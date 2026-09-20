import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/guardian_consent_section.dart';

// ---------------------------------------------------------------------------
// A child's guardian (13.12): what staff see on a customer under 18, and how
// the parent's consent is recorded with the way the parent was verified.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  String privacy;
  int postStatus = 200;
  _Server(this.privacy);
  @override
  void close({bool force = false}) {}
  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') {
      return ResponseBody.fromString(
          postStatus >= 400
              ? '{"error":{"code":"PRIVACY_REFERENCE_IS_A_NUMBER","message":"a reference notes what was seen, never a document\'s number"}}'
              : '{"data":{"guardianName":"R. Kumar","verification":"DOCUMENT_SEEN","givenAt":"2026-09-16T10:00:00Z","standing":true}}',
          postStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    return ResponseBody.fromString(privacy, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

String _privacy({bool child = true, bool guardian = false}) =>
    '{"data":{"child":$child,"canTrack":${!child || guardian},"guardian":${guardian ? '{"guardianName":"R. Kumar","verification":"DOCUMENT_SEEN","givenAt":"2026-09-16T10:00:00Z","standing":true}' : 'null'},'
    '"consents":[{"purpose":"LOYALTY","granted":true},{"purpose":"MARKETING","granted":false}]}}';

Future<_Server> _pump(WidgetTester tester, String privacy) async {
  final server = _Server(privacy);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(
        home: Scaffold(body: GuardianConsentSection(customerId: 'c-1'))),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('an adult shows only their consents', (tester) async {
    await _pump(tester, _privacy(child: false));
    expect(find.textContaining('loyalty on'), findsOneWidget);
    expect(find.byKey(const Key('guardian-record')), findsNothing);
  });

  testWidgets('a child without a guardian says so and offers to record one; the record names how the parent was verified',
      (tester) async {
    final server = await _pump(tester, _privacy());
    expect(find.textContaining('Under 18. Offers, personalisation'), findsOneWidget);
    await tester.tap(find.byKey(const Key('guardian-record')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('guardian-name')), 'R. Kumar');
    await tester.enterText(find.byKey(const Key('guardian-reference')), 'passport seen');
    await tester.tap(find.byKey(const Key('guardian-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST');
    expect(post.path, '/customer-svc/customers/c-1/privacy/guardian');
    expect((post.data as Map)['guardianName'], 'R. Kumar');
    expect((post.data as Map)['verification'], 'DOCUMENT_SEEN');
    expect((post.data as Map)['reference'], 'passport seen');
  });

  testWidgets('a refused reference is shown in the server\'s words and the dialog stays',
      (tester) async {
    final server = await _pump(tester, _privacy());
    server.postStatus = 400;
    await tester.tap(find.byKey(const Key('guardian-record')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('guardian-name')), 'R. Kumar');
    await tester.enterText(find.byKey(const Key('guardian-reference')), 'passport 12345678');
    await tester.tap(find.byKey(const Key('guardian-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining("never a document's number"), findsOneWidget);
    expect(find.byKey(const Key('guardian-save')), findsOneWidget);
  });

  testWidgets('a standing guardian is named and can be withdrawn with one DELETE', (tester) async {
    final server = await _pump(tester, _privacy(guardian: true));
    expect(find.textContaining('R. Kumar consented'), findsOneWidget);
    await tester.tap(find.byKey(const Key('guardian-withdraw')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.method == 'DELETE').single.path,
        '/customer-svc/customers/c-1/privacy/guardian');
  });
}
