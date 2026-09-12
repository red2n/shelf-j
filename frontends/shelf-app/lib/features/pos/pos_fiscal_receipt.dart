import 'package:dio/dio.dart';

import '../../core/constants.dart';

/// The legal receipt number for a sale. One request that order-svc holds up
/// to [waitSeconds] while the payment that completes the sale lands, then a
/// short local retry in case that wait was cut off. Null only when the number
/// is still not issued after all of that — the receipt then says so, and is
/// reprinted with the number once it exists.
Future<String?> awaitFiscalNumber(
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
        queryParameters:
            i == 0 && waitSeconds > 0 ? {'wait': waitSeconds} : null,
        options: Options(receiveTimeout: Duration(seconds: waitSeconds + 10)),
      );
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
