import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';
import 'package:shelf_app/features/admin/reports_screen.dart';

// ---------------------------------------------------------------------------
// The four reports horizon 1 built server-side and never gave a client. Two
// things are worth pinning hard.
//
// First, the date format. The pickers speak yyyy-MM-dd; these four endpoints
// parse from/to with Instant.parse, which rejects a bare date — SJ-D9 from the
// other side of the wire. Sending the picker's value straight through would 400
// every time, and nothing in the widget tree would reveal why.
//
// Second, the Box 1 warning. The server's own DTO says a VAT/Box-1 mismatch is
// "a data fault the Box 1 query drops silently", and this is the screen someone
// files a return from.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Records the query parameters of every request and replies with canned bodies.
class _RecordingAdapter implements HttpClientAdapter {
  final List<({String path, Map<String, dynamic> query})> calls = [];
  final Map<String, String> bodyFor = {};

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    calls.add((path: options.path, query: Map.of(options.queryParameters)));
    final body = bodyFor.entries
        .firstWhere((e) => options.path.contains(e.key),
            orElse: () => const MapEntry('', '{"data":[]}'))
        .value;
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  ({String path, Map<String, dynamic> query}) callTo(String fragment) =>
      calls.firstWhere((c) => c.path.contains(fragment));
}

({ProviderContainer container, _RecordingAdapter adapter}) _harness(
    {Map<String, String> bodies = const {}}) {
  final adapter = _RecordingAdapter()..bodyFor.addAll(bodies);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  final container = ProviderContainer(overrides: [
    apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
  ]);
  addTearDown(container.dispose);
  container.read(reportDateRangeProvider.notifier).state =
      const ReportDateRange(from: '2026-08-01', to: '2026-08-31');
  return (container: container, adapter: adapter);
}

