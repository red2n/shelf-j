import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/accounting_section.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Accounting connectors (17.9) on the Integrations screen: the owner connects
// the package the business keeps its books in, sees what has been pushed,
// pushes now, maps accounts, and deals with a journal the package refused; a
// manager sees the same and cannot connect or disconnect.
// ---------------------------------------------------------------------------
const _failed = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d01';
const _delivered = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d02';

Map<String, dynamic> _connection({String provider = 'SIMULATED', bool active = true}) => {
      'id': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d10',
      'provider': provider,
      'status': active ? 'ACTIVE' : 'DISABLED',
      'settings': provider == 'XERO' ? {'tenantId': 'org-77'} : <String, String>{},
      'syncFrom': '2026-09-01',
      'hasRefreshToken': provider == 'XERO',
      'lastSyncAt': '2026-09-23T09:00:00Z',
      'lastError': null,
      'disabledReason': null,
      'counts': {'pending': 1, 'delivered': 12, 'failed': 1, 'uncertain': 0, 'skipped': 0},
    };

List<Map<String, dynamic>> _providers() => [
      {'code': 'XERO', 'name': 'Xero', 'settings': ['tenantId'], 'optional': <String>[], 'tokens': 'A Xero custom connection'},
      {'code': 'QUICKBOOKS', 'name': 'QuickBooks Online', 'settings': ['realmId'], 'optional': ['environment'], 'tokens': 'An Intuit app'},
      {'code': 'SAGE', 'name': 'Sage Business Cloud Accounting', 'settings': ['businessId'], 'optional': <String>[], 'tokens': 'A Sage developer app'},
      {'code': 'SIMULATED', 'name': 'Simulated (the platform\'s stand-in)', 'settings': <String>[], 'optional': ['refuse'], 'tokens': 'None'},
    ];

Map<String, dynamic> _sync(String id, String status, String description, {String? externalId, String? error}) => {
      'id': id,
      'journalId': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d2${id.substring(id.length - 1)}',
      'status': status,
      'attempts': status == 'DELIVERED' ? 1 : 2,
      'externalId': externalId,
      'lastError': error,
      'nextAttemptAt': status == 'PENDING' ? '2026-09-23T09:05:00Z' : null,
      'createdAt': '2026-09-23T09:00:00Z',
      'deliveredAt': status == 'DELIVERED' ? '2026-09-23T09:00:02Z' : null,
      'entryDate': '2026-09-23',
      'description': description,
      'sourceType': 'JOURNAL',
      'total': '120.00',
    };

class _Server implements HttpClientAdapter {
  bool connected;
  final List<RequestOptions> requests = [];
  _Server({required this.connected});

  List<RequestOptions> of(String method, String pathEnd) => requests.where((r) => r.method == method && r.path.endsWith(pathEnd)).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/accounting/providers')) return jsonResponse(jsonEncode({'data': _providers()}));
    if (path.endsWith('/accounting/connection')) {
      if (o.method == 'PUT') {
        connected = true;
        return jsonResponse(jsonEncode({'data': _connection(provider: (o.data as Map)['provider'] as String)}));
      }
      if (o.method == 'DELETE') {
        connected = false;
        return jsonResponse(jsonEncode({'data': null}));
      }
      if (!connected) return jsonResponse(jsonEncode({'code': 'ACCOUNTING_NOT_CONNECTED'}), 404);
      return jsonResponse(jsonEncode({'data': _connection()}));
    }
    if (path.endsWith('/connection/sync')) {
      return jsonResponse(jsonEncode({'data': {'queued': 3, 'delivered': 2, 'failed': 1, 'uncertain': 0}}));
    }
    if (path.endsWith('/connection/accounts')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': 'SIM-1001', 'code': '1001', 'name': 'Stock', 'type': 'ASSET'},
          {'id': 'SIM-1200', 'code': '1200', 'name': 'Bank', 'type': 'BANK'},
        ],
      }));
    }
    if (path.endsWith('/connection/mappings')) {
      if (o.method == 'PUT') return jsonResponse(jsonEncode({'data': (o.data as Map)['mappings']}));
      return jsonResponse(jsonEncode({'data': [{'nominalCode': '1001', 'externalAccount': 'SIM-1001', 'externalName': null}]}));
    }
    if (path.contains('/syncs/') && o.method == 'POST') {
      return jsonResponse(jsonEncode({'data': _sync(_failed, path.endsWith('/skip') ? 'SKIPPED' : 'PENDING', 'Suspense')}));
    }
    if (path.endsWith('/accounting/syncs')) {
      return jsonResponse(jsonEncode({
        'data': {
          'items': [
            _sync(_failed, 'FAILED', 'Suspense', error: 'Simulated package refused: unknown account \'9999\''),
            _sync(_delivered, 'DELIVERED', 'Rent', externalId: 'sim-0199'),
          ],
          'nextCursor': null,
        },
      }));
    }
    return jsonResponse(jsonEncode({'data': null}), 404);
  }
}

