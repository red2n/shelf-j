import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/auth/auth_notifier.dart';
import 'package:shelf_app/core/auth/auth_state.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/recalls_screen.dart';

// ---------------------------------------------------------------------------
// Recalls, from the admin screen.
//
// The server takes stock off sale, holds what arrives later and refuses to
// close a recall a store has not finished. What the screen must get right is
// what it sends — a recall with its customer notice, a store's count and what
// became of the stock, a reason for every pack put back on sale — and that it
// never shows a failed load as "no open recalls".
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Adapter implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  (int, String) list = (200, '{"data":[]}');
  String detail = '{"data":{}}';
  (int, String) closeReply = (200, '{"data":{}}');

  @override
  void close({bool force = false}) {}

  ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final path = o.path;
    if (o.method == 'POST') {
      posts.add(o);
      if (path.endsWith('/close')) return _json(closeReply.$2, closeReply.$1);
      if (path.endsWith('/admin/recalls')) return _json(detail, 201);
      return _json('{"data":{}}', 200);
    }
    if (path.endsWith('/admin/inventory/recalls')) return _json(list.$2, list.$1);
    if (path.contains('/admin/inventory/recalls/')) return _json(detail, 200);
    if (path.contains('/admin/stores')) {
      return _json(
          '{"data":[{"id":"store-1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE"},'
          '{"id":"store-2","name":"Market Square","code":"MS","type":"STORE","status":"ACTIVE"}]}',
          200);
    }
    if (path.endsWith('/variants/resolve')) {
      return _json('{"data":[{"variantId":"v-1","productName":"Crunchy peanut butter","sku":"PB-340"}]}', 200);
    }
    if (path.endsWith('/admin/products')) {
      return _json('{"data":[{"id":"p-1","name":"Crunchy peanut butter","status":"ACTIVE"}]}', 200);
    }
    if (path.endsWith('/admin/products/p-1/variants')) {
      return _json('{"data":[{"id":"v-1","productId":"p-1","sku":"PB-340","status":"ACTIVE"}]}', 200);
    }
    return _json('{"data":[]}', 200);
  }
}

class _Auth extends AuthNotifier {
  final String role;
  final List<String> storeIds;
  _Auth(this.role, [this.storeIds = const []]);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: [role],
        storeIds: storeIds,
      );
}

const _summary = '''
{"data":[{"id":"r-1","reference":"FSA-PRIN-42","kind":"RECALL","hazard":"ALLERGEN","status":"OPEN",
 "openedAt":"2026-09-11T08:00:00Z","scopeLines":1,"storesAffected":2,"storesOutstanding":2,"qtyHeld":13}]}''';

const _detail = '''
{"data":{"id":"r-1","reference":"FSA-PRIN-42","kind":"RECALL","hazard":"ALLERGEN",
 "reason":"Undeclared peanut","customerNotice":"Do not eat. Return it for a full refund.",
 "source":"FSA","status":"OPEN","openedAt":"2026-09-11T08:00:00Z",
 "items":[{"id":"i-1","variantId":"v-1","batchNo":"L1","coversEveryPack":false}],
 "batches":[
   {"batchId":"b-1","storeId":"store-1","variantId":"v-1","batchNo":"L1","match":"IN_SCOPE","qtyAtQuarantine":10,"remainingQty":10,"quarantinedOn":"OPEN","released":false},
   {"batchId":"b-2","storeId":"store-2","variantId":"v-1","match":"LOT_UNKNOWN","qtyAtQuarantine":3,"remainingQty":3,"quarantinedOn":"OPEN","released":false}],
 "storeActions":[],
 "stores":[{"storeId":"store-1","qtyHeld":10,"outstanding":true},{"storeId":"store-2","qtyHeld":3,"outstanding":true}]}}''';

Future<_Adapter> _pump(WidgetTester tester, {String role = 'MANAGER', List<String> storeIds = const []}) async {
  final adapter = _Adapter()
    ..list = (200, _summary)
    ..detail = _detail;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  tester.view.physicalSize = const Size(1400, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role, storeIds)),
    ],
    child: const MaterialApp(home: Scaffold(body: RecallsScreen())),
  ));
  await tester.pumpAndSettle();
  return adapter;
}

