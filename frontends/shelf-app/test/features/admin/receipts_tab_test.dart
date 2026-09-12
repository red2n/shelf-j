import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/auth/auth_notifier.dart';
import 'package:shelf_app/core/auth/auth_state.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';
import 'package:shelf_app/features/admin/receipts_tab.dart';

// ---------------------------------------------------------------------------
// The legal receipt register: the series a store runs, the documents in
// order, and the inspector's question answered from the table. A manager sets
// a prefix; nothing here renumbers, deletes or edits a receipt, and the test
// would fail if it did.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
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

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool intact = true;
  int putStatus = 200;
  String putError = 'RECEIPT_PREFIX_INVALID';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body;
    var status = 200;
    if (o.path.endsWith('/fiscal-receipts/series') && o.method == 'PUT') {
      status = putStatus;
      body = status == 200
          ? '{"data":{"storeId":"s1","seriesCode":"MAIN","period":"2026","nextNumber":3,"prefix":"GB-A"}}'
          : '{"error":{"code":"$putError","message":"a prefix is letters, digits and hyphens, at most 16"}}';
    } else if (o.path.endsWith('/fiscal-receipts/series')) {
      body = '{"data":[{"storeId":"s1","seriesCode":"MAIN","period":"2026","nextNumber":3,"prefix":"GB-A"}]}';
    } else if (o.path.endsWith('/fiscal-receipts/audit')) {
      body = intact
          ? '{"data":{"firstNumber":1,"lastNumber":2,"issued":2,"expected":2,"intact":true,"gaps":[]}}'
          : '{"data":{"firstNumber":1,"lastNumber":5,"issued":3,"expected":5,"intact":false,"gaps":[{"from":2,"to":3}]}}';
    } else if (o.path.endsWith('/fiscal-receipts')) {
      body = '{"data":['
          '{"fullNumber":"GB-A-2026-000001","number":1,"orderId":"01a090ae-611e-701e-a773-cff68a489efe","issuedAt":"2026-09-12T10:00:00Z","grossTotal":12.50,"currency":"GBP"},'
          '{"fullNumber":"GB-A-2026-000002","number":2,"orderId":"01a090ae-611e-701e-a773-cff68a489eff","issuedAt":"2026-09-12T10:05:00Z","grossTotal":3.00,"currency":"GBP","voidedAt":"2026-09-12T10:06:00Z"}'
          ']}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER', bool intact = true}) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()..intact = intact;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: ReceiptsTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('the series, the audit and the receipts in order are shown', (tester) async {
    final server = await _pump(tester);
    expect(find.text('MAIN · 2026'), findsOneWidget);
    expect(find.text('prefix GB-A · next number 3'), findsOneWidget);
    expect(find.text('Sequence intact'), findsOneWidget);
    expect(find.text('GB-A-2026-000001'), findsOneWidget);
    expect(find.text('GB-A-2026-000002'), findsOneWidget);
    // A voided sale keeps its number and is shown as void, not removed.
    expect(find.textContaining('VOID'), findsOneWidget);
    // Every read went to the admin routes, filtered by store, series and year.
    final audit = server.requests.firstWhere((r) => r.path.endsWith('/audit'));
    expect(audit.queryParameters, {'storeId': 's1', 'series': 'MAIN', 'period': DateTime.now().year.toString()});
    // Nothing on this screen deletes or edits a receipt.
    expect(server.requests.where((r) => r.method != 'GET'), isEmpty);
  });

  testWidgets('a broken sequence is shown with the missing range', (tester) async {
    await _pump(tester, intact: false);
    expect(find.text('Sequence has gaps'), findsOneWidget);
    expect(find.text('Missing 2–3'), findsOneWidget);
    expect(find.textContaining('issued 3 · expected 5'), findsOneWidget);
  });

  testWidgets('a manager sets a prefix; the server refuses a bad one and the screen says so',
      (tester) async {
    final server = await _pump(tester)..putStatus = 400;
    await tester.tap(find.text('Set prefix'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Prefix'), 'not a prefix!');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.textContaining('letters, digits and hyphens'), findsOneWidget);
    expect(find.byType(AlertDialog), findsOneWidget, reason: 'the dialog stays open to fix it');

    server.putStatus = 200;
    await tester.enterText(find.widgetWithText(TextField, 'Prefix'), 'GB-A');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/admin/fiscal-receipts/series'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body, {'storeId': 's1', 'seriesCode': 'MAIN', 'period': '2026', 'prefix': 'GB-A'});
  });

  testWidgets('a cashier reads nothing here that they could change', (tester) async {
    await _pump(tester, role: 'CASHIER');
    // The screen renders read-only for the role that cannot set a prefix — the
    // server refuses it anyway; this keeps the button from being offered.
    expect(find.text('Set prefix'), findsNothing);
  });
}
