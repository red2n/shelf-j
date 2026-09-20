import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sales_screen.dart';

// ---------------------------------------------------------------------------
// Gift card value says how it was paid for (17.11). A card sold is a liability
// against the money taken and one given away is a marketing cost, so issuing
// and reloading ask for it and send it, redeeming does not, and nothing is sent
// until it has been chosen.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    requests.add(options);
    final path = options.path;
    final Object body;
    if (path.endsWith('/transactions')) {
      body = {'data': []};
    } else if (options.method == 'GET' && path.contains('/gift-cards/')) {
      body = {
        'data': {
          'code': 'GC-7777',
          'currentBalance': 50,
          'initialBalance': 50,
          'status': 'ACTIVE',
          'currency': 'GBP',
        }
      };
    } else if (options.method == 'POST' && path.endsWith('/gift-cards')) {
      body = {
        'data': {'code': 'GC-1234'}
      };
    } else {
      body = {'data': {}};
    }
    return ResponseBody.fromString(jsonEncode(body), 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<RequestOptions> posts(String fragment) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(fragment))
      .toList();
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(
                id: 's1',
                name: 'High Street',
                code: 'HS',
                type: 'STORE',
                status: 'ACTIVE',
                country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: SalesScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _choosePaidBy(WidgetTester tester, String label) async {
  await tester.tap(find.byKey(const Key('gift-card-paid-by')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a card is not issued until it says how it was paid for, and then sends it',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Issue'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('High Street').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Amount'), '25');
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    expect(find.textContaining('say how it was paid for'), findsOneWidget);
    expect(server.posts('/gift-cards'), isEmpty);

    await _choosePaidBy(tester, 'Given away (promotional)');
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    final issue = server.posts('/gift-cards').single;
    expect(issue.data, containsPair('paidBy', 'PROMOTIONAL'));
    expect(issue.data, containsPair('storeId', 's1'));
    expect(issue.data, containsPair('amount', 25.0));
    expect(find.text('Gift card issued'), findsOneWidget);
  });

  testWidgets('a reload asks how it was paid for, and a redemption does not', (tester) async {
    final server = await _pump(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Gift card code'), 'GC-7777');
    await tester.tap(find.text('Look up'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Reload'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Amount'), '10');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    // Without a tender OK does nothing, and nothing is sent.
    expect(find.text('Reload gift card'), findsOneWidget);
    expect(server.posts('/reload'), isEmpty);
    await _choosePaidBy(tester, 'Cash');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(server.posts('/reload').single.data, {'amount': 10.0, 'paidBy': 'CASH'});

    await tester.tap(find.text('Redeem'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('gift-card-paid-by')), findsNothing);
    await tester.enterText(find.widgetWithText(TextField, 'Amount'), '5');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(server.posts('/redeem').single.data, {'amount': 5.0});
  });
}
