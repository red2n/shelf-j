import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/deposits_report_screen.dart';

// ---------------------------------------------------------------------------
// Container deposits (09.16): what was charged, what was paid back, what the
// scheme holds, by material — and what the screen says with nothing or an error.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Orders implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"from":"2026-09-01T00:00:00Z","to":"2026-10-01T00:00:00Z","currency":"EUR",'
      '"chargedContainers":0,"chargedAmount":0,"chargedVat":0,"refundedContainers":0,'
      '"refundedAmount":0,"unredeemedAmount":0,"byMaterial":[]}}';
  RequestOptions? last;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    last = o;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Orders> _pump(WidgetTester tester, void Function(_Orders) setUp) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final orders = _Orders();
  setUp(orders);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = orders;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: DepositsReportScreen())),
  ));
  await tester.pumpAndSettle();
  return orders;
}

const _germany = '{"data":{"from":"2026-09-01T00:00:00Z","to":"2026-10-01T00:00:00Z","currency":"EUR",'
    '"chargedContainers":120,"chargedAmount":30.00,"chargedVat":4.79,"refundedContainers":80,'
    '"refundedAmount":20.00,"unredeemedAmount":10.00,"byMaterial":['
    '{"material":"PET","chargedContainers":100,"chargedAmount":25.00,"chargedVat":3.99,"refundedContainers":70,"refundedAmount":17.50},'
    '{"material":"ALUMINIUM","chargedContainers":20,"chargedAmount":5.00,"chargedVat":0.80,"refundedContainers":10,"refundedAmount":2.50}]}}';

void main() {
  testWidgets('charged, refunded and unredeemed, with the VAT inside where the scheme taxes it',
      (tester) async {
    final orders = await _pump(tester, (o) => o.body = _germany);

    expect(orders.last!.path, '/order-svc/admin/reports/deposits');
    expect(orders.last!.queryParameters['from'], isNotNull);
    expect(orders.last!.queryParameters['to'], isNotNull);
    expect(find.byKey(const Key('deposits-charged')), findsOneWidget);
    expect(find.text('EUR 30.00'), findsOneWidget);
    expect(find.text('120 containers'), findsOneWidget);
    expect(find.text('EUR 20.00'), findsOneWidget);
    expect(find.text('EUR 10.00'), findsOneWidget);
    expect(find.byKey(const Key('deposits-vat')), findsOneWidget);
    expect(find.text('EUR 4.79'), findsOneWidget);
    expect(find.byKey(const Key('deposits-material-PET')), findsOneWidget);
    expect(find.text('PET plastic'), findsOneWidget);
    expect(find.text('100 sold · 70 returned'), findsOneWidget);
    expect(find.byKey(const Key('deposits-material-ALUMINIUM')), findsOneWidget);
  });

  testWidgets('a period with nothing says so, and no VAT figure where none was inside',
      (tester) async {
    await _pump(tester, (_) {});
    expect(find.text('No deposit was charged or refunded in this period.'), findsOneWidget);
    expect(find.byKey(const Key('deposits-vat')), findsNothing);
    expect(find.byKey(const Key('deposits-period')), findsOneWidget);
  });

  testWidgets('a failed load is said, with a retry', (tester) async {
    await _pump(tester, (o) {
      o.status = 500;
      o.body = '{}';
    });
    expect(find.text('Could not load the deposit report.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });
}
