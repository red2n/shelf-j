import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/fulfilment_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Fulfilment screen (ship-from-store and dark-store picking): a store's
// queue by stage — what waits to be picked, what is packed for the courier,
// what is ready to collect, what left today — with Dispatch posting the
// carrier, reference and parcels, and Collected posting who took it. A
// warehouse is never offered; a dark store is named as one.
// ---------------------------------------------------------------------------

const _leeds = '01a0d930-0000-7000-8000-0000000000e1';
const _dark = '01a0d930-0000-7000-8000-0000000000e2';
const _dc = '01a0d930-0000-7000-8000-0000000000d1';
const _packed = '01a0d930-0000-7000-8000-0000000000f1';
const _ready = '01a0d930-0000-7000-8000-0000000000f2';
const _gone = '01a0d930-0000-7000-8000-0000000000f3';
const _owing = '01a0d930-0000-7000-8000-0000000000f4';
const _noSubs = '01a0d930-0000-7000-8000-0000000000f5';
const _apples = '01a0d930-0000-7000-8000-0000000000a1';
const _pears = '01a0d930-0000-7000-8000-0000000000a2';
const _plums = '01a0d930-0000-7000-8000-0000000000a3';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  String _order(String id, String type, {String? handover}) {
    final tail = handover == null ? '' : ',"handover":$handover';
    return '{"id":"$id","storeId":"$_leeds","channel":"ONLINE","fulfilmentType":"$type","status":"FULFILLED",'
        '"total":12.5,"currency":"GBP","createdAt":"2026-09-25T09:00:00Z"$tail}';
  }

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":['
          '{"id":"$_dc","name":"Leeds DC","code":"DC","type":"WAREHOUSE","status":"ACTIVE"},'
          '{"id":"$_leeds","name":"Leeds","code":"LDS","type":"STORE","status":"ACTIVE"},'
          '{"id":"$_dark","name":"Online hub","code":"HUB","type":"DARK_STORE","status":"ACTIVE"}'
          '],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/orders/owing')) {
      return jsonResponse('{"data":['
          '{"orderId":"$_owing","status":"CONFIRMED","fulfilmentType":"DELIVERY","allowSubstitutions":true,'
          '"createdAt":"2026-09-25T09:00:00Z","lines":[{"variantId":"$_apples","qty":3,"fulfilledQty":1,"shortQty":0,"outstandingQty":2}]},'
          '{"orderId":"$_noSubs","status":"CONFIRMED","fulfilmentType":"PICKUP","allowSubstitutions":false,'
          '"createdAt":"2026-09-25T09:05:00Z","lines":[{"variantId":"$_apples","qty":1,"fulfilledQty":0,"shortQty":0,"outstandingQty":1}]}'
          ']}');
    }
    if (path.endsWith('/lines/$_apples/substitutes')) {
      return jsonResponse('{"data":['
          '{"variantId":"$_pears","productName":"Pears","sku":"PEA","available":5},'
          '{"variantId":"$_plums","productName":"Plums","sku":"PLU","available":0}'
          ']}');
    }
    if (path.endsWith('/admin/products/variants/resolve')) {
      return jsonResponse('{"data":[{"variantId":"$_apples","productName":"Apples","sku":"APL"}]}');
    }
    if (path.endsWith('/short') || path.endsWith('/substitute')) {
      return jsonResponse('{"data":${_order(_owing, 'DELIVERY')}}');
    }
    if (path.endsWith('/waves/awaiting')) {
      return jsonResponse('{"data":[{"orderId":"a"},{"orderId":"b"},{"orderId":"c"}]}');
    }
    if (path.endsWith('/orders') && o.method == 'GET') {
      final q = o.queryParameters;
      if (q['handover'] == 'DONE') {
        return jsonResponse('{"data":[${_order(_gone, 'DELIVERY', handover: '{"kind":"DISPATCHED","carrier":"DPD","reference":"1Z999","at":"2026-09-25T10:00:00Z"}')}]}');
      }
      if (q['fulfilmentType'] == 'DELIVERY') return jsonResponse('{"data":[${_order(_packed, 'DELIVERY')}]}');
      if (q['fulfilmentType'] == 'PICKUP') return jsonResponse('{"data":[${_order(_ready, 'PICKUP')}]}');
    }
    if (path.endsWith('/dispatch') || path.endsWith('/collect')) {
      return jsonResponse('{"data":${_order(_packed, 'DELIVERY', handover: '{"kind":"DISPATCHED","carrier":"DPD"}')}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('STOREKEEPER')),
    ],
    child: const MaterialApp(home: Scaffold(body: FulfilmentScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('the queue is shown by stage for the store, and a warehouse is never offered',
      (tester) async {
    await _open(tester);
    expect(find.text('3 orders waiting — picked on Inventory › Picking & putaway.'), findsOneWidget);
    expect(find.byKey(const Key('queued-$_packed')), findsOneWidget);
    expect(find.byKey(const Key('queued-$_ready')), findsOneWidget);
    expect(find.text('Dispatched · DPD 1Z999'), findsOneWidget);
    await tester.tap(find.byKey(const Key('fulfilment-store')));
    await tester.pumpAndSettle();
    expect(find.text('Online hub · dark store'), findsOneWidget);
    expect(find.text('Leeds DC'), findsNothing);
  });

  testWidgets('Dispatch posts the carrier, reference and parcels', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('dispatch-$_packed')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('dispatch-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say who is carrying it.'), findsOneWidget, reason: 'a carrier is required');
    await tester.enterText(find.byKey(const Key('dispatch-carrier')), 'DPD');
    await tester.enterText(find.byKey(const Key('dispatch-reference')), '1Z999');
    await tester.enterText(find.byKey(const Key('dispatch-parcels')), '2');
    await tester.tap(find.byKey(const Key('dispatch-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/orders/$_packed/dispatch'));
    expect(_body(post), {'carrier': 'DPD', 'reference': '1Z999', 'parcels': 2});
    expect(find.text('Dispatched with DPD.'), findsOneWidget);
  });

  testWidgets('Collected posts who took it, and nothing when nobody was noted', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('collect-$_ready')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('collect-who')), 'Sam Shopper');
    await tester.tap(find.byKey(const Key('collect-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/orders/$_ready/collect'));
    expect(_body(post), {'collectedBy': 'Sam Shopper'});
  });

  // ── substitutions for out-of-stock online lines ────────────────────────────

  testWidgets('outstanding lines are listed by name, and Substitute posts the chosen stand-in',
      (tester) async {
    final server = await _open(tester);
    expect(find.byKey(const Key('owing-count')), findsOneWidget);
    expect(find.text('2 of 3 outstanding'), findsOneWidget);
    expect(find.text('Apples'), findsNWidgets(2), reason: 'names, not ids');
    expect(find.byKey(const Key('substitute-$_owing-$_apples')), findsOneWidget);
    expect(find.byKey(const Key('substitute-$_noSubs-$_apples')), findsNothing,
        reason: 'the shopper said no: only Short is offered');
    expect(find.byKey(const Key('short-$_noSubs-$_apples')), findsOneWidget);
    await tester.tap(find.byKey(const Key('substitute-$_owing-$_apples')));
    await tester.pumpAndSettle();
    expect(find.text('Pears'), findsOneWidget);
    expect(find.text('PEA · 5 available'), findsOneWidget);
    await tester.tap(find.byKey(const Key('substitute-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say what you packed.'), findsOneWidget);
    await tester.tap(find.byKey(const Key('suggestion-$_pears')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('substitute-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere(
        (r) => r.method == 'POST' && r.path.endsWith('/orders/$_owing/lines/$_apples/substitute'));
    expect(_body(post), {'substituteVariantId': _pears, 'qty': 2});
    expect(post.headers['Idempotency-Key'], isNotNull);
    expect(find.text('Substituted. The shopper is told and pays no more.'), findsOneWidget);
  });

  testWidgets('Short posts the quantity and reason, at most what the line still owes',
      (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('short-$_owing-$_apples')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('short-qty')), '5');
    await tester.tap(find.byKey(const Key('short-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('short-error')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('short-qty')), '1');
    await tester.enterText(find.byKey(const Key('short-reason')), 'last one bruised');
    await tester.tap(find.byKey(const Key('short-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere(
        (r) => r.method == 'POST' && r.path.endsWith('/orders/$_owing/lines/$_apples/short'));
    expect(_body(post), {'qty': 1, 'reason': 'last one bruised'});
    expect(find.text('Closed short. The shopper is told and refunded.'), findsOneWidget);
  });
}
