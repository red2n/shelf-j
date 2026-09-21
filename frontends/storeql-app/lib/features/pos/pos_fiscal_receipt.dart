import 'package:dio/dio.dart';

import '../../core/constants.dart';

/// What the store's fiscal regime stamped on a sale (18.5), as the till must
/// print it: the German security module's fields (KassenSichV §6) or the
/// Portuguese signature excerpt and certificate number. Null fields mean the
/// regime has no such stamp.
class FiscalStamp {
  final String fullNumber;
  final String regime;

  // Germany — KassenSichV §6 Nr. 6–8 and the QR of DSFinV-K Anlage I.
  final String? tseSerial;
  final int? tseTransactionNumber;
  final int? tseSignatureCounter;
  final String? tseSignature;
  final String? tseStartedAt;
  final String? tseFinishedAt;
  final String? tseQr;

  /// Set when the module could not sign: the sale went ahead, and the receipt
  /// says so, which is what the law asks of an outage.
  final String? tseError;

  // Portugal — the four characters and the certificate number every document prints.
  final String? ptExcerpt;
  final String? ptCertificateNumber;
  final String? ptAtcud;

  const FiscalStamp({
    required this.fullNumber,
    this.regime = 'NONE',
    this.tseSerial,
    this.tseTransactionNumber,
    this.tseSignatureCounter,
    this.tseSignature,
    this.tseStartedAt,
    this.tseFinishedAt,
    this.tseQr,
    this.tseError,
    this.ptExcerpt,
    this.ptCertificateNumber,
    this.ptAtcud,
  });

  bool get hasTse => tseSignature != null || tseError != null;
  bool get hasPt => ptExcerpt != null;

  factory FiscalStamp.fromJson(Map<String, dynamic> j) {
    final tse = j['tse'] as Map<String, dynamic>?;
    final pt = j['pt'] as Map<String, dynamic>?;
    return FiscalStamp(
      fullNumber: j['fullNumber'] as String? ?? '',
      regime: j['regime'] as String? ?? 'NONE',
      tseSerial: tse?['serialNumber'] as String?,
      tseTransactionNumber: (tse?['transactionNumber'] as num?)?.toInt(),
      tseSignatureCounter: (tse?['signatureCounter'] as num?)?.toInt(),
      tseSignature: tse?['signature'] as String?,
      tseStartedAt: tse?['startedAt'] as String?,
      tseFinishedAt: tse?['finishedAt'] as String?,
      tseQr: tse?['qr'] as String?,
      tseError: tse?['error'] as String?,
      ptExcerpt: pt?['printedExcerpt'] as String?,
      ptCertificateNumber: pt?['certificateNumber'] as String?,
      ptAtcud: pt?['atcud'] as String?,
    );
  }
}

/// The legal receipt for a sale, with its number and the regime's stamp. One
/// request that order-svc holds up to [waitSeconds] while the payment that
/// completes the sale lands, then a short local retry in case that wait was
/// cut off. Null only when the number is still not issued after all of that —
/// the receipt then says so, and is reprinted with the number once it exists.
Future<FiscalStamp?> awaitFiscalReceipt(
  Dio dio,
  String orderId, {
  int waitSeconds = 12,
  int attempts = 4,
  Duration interval = const Duration(milliseconds: 750),
}) async {
  for (var i = 0; i < attempts; i++) {
    try {
      final resp = await dio.get(
        '/${ApiConstants.order}/orders/$orderId/fiscal-receipt',
        // The server waits so the till does not have to poll: one round trip
        // brings the number in the common case. Retries ask plainly.
        queryParameters: i == 0 && waitSeconds > 0
            ? {'wait': waitSeconds}
            : null,
        options: Options(receiveTimeout: Duration(seconds: waitSeconds + 10)),
      );
      final data = (resp.data as Map?)?['data'];
      if (data is Map) {
        final number = data['fullNumber'];
        if (number is String && number.isNotEmpty) {
          return FiscalStamp.fromJson(Map<String, dynamic>.from(data));
        }
      }
    } on DioException catch (e) {
      // 404 means "not issued yet", which waiting can change. Anything else —
      // offline, refused — will not change by waiting, and the customer is
      // standing at the till.
      if (e.response?.statusCode != 404) return null;
    }
    if (i < attempts - 1) await Future<void>.delayed(interval);
  }
  return null;
}

/// The legal receipt number alone, for callers that print nothing else.
Future<String?> awaitFiscalNumber(
  Dio dio,
  String orderId, {
  int waitSeconds = 12,
  int attempts = 4,
  Duration interval = const Duration(milliseconds: 750),
}) async {
  final stamp = await awaitFiscalReceipt(
    dio,
    orderId,
    waitSeconds: waitSeconds,
    attempts: attempts,
    interval: interval,
  );
  return stamp?.fullNumber;
}