Map<String, dynamic> _body(RequestOptions o) =>
    (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;

void main() {
  testWidgets('a failed load is an error, never "no open recalls"', (tester) async {
    final adapter = _Adapter()..list = (503, '{"error":{"code":"UNAVAILABLE","message":"Service unavailable"}}');
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => _Auth('STOREKEEPER')),
      ],
      child: const MaterialApp(home: Scaffold(body: RecallsScreen())),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No open recalls.'), findsNothing);
    expect(find.text('Service unavailable'), findsOneWidget);
  });

  testWidgets('a manager opens a recall with its notice, lot and dates', (tester) async {
    final adapter = await _pump(tester);
    await tester.tap(find.byKey(const Key('recall-open')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('recall-reference')), 'FSA-PRIN-42');
    await tester.enterText(find.byKey(const Key('recall-reason')), 'Undeclared peanut');

    // A recall without a notice for customers is refused before anything is sent.
    await tester.tap(find.byKey(const Key('recall-open-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Enter the notice for the tills'), findsOneWidget);
    expect(adapter.posts, isEmpty);

    await tester.enterText(find.byKey(const Key('recall-notice')), 'Do not eat. Return it for a full refund.');
    await tester.tap(find.text('Product *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Crunchy peanut butter').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Variant *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PB-340').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('recall-lot')), 'L1');
    await tester.enterText(find.byKey(const Key('recall-from')), '1 Oct');
    await tester.tap(find.byKey(const Key('recall-add-item')));
    await tester.pumpAndSettle();
    expect(find.text('Dates are written YYYY-MM-DD.'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('recall-from')), '2026-10-01');
    await tester.tap(find.byKey(const Key('recall-add-item')));
    await tester.pumpAndSettle();
    expect(find.text('Lot L1, dated 2026-10-01 or later'), findsOneWidget);

    await tester.tap(find.byKey(const Key('recall-open-save')));
    await tester.pumpAndSettle();

    final open = adapter.posts.single;
    expect(open.path, endsWith('/admin/recalls'));
    final body = _body(open);
    expect(body['kind'], 'RECALL');
    expect(body['customerNotice'], 'Do not eat. Return it for a full refund.');
    expect(body['items'], [
      {'variantId': 'v-1', 'batchNo': 'L1', 'expiryFrom': '2026-10-01'}
    ]);
  });

  testWidgets('a storekeeper cannot open one, records what their store found, and checks a pack',
      (tester) async {
    final adapter = await _pump(tester, role: 'STOREKEEPER', storeIds: ['store-2']);
    expect(find.byKey(const Key('recall-open')), findsNothing);

    await tester.tap(find.text('FSA-PRIN-42'));
    await tester.pumpAndSettle();
    expect(find.text('Do not eat. Return it for a full refund.'), findsOneWidget);
    // Only their own store can be acted on, and only an uncertain pack released.
    expect(find.byKey(const Key('recall-record-store-1')), findsNothing);
    expect(find.byKey(const Key('recall-release-b-1')), findsNothing);
    expect(find.byKey(const Key('recall-close')), findsNothing);

    await tester.ensureVisible(find.byKey(const Key('recall-release-b-2')));
    await tester.tap(find.byKey(const Key('recall-release-b-2')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('recall-text')), 'Pack shows lot L4');
    await tester.pump();
    await tester.tap(find.byKey(const Key('recall-text-confirm')));
    await tester.pumpAndSettle();
    final release = adapter.posts.last;
    expect(release.path, endsWith('/recalls/r-1/batches/b-2/release'));
    expect(_body(release), {'reason': 'Pack shows lot L4'});

    await tester.tap(find.byKey(const Key('recall-record-store-2')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('recall-qty-found')), '2');
    await tester.tap(find.text('Destroyed'));
    await tester.tap(find.byKey(const Key('recall-notice-displayed')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('recall-action-save')));
    await tester.pumpAndSettle();
    final action = adapter.posts.last;
    expect(action.path, endsWith('/recalls/r-1/stores/store-2/actions'));
    expect(_body(action), {'qtyFound': 2.0, 'disposition': 'DESTROYED', 'noticeDisplayed': true});
  });

  testWidgets('closing too early names the stores still holding stock', (tester) async {
    final adapter = await _pump(tester);
    adapter.closeReply = (
      409,
      '{"error":{"code":"RECALL_STORES_OUTSTANDING","message":"These stores still hold recalled stock",'
          '"details":["store-2"]}}'
    );
    await tester.tap(find.text('FSA-PRIN-42'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('recall-close')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('recall-text-confirm')));
    await tester.pumpAndSettle();

    expect(find.textContaining('Still holding recalled stock: Market Square.'), findsOneWidget);
  });
}
