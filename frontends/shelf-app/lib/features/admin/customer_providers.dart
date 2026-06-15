import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ── Models ───────────────────────────────────────────────────────────────────

class Customer {
  final String id;
  final String email;
  final String? phone;
  final String firstName;
  final String lastName;
  final String status;

  const Customer({
    required this.id,
    required this.email,
    this.phone,
    required this.firstName,
    required this.lastName,
    required this.status,
  });

  String get fullName => '$firstName $lastName'.trim();

  factory Customer.fromJson(Map<String, dynamic> j) => Customer(
        id: j['id'] as String? ?? '',
        email: j['email'] as String? ?? '',
        phone: j['phone'] as String?,
        firstName: j['firstName'] as String? ?? '',
        lastName: j['lastName'] as String? ?? '',
        status: j['status'] as String? ?? '',
      );
}

class LoyaltyAccount {
  final double pointsBalance;
  final double lifetimePoints;
  final String? tier;

  const LoyaltyAccount({
    required this.pointsBalance,
    required this.lifetimePoints,
    this.tier,
  });

  factory LoyaltyAccount.fromJson(Map<String, dynamic> j) => LoyaltyAccount(
        pointsBalance: (j['pointsBalance'] as num?)?.toDouble() ?? 0,
        lifetimePoints: (j['lifetimePoints'] as num?)?.toDouble() ?? 0,
        tier: j['tier'] as String?,
      );
}

class LoyaltyLedgerEntry {
  final String type;
  final double points;
  final double balanceAfter;
  final String? reason;
  final String createdAt;

  const LoyaltyLedgerEntry({
    required this.type,
    required this.points,
    required this.balanceAfter,
    this.reason,
    required this.createdAt,
  });

  factory LoyaltyLedgerEntry.fromJson(Map<String, dynamic> j) => LoyaltyLedgerEntry(
        type: j['type'] as String? ?? '',
        points: (j['points'] as num?)?.toDouble() ?? 0,
        balanceAfter: (j['balanceAfter'] as num?)?.toDouble() ?? 0,
        reason: j['reason'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
      );
}

class StoreCreditAccount {
  final double balance;
  final String currency;

  const StoreCreditAccount({required this.balance, required this.currency});

  factory StoreCreditAccount.fromJson(Map<String, dynamic> j) => StoreCreditAccount(
        balance: (j['balance'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
      );
}

// ── Providers ────────────────────────────────────────────────────────────────

final customersProvider = FutureProvider.autoDispose<List<Customer>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.customer}/customers',
      queryParameters: {'limit': 100});
  final items = (resp.data['data']?['items'] as List?) ?? [];
  return items.map((e) => Customer.fromJson(e as Map<String, dynamic>)).toList();
});

final customerLoyaltyProvider =
    FutureProvider.autoDispose.family<LoyaltyAccount, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/loyalty');
  return LoyaltyAccount.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final customerLoyaltyLedgerProvider = FutureProvider.autoDispose
    .family<List<LoyaltyLedgerEntry>, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/loyalty/ledger');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => LoyaltyLedgerEntry.fromJson(e as Map<String, dynamic>))
      .toList();
});

final customerStoreCreditProvider = FutureProvider.autoDispose
    .family<StoreCreditAccount, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/store-credit');
  return StoreCreditAccount.fromJson(resp.data['data'] as Map<String, dynamic>);
});
