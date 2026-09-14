import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shelf_app/core/format.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';
import 'package:shelf_app/features/platform/security_incidents_screen.dart';

// ---------------------------------------------------------------------------
// The platform's security incident register (21.15): the register with its
// deadlines, an incident's stages and timeline, recording and telling
// businesses, and every refusal shown where it happened.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Platform implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  final replies = <String, (int, String)>{};

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final r = replies['${o.method} ${o.path}'] ??
        (404, '{"error":{"code":"NOT_FOUND","message":"no reply for ${o.method} ${o.path}"}}');
    return ResponseBody.fromString(r.$2, r.$1, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<RequestOptions> posts() => requests.where((r) => r.method == 'POST').toList();
}

const _base = '/tenant-svc/platform/security-incidents';

const _tenants = [
  PlatformTenant(id: 't1', name: 'Corner Shop', status: 'ACTIVE', country: 'GB', currency: 'GBP', createdAt: '2026-01-01T00:00:00Z'),
  PlatformTenant(id: 't2', name: 'Farm Shop', status: 'ACTIVE', country: 'GB', currency: 'GBP', createdAt: '2026-01-01T00:00:00Z'),
];

Future<_Platform> _pump(WidgetTester tester, void Function(_Platform) setUp,
    {bool tenantsFail = false}) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final api = _Platform();
  setUp(api);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = api;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      allTenantsProvider.overrideWith((ref) async {
        if (tenantsFail) throw Exception('tenant-svc down');
        return _tenants;
      }),
    ],
    child: const MaterialApp(home: Scaffold(body: SecurityIncidentsScreen())),
  ));
  await tester.pumpAndSettle();
  return api;
}

const _summaries = '{"data":['
    '{"id":"i1","kind":"SEVERE_INCIDENT","title":"Token leak","awareAt":"2026-09-13T02:00:00Z","status":"OPEN",'
    '"nextStage":"EARLY_WARNING","nextDueAt":"2026-09-14T02:00:00Z","overdue":true},'
    '{"id":"i3","kind":"EXPLOITED_VULNERABILITY","title":"Proxy exploit","awareAt":"2026-09-14T06:00:00Z","status":"OPEN",'
    '"nextStage":"NOTIFICATION","nextDueAt":"2026-09-17T06:00:00Z","overdue":false}]}';

const _cra = 'Regulation (EU) 2024/2847 art.14';

String _stage(String stage, String state, {String? dueAt, String? doneAt}) =>
    '{"stage":"$stage","summary":"$stage summary","citation":"$_cra",'
    '"dueAt":${dueAt == null ? 'null' : '"$dueAt"'},"doneAt":${doneAt == null ? 'null' : '"$doneAt"'},"state":"$state"}';

final _vulnerability = '{"data":{"id":"i3","kind":"EXPLOITED_VULNERABILITY","title":"Proxy exploit",'
    '"summary":"Session tokens logged by a proxy","awareAt":"2026-09-14T06:00:00Z","openedAt":"2026-09-14T06:05:00Z",'
    '"affectsAllTenants":false,"tenantIds":["t1"],"status":"OPEN","stages":['
    '${_stage('EARLY_WARNING', 'DONE', dueAt: '2026-09-15T06:00:00Z', doneAt: '2026-09-14T07:00:00Z')},'
    '${_stage('NOTIFICATION', 'DUE', dueAt: '2026-09-17T06:00:00Z')},'
    '${_stage('FINAL_REPORT', 'WAITING')},'
    '${_stage('TENANT_NOTICE', 'NO_DEADLINE')}],'
    '"events":[{"id":"e1","kind":"EARLY_WARNING_SENT","occurredAt":"2026-09-14T07:00:00Z","recordedAt":"2026-09-14T07:01:00Z","reference":"SRP-1","note":null}],'
    '"noticesIssued":1,"noticesAcknowledged":0}}';

String _severe({String status = 'OPEN'}) => '{"data":{"id":"i1","kind":"SEVERE_INCIDENT","title":"Token leak",'
    '"summary":"Tokens leaked","awareAt":"2026-09-13T02:00:00Z","openedAt":"2026-09-13T02:10:00Z",'
    '"affectsAllTenants":true,"tenantIds":[],"status":"$status","stages":['
    '${_stage('EARLY_WARNING', 'OVERDUE', dueAt: '2026-09-14T02:00:00Z')},'
    '${_stage('NOTIFICATION', 'DUE', dueAt: '2026-09-16T02:00:00Z')},'
    '${_stage('FINAL_REPORT', 'WAITING')},'
    '${_stage('TENANT_NOTICE', 'NO_DEADLINE')}],'
    '"events":[],"noticesIssued":0,"noticesAcknowledged":0}}';

