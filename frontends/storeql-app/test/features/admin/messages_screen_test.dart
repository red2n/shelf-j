import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/messages_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The business's messages in its own words (13.x). The list says, per message,
// which forms go out in the platform's words and which in the business's, and in
// what languages. The editor starts from the words a message goes out in now,
// inserts a value where the cursor is, previews through the server — which is
// what judges a template — saves a version, and goes back to the platform's words
// only after asking.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final Map<String, ResponseBody Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];

  _Server(this.routes);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final route = routes['${o.method} ${o.path}'];
    return route == null ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no route"}}', 404) : route(o);
  }

  List<RequestOptions> sent(String method) => requests.where((r) => r.method == method).toList();
}

const _base = '/notification-svc/admin/notifications';

final _catalogue = jsonEncode({
  'data': [
    {
      'type': 'ORDER_CONFIRMED',
      'title': 'Order confirmed',
      'audience': 'CUSTOMER',
      'why': 'Sent to a shopper with an account when their order is confirmed.',
      'variables': [
        {'name': 'order', 'kind': 'TEXT', 'description': "The order's reference"},
        {'name': 'total', 'kind': 'MONEY', 'description': 'What the order came to'},
        {'name': 'shop', 'kind': 'TEXT', 'description': "The business's name"},
      ],
      'forms': [
        {
          'form': 'EMAIL',
          'subjectMax': 200,
          'bodyMax': 20000,
          'required': [
            ['order'],
          ],
          'written': [
            {'language': 'pl', 'version': 2, 'createdAt': '2026-09-21T10:00:00Z'},
          ],
        },
        {'form': 'PUSH', 'subjectMax': 65, 'bodyMax': 240, 'required': [], 'written': []},
      ],
    },
    {
      'type': 'STORE_TASK_MISSED',
      'title': 'Task not done',
      'audience': 'STAFF',
      'why': 'To a store when a task fell due and was not done.',
      'variables': [
        {'name': 'title', 'kind': 'TEXT', 'description': "The task's title"},
      ],
      'forms': [
        {'form': 'ALERT', 'subjectMax': 200, 'bodyMax': 2000, 'required': [], 'written': []},
      ],
    },
  ],
});

String _template(String source, int? version, String subject, String body) => jsonEncode({
      'data': {
        'type': 'ORDER_CONFIRMED',
        'form': 'EMAIL',
        'language': 'en',
        'source': source,
        'version': version,
        'subject': subject,
        'body': body,
        'history': [],
      },
    });

final _settings = jsonEncode({
  'data': {'defaultLanguage': 'en', 'signedAs': 'Hollins Grocers'},
});

