import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/pos_terminal.dart';

// ---------------------------------------------------------------------------
// Taking a card on an EMV terminal (07.16).
//
// The four outcomes are the point. They look similar in a response body and mean
// entirely different things about money:
//
//   APPROVED   money taken — record the tender.
//   DECLINED   nothing taken — ask for another tender.
//   CANCELLED  nothing taken — same.
//   TIMED_OUT  money MAY have been taken — record nothing, retry nothing, and
//              make sure a human looks at the terminal.
//
// A till that collapses the last one into "declined" loses a taking; one that
// collapses it into "approved" claims money it cannot prove it has. Both are the
// kind of error a customer notices on their statement, so each is asserted here.
// ---------------------------------------------------------------------------

class _Till implements HttpClientAdapter {
  final List<RequestOptions> calls = [];
  String body = '{"data":{"id":"a-1","state":"APPROVED","scheme":"VISA",'
      '"panLast4":"4242","authCode":"012345","receiptLine":"VISA DEBIT ****4242 (CHIP, PIN)"}}';
  int status = 201;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    calls.add(o);
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Dio _dio(_Till till) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = till;

Future<TerminalOutcome> _take(_Till till, {String key = 'sale-1-term0'}) =>
    takeCardOnTerminal(
      _dio(till),
      terminalId: 't-1',
      orderId: 'o-1',
      amount: 12.5,
      currency: 'GBP',
      idempotencyKey: key,
    );

void main() {
  group('what is sent to the terminal', () {
    test('an amount as money, a currency, and the key that stops a double charge',
        () async {
      final till = _Till();
      await _take(till);

      final sent = till.calls.single;
      expect(sent.path, '/payment-svc/payments/terminal');
      expect(sent.method, 'POST');
      final data = sent.data as Map<String, dynamic>;
      expect(data['terminalId'], 't-1');
      expect(data['orderId'], 'o-1');
      // Two decimal places as a string: the service refuses a third rather than
      // rounding somebody else's money, and a JSON number is a double by the time
      // a browser has parsed it.
      expect(data['amount'], '12.50');
      expect(data['currency'], 'GBP');
      // The key is the whole guard. Without it a second press is a second EMV
      // transaction on a real card.
      expect(sent.headers['Idempotency-Key'], 'sale-1-term0');
    });

    test('no field carries a card number, because the terminal reads the card',
        () async {
      final till = _Till();
      await _take(till);
      final data = (till.calls.single.data as Map<String, dynamic>).keys.toSet();
      expect(data, {'terminalId', 'orderId', 'amount', 'currency'});
    });
  });

  group('what the terminal said', () {
    test('an approval carries the receipt line the card needs', () async {
      final outcome = await _take(_Till());
      expect(outcome.approved, isTrue);
      expect(outcome.uncertain, isFalse);
      expect(outcome.receiptLine, 'VISA DEBIT ****4242 (CHIP, PIN)');
      expect(outcome.panLast4, '4242');
      expect(outcome.authCode, '012345');
      expect(outcome.message, 'Approved');
    });

    test('a decline shows the terminal\'s own words, not just "declined"',
        () async {
      // A decline with no reason sends a cashier to ring the bank.
      final till = _Till()
        ..body = '{"data":{"id":"a-2","state":"DECLINED",'
            '"outcomeDetail":"DECLINED — insufficient funds"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isFalse);
      expect(outcome.message, 'DECLINED — insufficient funds');
      expect(outcome.receiptLine, isNull, reason: 'nothing was taken');
    });

    test('a cancellation is not an approval', () async {
      final till = _Till()
        ..body = '{"data":{"id":"a-3","state":"CANCELLED",'
            '"outcomeDetail":"Cancelled at the terminal"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isFalse);
    });

    test('a timeout is neither approved nor merely declined', () async {
      // The one that matters most. The card may have been charged, so the till
      // must not record a taking and must not quietly try again.
      final till = _Till()
        ..body = '{"data":{"id":"a-4","state":"TIMED_OUT",'
            '"outcomeDetail":"No answer from the terminal"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isTrue);
      expect(outcome.message, contains('may have been charged'));
      expect(outcome.message, contains('check the terminal'));
    });

    test('an unrecognised state is treated as a failure, never as an approval',
        () async {
      // A new vendor, a typo, a truncated body: the safe reading of "I do not
      // know what this means" is that no money was taken.
      final till = _Till()..body = '{"data":{"id":"a-5","state":"SOMETHING_NEW"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.message, isNotEmpty);
    });

    test('a body with no state at all is a failure', () async {
      final till = _Till()..body = '{"data":{"id":"a-6"}}';
      final outcome = await _take(till);
      expect(outcome.state, 'FAILED');
      expect(outcome.approved, isFalse);
    });
  });

  group('the exception the till throws on a refused card', () {
    test('carries the outcome, so a timeout can be handled differently', () async {
      const declined =
          TerminalOutcome(id: 'a', state: 'DECLINED', detail: 'No funds');
      const timedOut = TerminalOutcome(id: 'b', state: 'TIMED_OUT');

      expect(const TerminalNotApproved(declined).outcome.uncertain, isFalse);
      expect(const TerminalNotApproved(timedOut).outcome.uncertain, isTrue);
      // The message reaches the cashier through toString, so it must be the
      // terminal's, not the class's name.
      expect(const TerminalNotApproved(declined).toString(), 'No funds');
    });
  });

  group('the devices a till offers', () {
    test('the simulator is marked, so nobody thinks a card was really taken', () {
      const sim = CardTerminalDevice(
          id: 't-1', label: 'Till 1', vendor: 'SIMULATED', storeId: 's-1');
      const real = CardTerminalDevice(
          id: 't-2', label: 'Till 2', vendor: 'VERIFONE', storeId: 's-1');
      expect(sim.simulated, isTrue);
      expect(real.simulated, isFalse);
    });
  });
}
