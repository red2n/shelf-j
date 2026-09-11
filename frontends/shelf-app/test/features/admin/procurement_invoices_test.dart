import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/network/api_client.dart';
import 'package:shelf_app/features/admin/procurement_screen.dart';

// ---------------------------------------------------------------------------
// The three-way match on screen: ordered against received against invoiced.
//
// The server answers with variance CODES — INVOICED_ABOVE_RECEIVED and friends.
// A buyer about to ring a supplier needs the sentence, not the constant, and
// they need to know WHICH line disagreed. A status badge alone answers neither,
// which is why these tests assert the words and the figures rather than that a
// widget rendered.
//
// This is also the first widget test procurement_screen has ever had — it was
// built across three commits with none, which is called out in the artifact
// rather than left quiet.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _StubAdapter implements HttpClientAdapter {
  String body = '{"data":[]}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

String _invoice({
  required String number,
  required String status,
  required String variances,
  double qtyOrdered = 100,
  double qtyReceived = 60,
  double qtyInvoiced = 60,
  double orderedPrice = 2.50,
  double invoicedPrice = 2.50,
}) =>
    '''
{"id":"i-$number","poId":"po-1111111111","invoiceNumber":"$number",
 "invoiceDate":"2026-02-01","currency":"GBP","netAmount":150,"vatAmount":30,
 "grossAmount":180,"status":"$status","lines":[
   {"variantId":"v-abcdef123456","qtyOrdered":$qtyOrdered,"qtyReceived":$qtyReceived,
    "qtyInvoicedBefore":0,"qtyInvoiced":$qtyInvoiced,
    "orderedUnitPrice":$orderedPrice,"invoicedUnitPrice":$invoicedPrice,
    "variances":[$variances]}]}''';

Future<void> _pump(WidgetTester tester, String body) async {
  final adapter = _StubAdapter()..body = body;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Invoices'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a clean match shows no variance and does not shout', (tester) async {
    await _pump(tester, '{"data":[${_invoice(number: "INV-1", status: "MATCHED", variances: "")}]}');

    expect(find.text('INV-1'), findsOneWidget);
    expect(find.text('MATCHED'), findsOneWidget);
    expect(find.textContaining('Billed for more than arrived'), findsNothing);
  });

  testWidgets('a variance is shown in words, not as the server\'s constant',
      (tester) async {
    await _pump(
        tester,
        '{"data":[${_invoice(number: "INV-2", status: "FLAGGED", variances: '"INVOICED_ABOVE_RECEIVED"', qtyInvoiced: 100)}]}');

    // The sentence a buyer can act on…
    expect(find.text('Billed for more than arrived'), findsOneWidget);
    // …not the constant the API speaks in.
    expect(find.text('INVOICED_ABOVE_RECEIVED'), findsNothing);
  });

  testWidgets('all three documents\' figures are on the row, not just a badge',
      (tester) async {
    await _pump(
        tester,
        '{"data":[${_invoice(number: "INV-3", status: "FLAGGED", variances: '"INVOICED_ABOVE_RECEIVED"', qtyInvoiced: 100)}]}');

    expect(find.text('Ordered'), findsOneWidget);
    expect(find.text('Received'), findsOneWidget);
    expect(find.text('Invoiced'), findsOneWidget);
    // 100 ordered, 60 received, 100 invoiced — the three numbers that make the
    // variance obvious without reading the chip.
    expect(find.text('60'), findsOneWidget);
    expect(find.text('100'), findsNWidgets(2));
  });

  testWidgets('a price variance shows both prices, so the gap is visible',
      (tester) async {
    await _pump(
        tester,
        '{"data":[${_invoice(number: "INV-4", status: "FLAGGED", variances: '"PRICE_ABOVE_ORDER"', invoicedPrice: 2.75)}]}');

    expect(find.text('2.5 → 2.75'), findsOneWidget);
    expect(find.text('Charged above the agreed price'), findsOneWidget);
  });

  testWidgets('flagged invoices sort ahead of matched ones', (tester) async {
    await _pump(
        tester,
        '{"data":['
        '${_invoice(number: "INV-OK", status: "MATCHED", variances: "")},'
        '${_invoice(number: "INV-BAD", status: "FLAGGED", variances: '"NOT_RECEIVED"', qtyReceived: 0)}'
        ']}');

    // The exceptions are the entire point of the control; a list in date order
    // buries them behind the invoices nobody needs to read.
    final bad = tester.getTopLeft(find.text('INV-BAD')).dy;
    final ok = tester.getTopLeft(find.text('INV-OK')).dy;
    expect(bad, lessThan(ok));
  });

  testWidgets('an empty list says what to do next rather than just being blank',
      (tester) async {
    await _pump(tester, '{"data":[]}');
    expect(find.text('No supplier invoices yet'), findsOneWidget);
    expect(find.textContaining('Capture one from a purchase order'), findsOneWidget);
  });
}
