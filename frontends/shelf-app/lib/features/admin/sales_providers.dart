import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ── Gift cards (lookup by code; no list endpoint) ────────────────────────────

class GiftCard {
  final String code;
  final double currentBalance;
  final double initialBalance;
  final String status;
  final String currency;
  final String? expiresAt;

  const GiftCard({
    required this.code,
    required this.currentBalance,
    required this.initialBalance,
    required this.status,
    required this.currency,
    this.expiresAt,
  });

  factory GiftCard.fromJson(Map<String, dynamic> j) => GiftCard(
        code: j['code'] as String? ?? '',
        currentBalance: (j['currentBalance'] as num?)?.toDouble() ?? 0,
        initialBalance: (j['initialBalance'] as num?)?.toDouble() ?? 0,
        status: j['status'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        expiresAt: j['expiresAt'] as String?,
      );
}

class GiftCardTxn {
  final String txType;
  final double amount;
  final double balanceAfter;
  final String createdAt;

  const GiftCardTxn({
    required this.txType,
    required this.amount,
    required this.balanceAfter,
    required this.createdAt,
  });

  factory GiftCardTxn.fromJson(Map<String, dynamic> j) => GiftCardTxn(
        txType: j['txType'] as String? ?? '',
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
        balanceAfter: (j['balanceAfter'] as num?)?.toDouble() ?? 0,
        createdAt: j['createdAt'] as String? ?? '',
      );
}

// ── Layaways (lookup by id; no list endpoint) ────────────────────────────────

class Layaway {
  final String id;
  final String? customerId;
  final double totalAmount;
  final double depositPaid;
  final double balance;
  final String status;
  final String? dueDate;

  const Layaway({
    required this.id,
    this.customerId,
    required this.totalAmount,
    required this.depositPaid,
    required this.balance,
    required this.status,
    this.dueDate,
  });

  factory Layaway.fromJson(Map<String, dynamic> j) => Layaway(
        id: j['id'] as String? ?? '',
        customerId: j['customerId'] as String?,
        totalAmount: (j['totalAmount'] as num?)?.toDouble() ?? 0,
        depositPaid: (j['depositPaid'] as num?)?.toDouble() ?? 0,
        balance: (j['balance'] as num?)?.toDouble() ?? 0,
        status: j['status'] as String? ?? '',
        dueDate: j['dueDate'] as String?,
      );
}

// ── Special orders (has list) ────────────────────────────────────────────────

class SpecialOrder {
  final String id;
  final String? customerName;
  final String status;
  final double total;
  final String currency;
  final String? requestedDeliveryDate;

  const SpecialOrder({
    required this.id,
    this.customerName,
    required this.status,
    required this.total,
    required this.currency,
    this.requestedDeliveryDate,
  });

  factory SpecialOrder.fromJson(Map<String, dynamic> j) => SpecialOrder(
        id: j['id'] as String? ?? '',
        customerName: j['customerName'] as String?,
        status: j['status'] as String? ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        requestedDeliveryDate: j['requestedDeliveryDate'] as String?,
      );
}

final specialOrdersProvider =
    FutureProvider.autoDispose<List<SpecialOrder>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/admin/special-orders', queryParameters: {'limit': 100});
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => SpecialOrder.fromJson(e as Map<String, dynamic>)).toList();
});
