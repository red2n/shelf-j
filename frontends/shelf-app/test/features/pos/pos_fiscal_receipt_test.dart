import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/pos/pos_fiscal_receipt.dart';
import 'package:shelf_app/features/pos/pos_receipt_data.dart';

// ---------------------------------------------------------------------------
// The legal receipt number on the till's receipt.
//
// order-svc issues the number when the payment that completes a sale reaches
// it, a few seconds after the till posts the last tender. The till waits a
// bounded time for it. And a receipt that has no number says so: this receipt
// used to print "Receipt #" over the first eight characters of the order's
// UUID, which is an order reference wearing a receipt number's label.
// ---------------------------------------------------------------------------

class _Replies implements HttpClientAdapter {
  final List<(int, String)> replies;
  final List<RequestOptions> calls = [];
  _Replies(this.replies);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    calls.add(o);
    final r = replies[calls.length - 1 < replies.length ? calls.length - 1 : replies.length - 1];
    return ResponseBody.fromString(r.$2, r.$1, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _notYet = (404, '{"error":{"code":"ORDER_RECEIPT_NOT_ISSUED","details":[]}}');
const _issued = (200, '{"data":{"fullNumber":"2026-000042","number":42}}');

Dio _dio(_Replies r) => Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = r;

PosReceiptData _receipt({String? number, String? note}) => PosReceiptData(
      orderId: '9f3c2a1b-0000-0000-0000-000000000000',
      storeName: 'High Street',
      dateTime: DateTime(2026, 9, 10, 11, 30),
      items: const [],
      subtotal: 12,
      discount: 0,
      total: 12,
      currency: 'GBP',
      tenders: const [],
      change: 0,
      fiscalNumber: number,
      fiscalNumberNote: note,
    );

void main() {
  group('awaitFiscalNumber', () {
    test('asks the till-readable path, not the management-only one', () async {
      final r = _Replies([_issued]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1'), '2026-000042');
      expect(r.calls.single.path, '/order-svc/orders/o-1/fiscal-receipt');
    });

    test('waits through "not issued yet" for a number that is on its way', () async {
      final r = _Replies([_notYet, _notYet, _issued]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1', interval: Duration.zero),
          '2026-000042');
      expect(r.calls, hasLength(3));
    });

    test('gives up after a bounded wait — the customer is standing there', () async {
      final r = _Replies([_notYet]);
      expect(
          await awaitFiscalNumber(_dio(r), 'o-1',
              attempts: 4, interval: Duration.zero),
          isNull);
      expect(r.calls, hasLength(4));
    });

    test('stops at once on an answer waiting cannot change', () async {
      final r = _Replies([(503, '{"error":{"code":"UPSTREAM_UNAVAILABLE","details":[]}}')]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1', interval: Duration.zero),
          isNull);
      expect(r.calls, hasLength(1));
    });
  });

  group('the printed receipt', () {
    test('prints the legal number as the receipt number, with the order beside it', () {
      final html = _receipt(number: '2026-000042').toHtml();
      expect(html, contains('Receipt no.:'));
      expect(html, contains('2026-000042'));
      expect(html, contains('Order ref:'));
      expect(html, contains('<title>Receipt 2026-000042</title>'));
      expect(html, isNot(contains('Receipt #')));
    });

    test('with no number yet, it does not pass the order id off as one', () {
      final html =
          _receipt(note: 'Receipt number not issued yet. Reprint once it is.').toHtml();
      expect(html, isNot(contains('Receipt no.:')));
      expect(html, isNot(contains('Receipt #')));
      expect(html, contains('Order ref:'));
      expect(html, contains('Receipt number not issued yet'));
    });

    test('a late number can be added for the reprint', () {
      final late = _receipt(note: 'not yet').withFiscalNumber('2026-000043');
      expect(late.fiscalNumber, '2026-000043');
      expect(late.toHtml(), contains('Receipt no.:'));
      expect(late.toHtml(), isNot(contains('not yet')));
    });
  });
}