Future<void> _openIncident(WidgetTester tester, String id) async {
  await tester.tap(find.byKey(Key('incident-$id')));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('the register lists open incidents, flags the overdue one and names each next deadline',
      (tester) async {
    final api = await _pump(tester, (a) => a.replies['GET $_base'] = (200, _summaries));

    expect(api.requests.single.queryParameters, {'status': 'OPEN'});
    expect(find.text('Token leak'), findsOneWidget);
    expect(find.text('Overdue'), findsOneWidget);
    expect(find.textContaining('next: Early warning by ${AppFormat.dateTime('2026-09-14T02:00:00Z')}'), findsOneWidget);
    expect(find.textContaining('Actively exploited vulnerability'), findsOneWidget);
    expect(find.text('Open'), findsWidgets);
  });

  testWidgets('switching to Closed asks for closed incidents, and none says so', (tester) async {
    final api = await _pump(tester, (a) => a.replies['GET $_base'] = (200, _summaries));
    api.replies['GET $_base'] = (200, '{"data":[]}');

    await tester.tap(find.text('Closed'));
    await tester.pumpAndSettle();

    expect(api.requests.last.queryParameters, {'status': 'CLOSED'});
    expect(find.text('No incidents on the register.'), findsOneWidget);
  });

  testWidgets("an incident shows what the law asks, each stage's state, its timeline and notices",
      (tester) async {
    await _pump(tester, (a) {
      a.replies['GET $_base'] = (200, _summaries);
      a.replies['GET $_base/i3'] = (200, _vulnerability);
    });
    await _openIncident(tester, 'i3');

    expect(find.text('Proxy exploit'), findsOneWidget);
    expect(find.textContaining('1 business · open'), findsOneWidget);
    expect(find.text('Done ${AppFormat.dateTime('2026-09-14T07:00:00Z')}'), findsOneWidget);
    expect(find.text('Due ${AppFormat.dateTime('2026-09-17T06:00:00Z')}'), findsOneWidget);
    expect(find.text('Waiting'), findsOneWidget);
    expect(find.text('No fixed time'), findsOneWidget);
    expect(find.textContaining(_cra), findsNWidgets(4));
    expect(find.text('Early warning sent'), findsOneWidget);
    expect(find.textContaining('ref SRP-1'), findsOneWidget);
    expect(find.text('Notices: 1 issued, 0 acknowledged'), findsOneWidget);

    // Record offers what is left, not what is done or done by other means.
    await tester.tap(find.byKey(const Key('record-event')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('record-kind')));
    await tester.pumpAndSettle();
    expect(find.text('Corrective measure available'), findsOneWidget);
    expect(find.text('Final report sent'), findsOneWidget);
    expect(find.text('Early warning sent'), findsOneWidget, reason: 'only the timeline entry, not an option');
    expect(find.text('Businesses told'), findsNothing);
  });

  testWidgets('a severe incident has no measure to record, and a refusal stays in the dialog',
      (tester) async {
    final api = await _pump(tester, (a) {
      a.replies['GET $_base'] = (200, _summaries);
      a.replies['GET $_base/i1'] = (200, _severe());
      a.replies['POST $_base/i1/events'] = (409,
          '{"error":{"code":"INCIDENT_STAGE_ALREADY_RECORDED","message":"EARLY_WARNING_SENT is already recorded for this incident"}}');
    });
    await _openIncident(tester, 'i1');
    expect(find.text('Overdue since ${AppFormat.dateTime('2026-09-14T02:00:00Z')}'), findsOneWidget);
    expect(find.textContaining('every business · open'), findsOneWidget);

    await tester.tap(find.byKey(const Key('record-event')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('record-kind')));
    await tester.pumpAndSettle();
    expect(find.text('Corrective measure available'), findsNothing);
    await tester.tap(find.text('Early warning sent').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('record-reference')), 'SRP-9');
    await tester.tap(find.byKey(const Key('record-submit')));
    await tester.pumpAndSettle();

    final post = api.posts().single;
    expect(post.path, '$_base/i1/events');
    expect(post.data, {'kind': 'EARLY_WARNING_SENT', 'reference': 'SRP-9'});
    expect(find.byKey(const Key('dialog-error')), findsOneWidget);
    expect(find.text('EARLY_WARNING_SENT is already recorded for this incident'), findsOneWidget);
    expect(find.byKey(const Key('record-submit')), findsOneWidget, reason: 'the dialog stays open');
  });

  testWidgets('a recorded report closes the dialog and reloads the incident', (tester) async {
    final api = await _pump(tester, (a) {
      a.replies['GET $_base'] = (200, _summaries);
      a.replies['GET $_base/i3'] = (200, _vulnerability);
      a.replies['POST $_base/i3/events'] = (201, _vulnerability);
    });
    await _openIncident(tester, 'i3');
    await tester.tap(find.byKey(const Key('record-event')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('record-reference')), 'SRP-2');
    await tester.tap(find.byKey(const Key('record-submit')));
    await tester.pumpAndSettle();

    expect(api.posts().single.data, {'kind': 'NOTIFICATION_SENT', 'reference': 'SRP-2'});
    expect(find.byKey(const Key('record-submit')), findsNothing);
    expect(find.text('Recorded.'), findsOneWidget);
    expect(api.requests.where((r) => r.method == 'GET' && r.path == '$_base/i3'), hasLength(2));
  });

  testWidgets('opening an incident sends what was entered, for the businesses chosen', (tester) async {
    final api = await _pump(tester, (a) {
      a.replies['GET $_base'] = (200, '{"data":[]}');
      a.replies['POST $_base'] = (201, _vulnerability);
      a.replies['GET $_base/i3'] = (200, _vulnerability);
    });
    expect(find.text('No open incidents.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('open-incident')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('incident-title')), 'Proxy exploit');
    await tester.enterText(find.byKey(const Key('incident-summary')), 'Session tokens logged by a proxy');
    await tester.enterText(find.byKey(const Key('incident-aware-at')), '2026-09-14T06:00:00Z');
    await tester.tap(find.byKey(const Key('tenant-t1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('incident-submit')));
    await tester.pumpAndSettle();

    expect(api.posts().single.data, {
      'kind': 'EXPLOITED_VULNERABILITY',
      'title': 'Proxy exploit',
      'summary': 'Session tokens logged by a proxy',
      'awareAt': '2026-09-14T06:00:00Z',
      'tenantIds': ['t1'],
    });
    expect(find.text('What the law asks'), findsOneWidget, reason: 'the new incident opens');
  });

  testWidgets('a blank title or a time that is not one is refused before anything is sent', (tester) async {
    final api = await _pump(tester, (a) => a.replies['GET $_base'] = (200, '{"data":[]}'));
    await tester.tap(find.byKey(const Key('open-incident')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('incident-summary')), 'Something');
    await tester.enterText(find.byKey(const Key('incident-aware-at')), 'yesterday');
    await tester.tap(find.byKey(const Key('incident-submit')));
    await tester.pumpAndSettle();

    expect(find.text('A title is required'), findsOneWidget);
    expect(find.text('A date and time such as 2026-09-14T08:00:00Z'), findsOneWidget);
    expect(api.posts(), isEmpty);
  });

  testWidgets('no incident is opened while the businesses cannot be loaded', (tester) async {
    final api = await _pump(tester, (a) => a.replies['GET $_base'] = (200, '{"data":[]}'), tenantsFail: true);
    await tester.tap(find.byKey(const Key('open-incident')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('incident-title')), 'Proxy exploit');
    await tester.enterText(find.byKey(const Key('incident-summary')), 'Session tokens');

    expect(find.byKey(const Key('tenants-unavailable')), findsOneWidget);
    final submit = tester.widget<FilledButton>(find.byKey(const Key('incident-submit')));
    expect(submit.onPressed, isNull, reason: 'a failed read must not widen an incident to every business');
    expect(api.posts(), isEmpty);
  });

  testWidgets('telling businesses needs a message, sends it, and says how many were told', (tester) async {
    final api = await _pump(tester, (a) {
      a.replies['GET $_base'] = (200, _summaries);
      a.replies['GET $_base/i3'] = (200, _vulnerability);
      a.replies['POST $_base/i3/notices'] = (200, '{"data":{"issued":1,"total":1,"acknowledged":0}}');
    });
    await _openIncident(tester, 'i3');
    await tester.tap(find.byKey(const Key('tell-businesses')));
    await tester.pumpAndSettle();

    expect(find.textContaining('Each of the 1 businesses affected'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byKey(const Key('tell-submit'))).onPressed, isNull);
    await tester.enterText(find.byKey(const Key('tell-message')), 'Rotate staff passwords.');
    await tester.pump();
    await tester.tap(find.byKey(const Key('tell-submit')));
    await tester.pumpAndSettle();

    final post = api.posts().single;
    expect(post.path, '$_base/i3/notices');
    expect(post.data, {'message': 'Rotate staff passwords.'});
    expect(find.text('1 new notices; 1 in all, 0 acknowledged.'), findsOneWidget);
  });

  testWidgets('a closed incident offers nothing to record or send', (tester) async {
    await _pump(tester, (a) {
      a.replies['GET $_base'] = (200, _summaries);
      a.replies['GET $_base/i1'] = (200, _severe(status: 'CLOSED'));
    });
    await _openIncident(tester, 'i1');

    expect(find.textContaining('· closed'), findsOneWidget);
    expect(find.byKey(const Key('record-event')), findsNothing);
    expect(find.byKey(const Key('tell-businesses')), findsNothing);
  });

  testWidgets('a failed read says so, with a way to try again', (tester) async {
    await _pump(tester, (a) => a.replies['GET $_base'] = (403, '{"error":{"code":"FORBIDDEN","message":"Insufficient role for this operation"}}'));
    expect(find.byType(ListTile), findsNothing);
    expect(find.textContaining('Retry', findRichText: true), findsWidgets);
  });
}
