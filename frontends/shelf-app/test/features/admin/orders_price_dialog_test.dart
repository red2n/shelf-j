import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/orders_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D41: a catalog-mode till order waits for a manager's prices. The dialog
// takes a unit price per line and the VAT, sends them to /price, refuses to
// send a line without a price, and shows the server's refusal in words.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int postStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') {
      final body = postStatus == 200
          ? '{"data":{"id":"o-1","status":"PENDING","currency":"GBP","total":16.2,"items":[]}}'
          : '{"error":{"code":"ORDER_NOT_AWAITING_PRICE","message":"only an order awaiting a price can be priced; this one is PENDING"}}';
      return ResponseBody.fromString(body, postStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    const body = '{"data":{"id":"o-1","status":"AWAITING_PRICE","currency":"GBP","total":0,'
        '"items":[{"variantId":"01a090ae-611e-7011-ae7d-1bd68c966ff6","qty":3,"unitPrice":0,"lineTotal":0},'
        '{"variantId":"01a090ae-611e-7011-ae7d-1bd68c966aa1","qty":1,"unitPrice":0,"lineTotal":0}]}}';
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => PriceOrderDialog(orderId: 'o-1', currency: 'GBP', onDone: () {}),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _postBody(_Server s) {
  final post = s.requests.singleWhere((r) => r.method == 'POST');
  return (post.data is String ? jsonDecode(post.data as String) : post.data) as Map<String, dynamic>;
}

void main() {
  testWidgets('every line is priced, the VAT given, and the order sent to /price', (tester) async {
    final server = await _pump(tester);
    expect(find.textContaining('× 3'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4.50');
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966aa1')), '2');
    await tester.enterText(find.byKey(const Key('price-tax')), '3.10');
    await tester.tap(find.text('Price and release'));
    await tester.pumpAndSettle();
    final body = _postBody(server);
    expect(body['lines'], [
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966ff6', 'unitPrice': 4.5},
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966aa1', 'unitPrice': 2},
    ]);
    expect(body['taxAmount'], 3.1);
    expect(server.requests.singleWhere((r) => r.method == 'POST').path, endsWith('/orders/o-1/price'));
    expect(find.textContaining('can be paid for now'), findsOneWidget);
  });

  testWidgets('a line left without a price is not sent', (tester) async {
    final server = await _pump(tester);
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4.50');
    await tester.tap(find.text('Price and release'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Give every line a price'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets("the server's refusal is shown and the dialog stays open", (tester) async {
    final server = await _pump(tester)..postStatus = 409;
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4.50');
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966aa1')), '2');
    await tester.tap(find.text('Price and release'));
    await tester.pumpAndSettle();
    expect(find.textContaining('this one is PENDING'), findsOneWidget);
    expect(find.text('Price order'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), hasLength(1));
  });
}
