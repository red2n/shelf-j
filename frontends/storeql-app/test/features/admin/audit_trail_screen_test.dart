import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/audit_trail_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The business audit trail (20.11): order-svc's events and inventory's
// adjustments as one timeline, newest first, naming who did what. The filters
// ask the server; "Load older" pages order-svc only; a refusal is shown in
// words; nothing on the screen can write.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool empty = false;
  bool forbidden = false;
  final DateTime now = DateTime.now().toUtc();

  String _at(int hoursAgo) => now.subtract(Duration(hours: hoursAgo)).toIso8601String();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body;
    var status = 200;
    if (o.path.endsWith('/admin/audit/events')) {
      if (forbidden) {
        status = 403;
        body = '{"error":{"code":"FORBIDDEN","message":"management role required"}}';
      } else if (empty) {
        body = '{"data":[],"meta":{}}';
      } else if (o.queryParameters['after'] == 'c1') {
        body = '{"data":[{"id":"e-3","type":"NO_SALE","occurredAt":"${_at(4)}","actorId":"u-3",'
            '"storeId":"s1","reason":"drawer check"}],"meta":{"nextCursor":null}}';
      } else if (o.queryParameters['type'] == 'VOID') {
        body = '{"data":[{"id":"e-1","type":"VOID","occurredAt":"${_at(1)}","actorId":"u-1",'
            '"storeId":"s1","orderId":"o-1","reason":"rang up twice"}],"meta":{}}';
      } else {
        body = '{"data":['
            '{"id":"e-1","type":"VOID","occurredAt":"${_at(1)}","actorId":"u-1","storeId":"s1","orderId":"o-1","reason":"rang up twice"},'
            '{"id":"e-2","type":"DISCOUNT","occurredAt":"${_at(3)}","actorId":"u-2","storeId":"s1","orderId":"o-2","amount":2.0,"reason":"damaged box","detail":"MANAGER"}'
            '],"meta":{"nextCursor":"c1"}}';
      }
    } else if (o.path.endsWith('/admin/inventory/movements')) {
      body = empty
          ? '{"data":[]}'
          : '{"data":[{"id":"m-1","storeId":"s1","variantId":"v-1","type":"ADJUST","qty":-3,'
              '"reasonCode":"DAMAGED","actorId":"u-1","createdAt":"${_at(2)}"}]}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester, {bool empty = false, bool forbidden = false}) async {
  tester.view.physicalSize = const Size(1200, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..empty = empty
    ..forbidden = forbidden;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
      staffProvider.overrideWith((ref) async => const [
            StaffMember(id: 'a1', userId: 'u-1', storeId: 's1', role: 'MANAGER', assignedAt: ''),
            StaffMember(id: 'a2', userId: 'u-2', storeId: 's1', role: 'CASHIER', assignedAt: ''),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: AuditTrailScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Iterable<RequestOptions> _trailRequests(_Server s) =>
    s.requests.where((r) => r.path.endsWith('/admin/audit/events'));

double _top(WidgetTester tester, String text) => tester.getTopLeft(find.text(text)).dy;

void main() {
  testWidgets('both sources are one timeline, newest first, naming who did what', (tester) async {
    final server = await _pump(tester);
    expect(find.text('Void'), findsOneWidget);
    expect(find.text('Stock adjustment · -3 × v-1'), findsOneWidget);
    expect(find.text('Discount · 2.00 (MANAGER)'), findsOneWidget);
    // The adjustment from inventory sits between the two till events by time.
    expect(_top(tester, 'Void') < _top(tester, 'Stock adjustment · -3 × v-1'), isTrue);
    expect(_top(tester, 'Stock adjustment · -3 × v-1') < _top(tester, 'Discount · 2.00 (MANAGER)'), isTrue);
    expect(find.textContaining('by u-1 · order o-1 · rang up twice'), findsOneWidget);
    expect(find.textContaining('by u-2 · order o-2 · damaged box'), findsOneWidget);
    expect(find.text('Till'), findsNWidgets(2));
    expect(find.text('Stock'), findsOneWidget);
    // The first page asked order-svc with the period and inventory for adjustments only.
    final first = _trailRequests(server).first;
    expect(first.queryParameters['from'], isNotNull);
    expect(first.queryParameters['to'], isNotNull);
    expect(first.queryParameters.containsKey('type'), isFalse);
    final stock = server.requests.singleWhere((r) => r.path.endsWith('/admin/inventory/movements'));
    expect(stock.queryParameters['type'], 'ADJUST');
    // With rows loaded, the export is offered.
    expect(tester.widget<OutlinedButton>(find.byKey(const Key('audit-export'))).onPressed, isNotNull);
  });

  testWidgets('Load older pages order-svc with its cursor and merges the page in', (tester) async {
    final server = await _pump(tester);
    expect(find.byKey(const Key('audit-load-older')), findsOneWidget);
    await tester.tap(find.byKey(const Key('audit-load-older')));
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters['after'], 'c1');
    expect(find.text('No sale'), findsOneWidget);
    expect(find.textContaining('by u-3 · drawer check'), findsOneWidget);
    expect(_top(tester, 'Discount · 2.00 (MANAGER)') < _top(tester, 'No sale'), isTrue);
    // Nothing older remains, so the button goes.
    expect(find.byKey(const Key('audit-load-older')), findsNothing);
    // Adjustments were read once; paging never re-reads them.
    expect(server.requests.where((r) => r.path.endsWith('/admin/inventory/movements')), hasLength(1));
  });

  testWidgets('choosing one kind of action asks the server for it and skips the other source',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('audit-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voids').last);
    await tester.pumpAndSettle();
    expect(_trailRequests(server).last.queryParameters['type'], 'VOID');
    expect(server.requests.where((r) => r.path.endsWith('/admin/inventory/movements')), hasLength(1));
    expect(find.text('Void'), findsOneWidget);
    expect(find.text('Stock adjustment · -3 × v-1'), findsNothing);
    expect(find.text('Discount · 2.00 (MANAGER)'), findsNothing);
  });

  testWidgets('an empty period says so, and there is nothing to export', (tester) async {
    await _pump(tester, empty: true);
    expect(find.textContaining('Nothing recorded in this period'), findsOneWidget);
    expect(tester.widget<OutlinedButton>(find.byKey(const Key('audit-export'))).onPressed, isNull);
  });

  testWidgets("the server's refusal is shown in words", (tester) async {
    await _pump(tester, forbidden: true);
    expect(find.text('management role required'), findsOneWidget);
    expect(find.text('Void'), findsNothing);
  });

  testWidgets('nothing on the screen writes: every request is a GET', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('audit-load-older')));
    await tester.pumpAndSettle();
    expect(server.requests.every((r) => r.method == 'GET'), isTrue);
  });
}
