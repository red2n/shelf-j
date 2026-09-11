import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/auth/auth_notifier.dart';
import 'package:shelf_app/core/auth/auth_state.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/food_safety_screen.dart';

// ---------------------------------------------------------------------------
// Food-safety checks, from the screen.
//
// The server judges every check and keeps the limits it judged against. What
// the app must get right is what it sends: a temperature as a reading and
// never as a verdict, a checklist as a verdict and never as a reading, one
// Idempotency-Key per attempt however often Save is pressed, and a failure
// followed by the question of what was done about it. These tests assert the
// requests that leave the app, not only what is drawn.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Adapter implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  String points = '{"data":[]}';
  int pointsStatus = 200;

  /// Statuses for successive POSTs to /records; the last one repeats.
  List<int> recordStatuses = [201];
  String recordResponse = '{"data":{}}';

  @override
  void close({bool force = false}) {}

  ResponseBody _json(String body, int status) =>
      ResponseBody.fromString(body, status, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<List<int>>? stream,
      Future<void>? cancel) async {
    final path = options.path;
    if (options.method == 'POST' || options.method == 'PUT') {
      posts.add(options);
      if (path.endsWith('/food-safety/records')) {
        final n = posts.where((p) => p.path.endsWith('/food-safety/records')).length;
        final status = recordStatuses[
            (n - 1).clamp(0, recordStatuses.length - 1)];
        return status < 300
            ? _json(recordResponse, status)
            : _json('{"error":{"code":"UNAVAILABLE","message":"Service unavailable"}}', status);
      }
      return _json('{"data":{}}', 200);
    }
    if (path.contains('/admin/stores')) {
      return _json(
          '{"data":[{"id":"store-1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE"}]}',
          200);
    }
    if (path.endsWith('/food-safety/points')) return _json(points, pointsStatus);
    return _json('{"data":[]}', 200);
  }
}

class _Auth extends AuthNotifier {
  final String role;
  _Auth(this.role);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: [role],
      );
}

const _chiller = '''
{"id":"pt-1","storeId":"store-1","name":"Dairy chiller 1",
 "checkType":{"id":"t-1","code":"CHILLED_STORAGE","name":"Chilled storage","kind":"TEMPERATURE","maxValue":8.00,"statutory":true,"platform":true},
 "maxValue":8.00,"frequencyHours":4,"active":true,"dueStatus":"OK","openFailures":0}''';

const _overdueFreezer = '''
{"id":"pt-2","storeId":"store-1","name":"Zzz freezer",
 "checkType":{"id":"t-2","code":"FROZEN_STORAGE","name":"Frozen storage","kind":"TEMPERATURE","maxValue":-18.00},
 "maxValue":-18.00,"frequencyHours":4,"active":true,"dueStatus":"OVERDUE","openFailures":0}''';

const _opening = '''
{"id":"pt-3","storeId":"store-1","name":"Opening checks",
 "checkType":{"id":"t-3","code":"OPENING_CHECKS","name":"Opening checks","kind":"PASS_FAIL"},
 "frequencyHours":24,"active":true,"dueStatus":"DUE","openFailures":0}''';

const _failedRecord = '''
{"data":{"id":"rec-1","pointId":"pt-1","pointName":"Dairy chiller 1","checkTypeName":"Chilled storage",
 "kind":"TEMPERATURE","value":9.50,"maxValue":8.00,"result":"FAIL","openFailure":true,
 "correctiveActionCount":0,"recordedAt":"2026-09-11T08:00:00Z"}}''';

Future<_Adapter> _pump(WidgetTester tester,
    {String role = 'STOREKEEPER', String points = '{"data":[]}', int pointsStatus = 200}) async {
  final adapter = _Adapter()
    ..points = points
    ..pointsStatus = pointsStatus;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  tester.view.physicalSize = const Size(1400, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: FoodSafetyScreen())),
  ));
  await tester.pumpAndSettle();
  return adapter;
}

