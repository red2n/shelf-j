import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ---------------------------------------------------------------------------
// Taking a card on an EMV terminal (07.16).
//
// A CARD tender used to be recorded because the cashier pressed "Card". The
// platform never asked a terminal whether the card was approved, so a declined
// card and an approved one looked identical in the books.
//
// Now the amount goes to a pinpad and the tender is recorded only if the card
// was actually approved. The outcome decides what the till does next, and the
// four outcomes are genuinely different:
//
//   APPROVED   the tender is recorded and the receipt carries the card line.
//   DECLINED   nothing was taken. The sale stays open for another tender.
//   CANCELLED  somebody pressed cancel. Same as declined for the till.
//   TIMED_OUT  the card MAY have been charged. Never retried automatically —
//              the cashier reads the terminal's own screen, and the attempt is
//              reconciled against the acquirer's settlement file.
//
// The card number never reaches this app. The terminal reads the card; the till
// sends an amount and receives a verdict with four digits for the receipt.
// ---------------------------------------------------------------------------

/// A terminal the cashier can send an amount to.
class CardTerminalDevice {
  final String id;
  final String label;
  final String vendor;
  final String storeId;

  const CardTerminalDevice({
    required this.id,
    required this.label,
    required this.vendor,
    required this.storeId,
  });

  /// True for the built-in simulator, so the till can say so rather than let a
  /// cashier believe a card was really taken.
  bool get simulated => vendor == 'SIMULATED';

  factory CardTerminalDevice.fromJson(Map<String, dynamic> j) =>
      CardTerminalDevice(
        id: j['id'] as String? ?? '',
        label: j['label'] as String? ?? '',
        vendor: j['vendor'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
      );
}

/// What the terminal said.
class TerminalOutcome {
  final String id;
  final String state;
  final String? detail;
  final String? receiptLine;
  final String? scheme;
  final String? panLast4;
  final String? authCode;

  const TerminalOutcome({
    required this.id,
    required this.state,
    this.detail,
    this.receiptLine,
    this.scheme,
    this.panLast4,
    this.authCode,
  });

  bool get approved => state == 'APPROVED';

  /// The card may have been charged. The one outcome that must never be retried
  /// and must never be recorded as a taking.
  bool get uncertain => state == 'TIMED_OUT';

  /// What the cashier is told. A decline without a reason sends them to ring the
  /// bank, so the terminal's own words are shown when it gave any.
  String get message => switch (state) {
    'APPROVED' => 'Approved',
    'DECLINED' => detail ?? 'Card declined',
    'CANCELLED' => detail ?? 'Cancelled at the terminal',
    'TIMED_OUT' =>
      'No answer from the terminal. The card may have been charged — check the '
          'terminal before taking payment again.',
    _ => detail ?? 'The terminal could not be reached',
  };

  factory TerminalOutcome.fromJson(Map<String, dynamic> j) => TerminalOutcome(
        id: j['id'] as String? ?? '',
        state: j['state'] as String? ?? 'FAILED',
        detail: j['outcomeDetail'] as String?,
        receiptLine: j['receiptLine'] as String?,
        scheme: j['scheme'] as String?,
        panLast4: j['panLast4'] as String?,
        authCode: j['authCode'] as String?,
      );
}

/// The active terminals at a store, so the till offers only devices that exist.
///
/// Empty is a normal answer: a shop with no pinpad goes on recording a CARD
/// tender the way it always did. Offering a terminal it has not got would be
/// worse than offering none.
final posTerminalsProvider =
    FutureProvider.family.autoDispose<List<CardTerminalDevice>, String>(
        (ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.payment}/admin/payments/terminals');
  final rows = (resp.data['data'] as List?) ?? const [];
  return [
    for (final e in rows)
      if (e is Map<String, dynamic> &&
          e['status'] == 'ACTIVE' &&
          e['storeId'] == storeId)
        CardTerminalDevice.fromJson(e),
  ];
});

/// Sends an amount to a terminal and returns what it said.
///
/// [idempotencyKey] is not optional in practice and the caller must derive it
/// from the sale rather than generate a fresh one per press: the whole point is
/// that a second press with the same key finds the first attempt instead of
/// starting a second EMV transaction on a real card.
Future<TerminalOutcome> takeCardOnTerminal(
  Dio dio, {
  required String terminalId,
  required String orderId,
  required double amount,
  required String currency,
  required String idempotencyKey,
}) async {
  final resp = await dio.post(
    '/${ApiConstants.payment}/payments/terminal',
    data: {
      'terminalId': terminalId,
      'orderId': orderId,
      // Two places, as money: the service refuses a third rather than rounding
      // somebody else's money.
      'amount': amount.toStringAsFixed(2),
      'currency': currency,
    },
    options: Options(headers: {'Idempotency-Key': idempotencyKey}),
  );
  return TerminalOutcome.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// Thrown when a card was not approved, so the caller stops the settle and
/// leaves the sale open for another tender.
///
/// Carries the outcome rather than a string, because the till has to treat a
/// timeout differently from a decline: one means "ask for another card", the
/// other means "do not touch this until somebody has read the terminal".
class TerminalNotApproved implements Exception {
  final TerminalOutcome outcome;
  const TerminalNotApproved(this.outcome);

  @override
  String toString() => outcome.message;
}