void main() {
  group('date handling — the endpoints parse instants, not dates', () {
    test('shrinkage widens the picker range to instants', () async {
      final h = _harness();
      await h.container.read(shrinkageReportProvider.future);

      final call = h.adapter.callTo('/reports/shrinkage');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-08-31T23:59:59Z');
    });

    test('tax summary does the same', () async {
      final h = _harness(bodies: {
        'tax-summary': '{"data":{"rows":[],"totals":{},"periodFrom":null,"periodTo":null}}'
      });
      await h.container.read(taxSummaryReportProvider.future);

      final call = h.adapter.callTo('/tax-summary');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-08-31T23:59:59Z');
    });

    test('`to` covers the whole final day, not midnight at its start', () async {
      // At T00:00:00Z a report run "to today" would exclude everything that
      // happened today — the most recent day, silently missing.
      final h = _harness();
      await h.container.read(shrinkageReportProvider.future);
      expect(h.adapter.callTo('/reports/shrinkage').query['to'],
          endsWith('T23:59:59Z'));
    });

    test('an empty range sends no date params rather than a malformed one',
        () async {
      final h = _harness();
      h.container.read(reportDateRangeProvider.notifier).state =
          const ReportDateRange(from: null, to: null);
      await h.container.read(shrinkageReportProvider.future);

      final call = h.adapter.callTo('/reports/shrinkage');
      expect(call.query.containsKey('from'), isFalse);
      expect(call.query.containsKey('to'), isFalse);
      expect(call.query['groupBy'], 'REASON');
    });
  });

  group('grouping', () {
    test('changing the grouping re-requests with it', () async {
      final h = _harness();
      await h.container.read(shrinkageReportProvider.future);
      h.container.read(shrinkageGroupingProvider.notifier).state = 'ACTOR';
      await h.container.read(shrinkageReportProvider.future);

      expect(h.adapter.calls.last.query['groupBy'], 'ACTOR');
    });

    test('valuation defaults to STORE — the site-level question comes first',
        () async {
      final h = _harness();
      await h.container.read(valuationReportProvider.future);
      expect(h.adapter.callTo('/reports/valuation').query['groupBy'], 'STORE');
    });
  });

  group('parsing', () {
    test('a shrinkage row keeps write-offs and finds apart', () async {
      final h = _harness(bodies: {
        'shrinkage': '{"data":[{"groupKey":"THEFT","qtyWrittenOff":100,'
            '"qtyFound":100,"netQty":0,"movements":7}]}'
      });
      final rows = await h.container.read(shrinkageReportProvider.future);

      // A store that wrote off 100 and found 100 others is not a store that did
      // nothing, and a net figure alone would say it was.
      expect(rows.single.qtyWrittenOff, 100);
      expect(rows.single.qtyFound, 100);
      expect(rows.single.netQty, 0);
      expect(rows.single.movements, 7);
    });

    test('a low-stock row keeps the signal that bound it', () async {
      final h = _harness(bodies: {
        'low-stock': '{"data":[{"storeId":"s-1","variantId":"v-1",'
            '"signal":"SAFETY_STOCK","reorderLevel":20,"availableQty":5,"shortfall":15}]}'
      });
      final rows = await h.container.read(lowStockReportProvider.future);
      expect(rows.single.signal, 'SAFETY_STOCK');
      expect(rows.single.shortfall, 15);
    });

    test('missing fields degrade to zero rather than throwing', () async {
      final h = _harness(bodies: {'valuation': '{"data":[{"groupKey":"s-1"}]}'});
      final rows = await h.container.read(valuationReportProvider.future);
      expect(rows.single.groupKey, 's-1');
      expect(rows.single.value, 0);
      expect(rows.single.unvaluedQty, 0);
    });
  });

  group('tax summary — Box 1 reconciliation', () {
    TaxSummaryReport report({required double vat, required double box1}) =>
        TaxSummaryReport.fromJson({
          'rows': const [],
          'totals': {
            'netAmount': 1000.0,
            'vatAmount': vat,
            'outputVat': box1,
            'grossAmount': 1000.0 + vat,
            'transactions': 3,
          },
        });

    test('agreeing totals raise nothing', () {
      expect(report(vat: 200, box1: 200).boxOneDisagrees, isFalse);
    });

    test('an exempt line carrying VAT is flagged', () {
      expect(report(vat: 200, box1: 180).boxOneDisagrees, isTrue);
    });

    test('a rounding-sized difference is not treated as a fault', () {
      expect(report(vat: 200.001, box1: 200).boxOneDisagrees, isFalse);
    });
  });

  group('the screen', () {
    Future<void> pump(WidgetTester tester, _RecordingAdapter adapter) async {
      tester.view.physicalSize = const Size(1400, 1000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final dio = Dio(BaseOptions(baseUrl: 'http://test'))
        ..httpClientAdapter = adapter;
      await tester.pumpWidget(ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
        child: const MaterialApp(home: Scaffold(body: ReportsScreen())),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('all four now have a way in', (tester) async {
      await pump(tester, _RecordingAdapter());
      for (final label in ['Low Stock', 'Stock Valuation', 'Shrinkage', 'Tax Summary']) {
        expect(find.text(label), findsWidgets, reason: '$label is not reachable');
      }
    });

    testWidgets('an empty report says so instead of showing a bare table',
        (tester) async {
      await pump(tester, _RecordingAdapter());
      await tester.tap(find.text('Low Stock').last);
      await tester.pumpAndSettle();
      expect(find.text('Nothing is below its reorder level.'), findsOneWidget);
    });

    testWidgets('a Box 1 mismatch warns before the return is filed',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['tax-summary'] = '{"data":{"rows":[{"groupKey":"ZERO",'
            '"exempt":true,"netAmount":100,"vatAmount":20,"grossAmount":120,'
            '"transactions":1}],"totals":{"netAmount":100,"vatAmount":20,'
            '"outputVat":0,"grossAmount":120,"transactions":1}}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Tax Summary').last);
      await tester.pumpAndSettle();

      expect(find.textContaining('does not match total VAT'), findsOneWidget);
      expect(find.textContaining('Check the rows below before filing'),
          findsOneWidget);
    });

    testWidgets('uncosted stock is called out, not folded into the value',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['valuation'] = '{"data":[{"groupKey":"s-1","method":"FIFO",'
            '"onHandQty":100,"unvaluedQty":40,"value":600}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Stock Valuation').last);
      await tester.pumpAndSettle();

      expect(find.textContaining('40 units carry no cost'), findsOneWidget);
      expect(find.textContaining('not counted as zero'), findsOneWidget);
    });

    testWidgets('a report with rows offers the CSV export', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['low-stock'] = '{"data":[{"storeId":"s-1","variantId":"v-1",'
            '"signal":"THRESHOLD","reorderLevel":10,"availableQty":2,"shortfall":8}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Low Stock').last);
      await tester.pumpAndSettle();
      expect(find.text('Export CSV'), findsOneWidget);
    });
  });
}
