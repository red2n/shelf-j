import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/auth/auth_notifier.dart';
import 'package:shelf_app/core/auth/auth_state.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/procurement_screen.dart';

// ---------------------------------------------------------------------------
// A supplier can be corrected after it is created (SJ-D34). The dialog opens
// with what the supplier has — a JPY supplier opens in JPY even though JPY is
// not on the picker — and sends the correction to PUT /suppliers/{id}. The
// server's refusal (an open order in the old currency) is shown in words.
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
      accessToken: 'a', refreshToken: 'r', userId: 'u', tenantId: 't', roles: [role]);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int putStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'PUT') {
      final body = putStatus == 200
          ? '{"data":{"id":"s-1","name":"Yen By Mistake","vatRegistered":true,"countryCode":"GB","currency":"GBP","paymentTermsDays":45}}'
          : '{"error":{"code":"PURCHASE_SUPPLIER_CURRENCY_IN_USE","message":"1 open purchase order(s) are denominated in JPY; receive, close or cancel them before changing the currency"}}';
      return ResponseBody.fromString(body, putStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    final body = o.path.endsWith('/suppliers')
        ? '{"data":[{"id":"s-1","name":"Yen By Mistake","vatRegistered":true,"vatNumber":"GB999999973","countryCode":"GB","currency":"JPY","paymentTermsDays":30}]}'
        : '{"data":[]}';
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
    ],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Suppliers'));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('a manager opens the supplier prefilled — in its own currency — and corrects it',
      (tester) async {
    final server = await _pump(tester);
    expect(find.text('Yen By Mistake'), findsOneWidget);
    await tester.tap(find.byTooltip('Edit supplier'));
    await tester.pumpAndSettle();
    expect(find.text('Edit supplier'), findsOneWidget);
    // Prefilled from the record, JPY included though the picker never offered it.
    expect(find.widgetWithText(TextFormField, 'Yen By Mistake'), findsOneWidget);
    expect(find.text('JPY'), findsWidgets);
    expect(find.widgetWithText(TextFormField, '30'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextFormField, '30'), '45');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/suppliers/s-1'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['paymentTermsDays'], 45);
    expect(body['currency'], 'JPY', reason: 'what was not touched is sent as it was');
    expect(body['vatNumber'], 'GB999999973');
    expect(find.text('Supplier updated.'), findsOneWidget);
  });

  testWidgets('the refusal for an open order in the old currency is shown in words',
      (tester) async {
    final server = await _pump(tester)..putStatus = 409;
    await tester.tap(find.byTooltip('Edit supplier'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.textContaining('open purchase order'), findsOneWidget);
    expect(find.text('Edit supplier'), findsOneWidget, reason: 'the dialog stays open');
    expect(server.requests.where((r) => r.method == 'PUT'), hasLength(1));
  });

  testWidgets('a cashier is not offered the edit', (tester) async {
    await _pump(tester, role: 'CASHIER');
    expect(find.text('Yen By Mistake'), findsOneWidget);
    expect(find.byTooltip('Edit supplier'), findsNothing);
  });
}
