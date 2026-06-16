import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

/// The currently open till session id for this terminal (null = no open till).
final activeTillProvider = StateProvider<String?>((ref) => null);

class TillReport {
  final String tillSessionId;
  final double floatAmount;
  final double cashDropsTotal;
  final double expectedCashInTill;
  final double grossSales;
  final double totalRefunds;
  final double netSales;

  const TillReport({
    required this.tillSessionId,
    required this.floatAmount,
    required this.cashDropsTotal,
    required this.expectedCashInTill,
    required this.grossSales,
    required this.totalRefunds,
    required this.netSales,
  });

  factory TillReport.fromJson(Map<String, dynamic> j) => TillReport(
        tillSessionId: j['tillSessionId'] as String? ?? '',
        floatAmount: (j['floatAmount'] as num?)?.toDouble() ?? 0,
        cashDropsTotal: (j['cashDropsTotal'] as num?)?.toDouble() ?? 0,
        expectedCashInTill: (j['expectedCashInTill'] as num?)?.toDouble() ?? 0,
        grossSales: (j['grossSales'] as num?)?.toDouble() ?? 0,
        totalRefunds: (j['totalRefunds'] as num?)?.toDouble() ?? 0,
        netSales: (j['netSales'] as num?)?.toDouble() ?? 0,
      );
}

/// Live X-report for an open till session.
final xReportProvider =
    FutureProvider.autoDispose.family<TillReport, String>((ref, sessionId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/x-report');
  return TillReport.fromJson(resp.data['data'] as Map<String, dynamic>);
});
