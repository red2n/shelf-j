import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/customer_providers.dart';
import 'package:shelf_app/features/admin/send_text_dialog.dart';

// A text to a customer (13.7): the dialog sends to the number on the record
// through the SMS channel, names the customer, marks marketing as marketing so
// the server can refuse it, stops an empty message, and shows a refusal in words.

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int status = 202;
  String refusal =
      '{"error":{"code":"MARKETING_CONSENT_MISSING","message":"this customer may not be sent marketing: no preference recorded"}}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final body = status == 202
        ? '{"data":{"eventId":"e-1","type":"CUSTOMER_SMS","status":"SENT","channel":"SMS"}}'
        : refusal;
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

const _customer = Customer(
    id: 'c-1', email: 'chris@example.com', phone: '+447700900123', firstName: 'Chris',
    lastName: 'Carter', status: 'ACTIVE');

Future<_Server> _pump(WidgetTester tester, {int status = 202}) async {
  final server = _Server()..status = status;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
                context: context, builder: (_) => const SendTextDialog(customer: _customer)),
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

void main() {
  testWidgets('sends to the number on the record, through the SMS channel, naming the customer',
      (tester) async {
    final server = await _pump(tester);
    expect(find.text('To +447700900123'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('send-text-body')), ' Your order is ready. ');
    await tester.tap(find.byKey(const Key('send-text-send')));
    await tester.pumpAndSettle();
    final post = server.requests.single;
    expect(post.path, endsWith('/notifications/send'));
    expect(post.data, {
      'channel': 'SMS',
      'recipient': '+447700900123',
      'subject': 'Message from the shop',
      'body': 'Your order is ready.',
      'type': 'CUSTOMER_SMS',
      'customerId': 'c-1',
      'category': 'TRANSACTIONAL',
    });
    expect(find.text('Text sent to +447700900123.'), findsOneWidget);
  });

  testWidgets('marketing is sent as marketing, and a refusal is shown in words', (tester) async {
    final server = await _pump(tester, status: 409);
    await tester.enterText(find.byKey(const Key('send-text-body')), '20% off this week');
    await tester.tap(find.byKey(const Key('send-text-marketing')));
    await tester.tap(find.byKey(const Key('send-text-send')));
    await tester.pumpAndSettle();
    expect((server.requests.single.data as Map)['category'], 'MARKETING');
    expect(find.textContaining('may not be sent marketing'), findsOneWidget);
    expect(find.text('Send'), findsOneWidget);
  });

  testWidgets('an empty message is stopped before it is sent', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('send-text-send')));
    await tester.pumpAndSettle();
    expect(find.text('Type the message first.'), findsOneWidget);
    expect(server.requests, isEmpty);
  });
}