Future<void> _pump(WidgetTester tester, _Server server) async {
  tester.view.physicalSize = const Size(1400, 2600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: const MaterialApp(home: Scaffold(body: MessagesScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

Map<String, ResponseBody Function(RequestOptions)> _routes({
  String source = 'DEFAULT',
  int? version,
}) =>
    {
      'GET $_base/templates': (_) => jsonResponse(_catalogue),
      'GET $_base/template-settings': (_) => jsonResponse(_settings),
      'GET $_base/templates/ORDER_CONFIRMED/EMAIL/en': (_) => jsonResponse(
            _template(source, version, 'Your order is confirmed', 'Thanks for your order!\n\nOrder {{order}}'),
          ),
      'GET $_base/templates/ORDER_CONFIRMED/EMAIL/pl': (_) => jsonResponse(
            _template('BUSINESS', 2, 'Zamówienie {{order}}', 'Dziękujemy! Razem: {{total}}'),
          ),
      'PUT $_base/templates/ORDER_CONFIRMED/EMAIL/en': (o) => jsonResponse(
            _template('BUSINESS', 1, (o.data as Map)['subject'] as String, (o.data as Map)['body'] as String),
          ),
      'POST $_base/templates/ORDER_CONFIRMED/EMAIL/en/preview': (_) => jsonResponse(jsonEncode({
            'data': {
              'subject': 'Your order is confirmed',
              'body': 'Thanks for your order!\n\nOrder 01a0c42a',
              'problems': [
                {'code': 'TEMPLATE_VARIABLE_UNKNOWN', 'message': 'This message has no password', 'names': ['password']},
              ],
            },
          })),
      'DELETE $_base/templates/ORDER_CONFIRMED/EMAIL/en': (_) => jsonResponse('{"data":"retired"}'),
      'PUT $_base/template-settings': (o) => jsonResponse(jsonEncode({
            'data': {
              'defaultLanguage': (o.data as Map)['defaultLanguage'],
              'signOff': (o.data as Map)['signOff'],
              'signedAs': (o.data as Map)['signOff'],
            },
          })),
    };

void main() {
  testWidgets('each message says which forms are the business\'s words, and in which languages', (tester) async {
    await _pump(tester, _Server(_routes()));
    expect(find.text('To customers'), findsOneWidget);
    expect(find.text('To staff'), findsOneWidget);
    expect(find.textContaining('Email: your words in Polish'), findsOneWidget);
    expect(find.textContaining("Push: platform's words"), findsOneWidget);
    expect(find.textContaining('signed "Hollins Grocers"'), findsOneWidget);
  });

  testWidgets('the editor starts from the platform\'s words, inserts a value at the cursor and previews',
      (tester) async {
    final server = _Server(_routes());
    await _pump(tester, server);
    await tester.tap(find.byKey(const Key('messages-open-ORDER_CONFIRMED')));
    await tester.pumpAndSettle();

    expect(find.text("The platform's words"), findsOneWidget);
    expect(find.text('Must say: order'), findsOneWidget);
    expect(find.byKey(const Key('template-retire')), findsNothing, reason: 'nothing of theirs to retire');

    final body = find.byKey(const Key('template-body'));
    await tester.tap(body);
    await tester.enterText(body, 'Total: ');
    await tester.tap(find.byKey(const Key('var-total')));
    await tester.pump();
    expect((tester.widget(body) as TextField).controller!.text, 'Total: {{total}}');

    await tester.tap(find.byKey(const Key('template-preview')));
    await tester.pumpAndSettle();
    expect(find.text('Thanks for your order!\n\nOrder 01a0c42a'), findsOneWidget);
    expect(find.text('This message has no password'), findsOneWidget);
    final sent = server.sent('POST').single;
    expect(sent.data, {'subject': 'Your order is confirmed', 'body': 'Total: {{total}}'});
  });

  testWidgets('saving writes the version and says so; another language opens its own words', (tester) async {
    final server = _Server(_routes());
    await _pump(tester, server);
    await tester.tap(find.byKey(const Key('messages-open-ORDER_CONFIRMED')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('template-subject')), 'Order {{order}} is in');
    await tester.enterText(find.byKey(const Key('template-body')), 'Thank you. {{order}}');
    await tester.tap(find.byKey(const Key('template-save')));
    await tester.pumpAndSettle();

    final put = server.sent('PUT').single;
    expect(put.path, '$_base/templates/ORDER_CONFIRMED/EMAIL/en');
    expect(put.data, {'subject': 'Order {{order}} is in', 'body': 'Thank you. {{order}}'});
    expect(find.text('Saved as version 1 in English.'), findsOneWidget);
    expect(find.text('Your words · version 1'), findsOneWidget);

    await tester.tap(find.byKey(const Key('template-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Polish · yours').last);
    await tester.pumpAndSettle();
    expect(server.requests.last.path, '$_base/templates/ORDER_CONFIRMED/EMAIL/pl');
    expect((tester.widget(find.byKey(const Key('template-subject'))) as TextField).controller!.text,
        'Zamówienie {{order}}');
  });

  testWidgets('going back to the platform\'s words asks first', (tester) async {
    final server = _Server(_routes(source: 'BUSINESS', version: 3));
    await _pump(tester, server);
    await tester.tap(find.byKey(const Key('messages-open-ORDER_CONFIRMED')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('template-retire')));
    await tester.pumpAndSettle();
    expect(server.sent('DELETE'), isEmpty, reason: 'nothing happens before the answer');
    await tester.tap(find.byKey(const Key('template-retire-confirm')));
    await tester.pumpAndSettle();
    expect(server.sent('DELETE').single.path, '$_base/templates/ORDER_CONFIRMED/EMAIL/en');
  });

  testWidgets('the business\'s language and sign-off are saved together', (tester) async {
    final server = _Server(_routes());
    await _pump(tester, server);
    await tester.tap(find.byKey(const Key('message-settings-edit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('message-settings-language')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Welsh').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('message-settings-sign-off')), 'Siop Hollins');
    await tester.tap(find.byKey(const Key('message-settings-save')));
    await tester.pumpAndSettle();
    expect(server.sent('PUT').single.data, {'defaultLanguage': 'cy', 'signOff': 'Siop Hollins'});
  });
}
