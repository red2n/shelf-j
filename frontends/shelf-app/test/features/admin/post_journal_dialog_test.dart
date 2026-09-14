import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/post_journal_dialog.dart';

// ---------------------------------------------------------------------------
// The manual journal (17.1). The server refuses a journal that does not
// balance; the dialog refuses first, with the running difference on screen,
// so a finance user typing twenty lines does not learn on the twenty-first
// that the third was wrong.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int status = 201;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final body = status == 201
        ? '{"data":{"journalId":"j-1","totalDebit":100,"totalCredit":100,"lines":[]}}'
        : '{"error":{"code":"PURCHASE_PERIOD_CLOSED","message":"the accounting period covering 2026-06-15 is closed for this store"}}';
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester, {int status = 201}) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..status = status;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<bool>(
                context: context, builder: (_) => const PostJournalDialog()),
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

FilledButton _post(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const Key('journal-post')));

Future<void> _type(WidgetTester tester, String key, String text) async {
  await tester.enterText(find.byKey(Key(key)), text);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the running difference is on screen and blocks the post until it is zero',
      (tester) async {
    final server = await _pump(tester);
    expect(_post(tester).onPressed, isNull);
    await _type(tester, 'journal-description', 'Opening stock');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '500');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '499.99');
    expect(find.textContaining('difference 0.01'), findsOneWidget);
    expect(find.text('Debits and credits differ by 0.01.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await _type(tester, 'journal-credit-1', '500');
    expect(find.textContaining('difference 0.00'), findsOneWidget);
    expect(_post(tester).onPressed, isNotNull);
    expect(server.requests, isEmpty);
  });

  testWidgets('a balanced journal is sent as the server expects it', (tester) async {
    final server = await _pump(tester);
    await _type(tester, 'journal-date', '2026-02-05');
    await _type(tester, 'journal-description', '  Opening stock  ');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-name-0', 'Stock');
    await _type(tester, 'journal-debit-0', '500');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '500');
    await tester.tap(find.byKey(const Key('journal-post')));
    await tester.pumpAndSettle();

    final sent = server.requests.single;
    expect(sent.method, 'POST');
    expect(sent.path, contains('/purchase-svc/nominal-ledger/journals'));
    expect(sent.data['entryDate'], '2026-02-05');
    expect(sent.data['description'], 'Opening stock');
    final lines = sent.data['lines'] as List;
    expect(lines.length, 2);
    expect(lines[0], {'nominalCode': '1001', 'nominalName': 'Stock', 'debit': '500'});
    expect(lines[1], {'nominalCode': '3000', 'credit': '500'});
    expect(find.text('Post a journal'), findsNothing);
    expect(find.text('Journal posted.'), findsOneWidget);
  });

  testWidgets('a line that is both a debit and a credit, or a bad code, is stopped here',
      (tester) async {
    await _pump(tester);
    await _type(tester, 'journal-description', 'x');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '10');
    await _type(tester, 'journal-credit-0', '10');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '10');
    expect(find.text('A line is a debit or a credit, not both.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await _type(tester, 'journal-credit-0', '');
    await _type(tester, 'journal-code-0', '10 01');
    expect(find.textContaining('nominal code of 1–10 letters or digits'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);

    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-date', '5 Feb');
    expect(find.text('Date as yyyy-MM-dd.'), findsOneWidget);
    expect(_post(tester).onPressed, isNull);
  });

  testWidgets('lines are added and removed, never below two', (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('journal-code-1')), findsOneWidget);
    expect(find.byKey(const Key('journal-code-2')), findsNothing);
    final remove = tester.widget<IconButton>(find.byKey(const Key('journal-remove-0')));
    expect(remove.onPressed, isNull);

    await tester.tap(find.byKey(const Key('journal-add-line')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('journal-code-2')), findsOneWidget);
    await tester.tap(find.byKey(const Key('journal-remove-2')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('journal-code-2')), findsNothing);
  });

  testWidgets('a refusal from the server is shown in words and the journal stays open',
      (tester) async {
    final server = await _pump(tester, status: 409);
    await _type(tester, 'journal-date', '2026-06-15');
    await _type(tester, 'journal-description', 'Late');
    await _type(tester, 'journal-code-0', '1001');
    await _type(tester, 'journal-debit-0', '1');
    await _type(tester, 'journal-code-1', '3000');
    await _type(tester, 'journal-credit-1', '1');
    await tester.tap(find.byKey(const Key('journal-post')));
    await tester.pumpAndSettle();
    expect(server.requests.length, 1);
    expect(find.text('Post a journal'), findsOneWidget);
    expect(find.textContaining('is closed for this store'), findsOneWidget);
    expect(_post(tester).onPressed, isNotNull);
  });
}
