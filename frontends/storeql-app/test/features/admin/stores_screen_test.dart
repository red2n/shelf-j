import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/stores_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D54 at the screen: editing a store sends the store's own time zone. The
// dialog used to start on 'UTC' and send it when the field was empty, which
// moved a store onto the wrong clock just by renaming it.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Auth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: ['OWNER'],
        storeIds: [],
      );
}

class _Server implements HttpClientAdapter {
  final String store;
  final List<RequestOptions> requests = [];
  _Server(this.store);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    var body = '{"data":[],"meta":{}}';
    if (o.path.endsWith('/admin/stores') && o.method == 'GET') {
      body = '{"data":[$store],"meta":{}}';
    } else if (o.path.contains('/admin/stores/') && o.method == 'PUT') {
      body = '{"data":$store}';
    } else if (o.path.endsWith('/admin/tenant')) {
      body = '{"data":{"id":"tenant-1","name":"Shop","status":"ACTIVE","currency":"USD","country":"US"}}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }

  Map<String, dynamic>? get saved {
    final puts = requests.where((r) => r.method == 'PUT' && r.path.contains('/admin/stores/'));
    if (puts.isEmpty) return null;
    final data = puts.last.data;
    return (data is String ? jsonDecode(data) : data) as Map<String, dynamic>;
  }
}

String _store({String? timezone}) => jsonEncode({
      'id': 'store-1',
      'name': 'Main',
      'code': 'MAIN',
      'type': 'STORE',
      'status': 'ACTIVE',
      'country': 'US',
      'timezone': ?timezone,
      'showPrices': true,
      'enabledPaymentMethods': ['CASH', 'CARD'],
    });

Future<_Server> _pump(WidgetTester tester, String store) async {
  final server = _Server(store);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  tester.view.physicalSize = const Size(1400, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(_Auth.new),
    ],
    child: const MaterialApp(home: StoresScreen()),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('editing a store sends its own time zone, never UTC', (tester) async {
    final server = await _pump(tester, _store(timezone: 'America/Chicago'));
    await tester.tap(find.text('Main').first);
    await tester.pumpAndSettle();

    expect(find.text('America/Chicago'), findsWidgets);
    expect(find.text('UTC'), findsNothing);
    await tester.tap(find.text('Save changes'));
    await tester.pumpAndSettle();

    expect(server.saved, isNotNull);
    expect(server.saved!['timezone'], 'America/Chicago');
  });

  testWidgets('a store with no zone must be given one before it can be saved', (tester) async {
    final server = await _pump(tester, _store());
    await tester.tap(find.text('Main').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save changes'));
    await tester.pumpAndSettle();

    // Nothing is sent, so nothing is filled in on the store's behalf.
    expect(server.saved, isNull);
  });
}
