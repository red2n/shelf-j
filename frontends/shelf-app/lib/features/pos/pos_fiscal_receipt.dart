import 'package:dio/dio.dart';

import '../../core/constants.dart';

/// Waits for order-svc to issue the sale's legal receipt number.
///
/// The number is taken when the payment that completes a sale reaches
/// order-svc — asynchronously, a few seconds after the till posts the last
/// tender. A receipt printed before then has no number on it, and in the
/// markets that require one it is not a receipt, so the till waits a bounded
/// time. Null when it has not arrived: the receipt then says so, rather than
/// printing an order id where the number belongs.
Future<String?> awaitFiscalNumber(
  Dio dio,
  String orderId, {
  int attempts = 16,
  Duration interval = const Duration(milliseconds: 500),
}) async {
  for (var i = 0; i < attempts; i++) {
    try {
      final resp =
          await dio.get('/${ApiConstants.order}/orders/$orderId/fiscal-receipt');
      final number = ((resp.data as Map?)?['data'] as Map?)?['fullNumber'];
      if (number is String && number.isNotEmpty) return number;
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
