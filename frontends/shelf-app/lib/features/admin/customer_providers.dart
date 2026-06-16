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
  final String? dob;
  final String? gender;

  const Customer({
    required this.id,
    required this.email,
    this.phone,
    required this.firstName,
    required this.lastName,
    required this.status,
    this.dob,
    this.gender,
  });

  String get fullName => '$firstName $lastName'.trim();

  factory Customer.fromJson(Map<String, dynamic> j) => Customer(
        id: j['id'] as String? ?? '',
        email: j['email'] as String? ?? '',
        phone: j['phone'] as String?,
        firstName: j['firstName'] as String? ?? '',
        lastName: j['lastName'] as String? ?? '',
        status: j['status'] as String? ?? '',
        dob: j['dob'] as String?,
        gender: j['gender'] as String?,
      );
}

class CustomerAddress {
  final String id;
  final String type;
  final String line1;
  final String? line2;
  final String? city;
  final String? state;
  final String country;
  final String? pincode;
  final bool isDefault;

  const CustomerAddress({
    required this.id,
    required this.type,
    required this.line1,
    this.line2,
    this.city,
    this.state,
    required this.country,
    this.pincode,
    required this.isDefault,
  });

  String get oneLine => [line1, line2, city, state, pincode, country]
      .where((e) => e != null && e.isNotEmpty)
      .join(', ');

  factory CustomerAddress.fromJson(Map<String, dynamic> j) => CustomerAddress(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? 'HOME',
        line1: j['line1'] as String? ?? '',
        line2: j['line2'] as String?,
        city: j['city'] as String?,
        state: j['state'] as String?,
        country: j['country'] as String? ?? '',
        pincode: j['pincode'] as String?,
        isDefault: j['isDefault'] as bool? ?? false,
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

/// A single customer's full record (refreshes the detail view after an edit).
final customerDetailProvider =
    FutureProvider.autoDispose.family<Customer, String>((ref, id) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$id');
  return Customer.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// A customer's saved addresses (ship-to / billing).
final customerAddressesProvider = FutureProvider.autoDispose
    .family<List<CustomerAddress>, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/addresses');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => CustomerAddress.fromJson(e as Map<String, dynamic>))
      .toList();
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