Future<_Server> _pump(WidgetTester tester, {required bool owner, bool connected = true}) async {
  tester.view.physicalSize = const Size(1400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(connected: connected);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: AccountingSection(owner: owner),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('with nothing connected the owner is offered a package; a manager is not', (tester) async {
    await _pump(tester, owner: true, connected: false);
    expect(find.byKey(const Key('accounting-none')), findsOneWidget);
    expect(find.byKey(const Key('accounting-connect')), findsOneWidget);
    expect(find.byKey(const Key('accounting-card')), findsNothing);

    await _pump(tester, owner: false, connected: false);
    expect(find.byKey(const Key('accounting-connect')), findsNothing);
  });

  testWidgets('connecting the stand-in asks only for the day to push from; Xero asks for the organisation and its tokens', (tester) async {
    final server = await _pump(tester, owner: true, connected: false);
    await tester.tap(find.byKey(const Key('accounting-connect')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('acct-setting-tenantId')), findsOneWidget, reason: 'Xero is offered first');
    expect(find.byKey(const Key('acct-access')), findsOneWidget);

    await tester.enterText(find.byKey(const Key('acct-setting-tenantId')), 'org-77');
    await tester.enterText(find.byKey(const Key('acct-access')), 'tok');
    await tester.enterText(find.byKey(const Key('acct-refresh')), 'ref');
    await tester.enterText(find.byKey(const Key('acct-client-id')), 'cid');
    await tester.enterText(find.byKey(const Key('acct-client-secret')), 'sec');
    await tester.enterText(find.byKey(const Key('acct-sync-from')), '2026-09-01');
    await tester.tap(find.byKey(const Key('acct-submit')));
    await tester.pumpAndSettle();
    final sent = server.of('PUT', '/accounting/connection').single;
    final body = sent.data as Map;
    expect(body['provider'], 'XERO');
    expect((body['settings'] as Map)['tenantId'], 'org-77');
    expect((body['credentials'] as Map)['accessToken'], 'tok');
    expect((body['credentials'] as Map)['clientSecret'], 'sec');
    expect(body['syncFrom'], '2026-09-01');
    expect(find.text('Xero connected'), findsOneWidget);
  });

  testWidgets('the stand-in needs no tokens and sends none', (tester) async {
    final server = await _pump(tester, owner: true, connected: false);
    await tester.tap(find.byKey(const Key('accounting-connect')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('acct-provider')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Simulated (the platform\'s stand-in)').last);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('acct-access')), findsNothing);
    expect(find.byKey(const Key('acct-setting-refuse')), findsOneWidget, reason: 'an optional setting is offered, not required');
    await tester.tap(find.byKey(const Key('acct-submit')));
    await tester.pumpAndSettle();
    final body = server.of('PUT', '/accounting/connection').single.data as Map;
    expect(body['provider'], 'SIMULATED');
    expect(body.containsKey('credentials'), isFalse);
    expect(body['settings'], isEmpty);
  });

  testWidgets('connected: the card says what is where, Push now pushes, and the pushes are listed with what needs a person', (tester) async {
    final server = await _pump(tester, owner: false);
    expect(find.byKey(const Key('accounting-card')), findsOneWidget);
    expect(find.text('Simulated package'), findsOneWidget);
    expect(find.text('Delivered 12'), findsOneWidget);
    expect(find.text('Failed 1'), findsOneWidget);
    expect(find.byKey(const Key('accounting-disconnect')), findsNothing, reason: 'a manager cannot disconnect');

    await tester.tap(find.byKey(const Key('accounting-sync')));
    await tester.pumpAndSettle();
    expect(server.of('POST', '/connection/sync'), hasLength(1));
    expect(find.text('3 queued, 2 pushed, 1 refused'), findsOneWidget);

    expect(find.text('Rent'), findsOneWidget);
    expect(find.textContaining('in the package as sim-0199'), findsOneWidget);
    expect(find.text('Suspense'), findsOneWidget);
    expect(find.textContaining('unknown account'), findsOneWidget);
    expect(find.byKey(const Key('sync-retry-$_failed')), findsOneWidget);
    expect(find.byKey(const Key('sync-retry-$_delivered')), findsNothing, reason: 'a delivered journal is not pushed again');

    await tester.tap(find.byKey(const Key('sync-retry-$_failed')));
    await tester.pumpAndSettle();
    expect(server.of('POST', '/syncs/$_failed/retry'), hasLength(1));

    await tester.tap(find.byKey(const Key('sync-skip-$_failed')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('sync-skip-reason')), 'entered by hand');
    await tester.tap(find.byKey(const Key('sync-skip-confirm')));
    await tester.pumpAndSettle();
    final skipped = server.of('POST', '/syncs/$_failed/skip').single;
    expect((skipped.data as Map)['reason'], 'entered by hand');
  });

  testWidgets('mapping accounts shows what is mapped, offers the package\'s chart, and saves the whole mapping', (tester) async {
    final server = await _pump(tester, owner: true);
    await tester.tap(find.byKey(const Key('accounting-map')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('map-code-0')), findsOneWidget);
    expect((tester.widget(find.byKey(const Key('map-code-0'))) as TextField).controller!.text, '1001');
    await tester.tap(find.byKey(const Key('map-add')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('map-code-1')), '2109');
    await tester.tap(find.byKey(const Key('map-account-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('1200 · Bank').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('map-save')));
    await tester.pumpAndSettle();
    final sent = server.of('PUT', '/connection/mappings').single;
    final mappings = (sent.data as Map)['mappings'] as List;
    expect(mappings, hasLength(2));
    expect(mappings[1], {'nominalCode': '2109', 'externalAccount': 'SIM-1200'});
  });

  testWidgets('the owner disconnects after confirming', (tester) async {
    final server = await _pump(tester, owner: true);
    await tester.tap(find.byKey(const Key('accounting-disconnect')));
    await tester.pumpAndSettle();
    expect(find.text('Disconnect Simulated package?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('accounting-disconnect-confirm')));
    await tester.pumpAndSettle();
    expect(server.of('DELETE', '/accounting/connection'), hasLength(1));
    expect(find.byKey(const Key('accounting-none')), findsOneWidget);
  });
}