Map<String, dynamic> _body(RequestOptions o) =>
    (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;

void main() {
  testWidgets('a warm reading is shown to fail, saved as a failure, and asks what was done',
      (tester) async {
    final adapter = await _pump(tester, points: '{"data":[$_chiller]}');
    adapter.recordResponse = _failedRecord;

    await tester.tap(find.text('Record'));
    await tester.pumpAndSettle();
    expect(find.text('Limit ≤ 8.00 °C — a legal limit'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('fs-reading')), '9.5');
    await tester.pump();
    expect(find.textContaining('Outside the limit'), findsOneWidget);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final record = adapter.posts.single;
    expect(record.path, endsWith('/inventory-svc/admin/inventory/food-safety/records'));
    expect(_body(record)['value'], 9.5);
    expect(_body(record).containsKey('passed'), isFalse,
        reason: 'a temperature is judged by the server, never declared a pass here');
    expect(record.headers['Idempotency-Key'], isNotEmpty);

    expect(find.text('Record what was done'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('fs-action')), 'Moved stock to the walk-in');
    await tester.pump();
    await tester.tap(find.text('Save action'));
    await tester.pumpAndSettle();

    final action = adapter.posts.last;
    expect(action.path, endsWith('/food-safety/records/rec-1/corrective-actions'));
    expect(_body(action)['action'], 'Moved stock to the walk-in');
    expect(_body(action)['foodDisposition'], 'NONE');
    expect(find.text('Record what was done'), findsNothing);
  });

  testWidgets('pressing Save again after a failure resends the same key', (tester) async {
    final adapter = await _pump(tester, points: '{"data":[$_chiller]}');
    adapter
      ..recordStatuses = [503, 201]
      ..recordResponse =
          '{"data":{"id":"rec-2","pointId":"pt-1","pointName":"Dairy chiller 1","kind":"TEMPERATURE","value":4.0,"result":"PASS","openFailure":false,"correctiveActionCount":0}}';

    await tester.tap(find.text('Record'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('fs-reading')), '4');
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Service unavailable'), findsOneWidget);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(adapter.posts, hasLength(2));
    expect(adapter.posts[1].headers['Idempotency-Key'],
        adapter.posts[0].headers['Idempotency-Key'],
        reason: 'a retry of one reading must not record it twice');
    expect(find.text('Record what was done'), findsNothing);
  });

  testWidgets('a checklist sends a verdict and never a reading', (tester) async {
    final adapter = await _pump(tester, points: '{"data":[$_opening]}');
    adapter.recordResponse =
        '{"data":{"id":"rec-3","pointId":"pt-3","pointName":"Opening checks","kind":"PASS_FAIL","result":"PASS","openFailure":false,"correctiveActionCount":0}}';

    await tester.tap(find.text('Record'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('fs-reading')), findsNothing);
    await tester.tap(find.text('Passed'));
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final body = _body(adapter.posts.single);
    expect(body['passed'], isTrue);
    expect(body.containsKey('value'), isFalse);
  });

  testWidgets('an overdue check is listed before one that is done', (tester) async {
    await _pump(tester, points: '{"data":[$_chiller,$_overdueFreezer]}');
    final titles = tester
        .widgetList<ListTile>(find.byType(ListTile))
        .map((t) => (t.title as Text).data)
        .toList();
    expect(titles.first, 'Zzz freezer');
    expect(find.text('1 overdue'), findsOneWidget);
  });

  testWidgets('a storekeeper records and reads, but does not set up or sign off',
      (tester) async {
    await _pump(tester);
    expect(find.text('Today'), findsOneWidget);
    expect(find.text('Diary'), findsOneWidget);
    expect(find.text('Setup'), findsNothing);
    expect(find.text('Reviews'), findsNothing);
  });

  testWidgets('a manager also sets up the checks and signs them off', (tester) async {
    await _pump(tester, role: 'MANAGER');
    expect(find.text('Setup'), findsOneWidget);
    expect(find.text('Reviews'), findsOneWidget);
  });

  testWidgets('checks that cannot be loaded say so, not that nothing is due', (tester) async {
    await _pump(tester, points: '{}', pointsStatus: 500);
    expect(find.textContaining('No checks are set up'), findsNothing);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('switching a check off asks why and sends the reason', (tester) async {
    final adapter = await _pump(tester, role: 'MANAGER', points: '{"data":[$_chiller]}');
    await tester.tap(find.text('Setup'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Switch off'));
    await tester.pumpAndSettle();
    expect(find.text('Confirm'), findsOneWidget);
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();
    expect(adapter.posts, isEmpty, reason: 'no switch without a reason');

    await tester.enterText(find.byKey(const Key('fs-reason')), 'Chiller replaced');
    await tester.pump();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    final post = adapter.posts.single;
    expect(post.path, endsWith('/inventory-svc/admin/food-safety/points/pt-1/deactivate'));
    expect(_body(post)['reason'], 'Chiller replaced');
  });
}
