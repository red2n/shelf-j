import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/age_checks_screen.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';

// The age-check register a manager shows a licensing officer: counts, the
// refusals with their reasons, and filters that ask the server the right
// question. Read-only — there is nothing on this screen that could edit a
// record, and the test would fail if there were.

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool empty = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body;
    if (o.path.endsWith('/age-checks/summary')) {
      body = empty
          ? '{"data":{"total":0,"passed":0,"refused":0,"refusedByReason":{},"byCategory":{}}}'
          : '{"data":{"total":5,"passed":3,"refused":2,"refusedByReason":{"NO_ID":1,"UNDER_AGE":1},"byCategory":{"ALCOHOL":4,"TOBACCO":1}}}';
    } else if (o.path.endsWith('/age-checks')) {
      body = empty
          ? '{"data":[],"meta":{}}'
          : '{"data":['
              '{"id":"a","storeId":"s1","variantId":"v1","category":"ALCOHOL","minimumAge":18,"country":"GB","storePolicy":false,"outcome":"REFUSED","reason":"NO_ID","checkedAt":"2026-09-12T10:00:00Z"},'
              '{"id":"b","storeId":"s1","variantId":"v2","category":"TOBACCO","minimumAge":18,"country":"GB","storePolicy":true,"outcome":"PASSED","idType":"PASS_CARD","checkedAt":"2026-09-12T09:00:00Z"}'
              '],"meta":{}}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester, {bool empty = false}) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()..empty = empty;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: AgeChecksScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('the counts and the refusals with their reasons are shown', (tester) async {
    await _pump(tester);
    expect(find.text('5'), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    expect(find.text('No ID shown: 1'), findsOneWidget);
    expect(find.textContaining('Refused — No ID shown'), findsOneWidget);
    expect(find.textContaining('Sale went ahead — PASS card'), findsOneWidget);
    expect(find.textContaining('(store policy)'), findsOneWidget);
  });

  testWidgets('an empty period says what an empty register means', (tester) async {
    await _pump(tester, empty: true);
    expect(find.textContaining('has not been checking'), findsOneWidget);
  });

  testWidgets('the filters ask the server, with an exclusive upper bound', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.text('Refusals'));
    await tester.pumpAndSettle();
    final register = server.requests.lastWhere((r) => r.path.endsWith('/age-checks'));
    expect(register.queryParameters['outcome'], 'REFUSED');
    final from = DateTime.parse(register.queryParameters['from'] as String);
    final to = DateTime.parse(register.queryParameters['to'] as String);
    expect(to.isAfter(from), isTrue);
    // The picker's last day is inclusive; the API's bound is exclusive, so "to" is one day past.
    expect(to.difference(from).inDays, 30);
  });

  testWidgets('nothing on the register can change a record', (tester) async {
    await _pump(tester);
    expect(find.byIcon(Icons.edit), findsNothing);
    expect(find.byIcon(Icons.delete), findsNothing);
    expect(find.widgetWithText(FilledButton, 'Save'), findsNothing);
  });
}
