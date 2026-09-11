import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/paged.dart';

// ── Models ───────────────────────────────────────────────────────────────────

class PriceList {
  final String id;
  final String name;
  final String? channel;
  final String? currency;
  final String? effectiveFrom;
  final String? effectiveTo;
  final bool active;

  const PriceList({
    required this.id,
    required this.name,
    this.channel,
    this.currency,
    this.effectiveFrom,
    this.effectiveTo,
    required this.active,
  });

  factory PriceList.fromJson(Map<String, dynamic> j) => PriceList(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        channel: j['channel'] as String?,
        currency: j['currency'] as String?,
        effectiveFrom: j['effectiveFrom'] as String?,
        effectiveTo: j['effectiveTo'] as String?,
        active: j['active'] as bool? ?? false,
      );
}

class PriceListItem {
  final String id;
  final String variantId;
  final double price;
  final double minQty;

  const PriceListItem({
    required this.id,
    required this.variantId,
    required this.price,
    required this.minQty,
  });

  factory PriceListItem.fromJson(Map<String, dynamic> j) => PriceListItem(
        id: j['id'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        price: (j['price'] as num?)?.toDouble() ?? 0,
        minQty: (j['minQty'] as num?)?.toDouble() ?? 1,
      );
}

class Promotion {
  final String id;
  final String name;
  final String type;
  final double value;
  final double? minOrderAmount;
  final String? channel;
  final bool active;
  final String? startsAt;
  final String? endsAt;

  /// Application order, ascending — lower runs first. Which of two overlapping
  /// offers wins used to be an accident of a SQL sort that compared a
  /// percentage against a sum of money.
  final int priority;

  /// True when this promotion stops every promotion after it.
  final bool exclusive;

  /// Code the customer must present, or null when it applies on its own.
  final String? couponCode;
  final int? maxRedemptions;
  final int? maxPerCustomer;

  /// BOGO only: buy [buyQty], get [getQty] at [getDiscountPct] off.
  final double? buyQty;
  final double? getQty;
  final double? getDiscountPct;

  const Promotion({
    required this.id,
    required this.name,
    required this.type,
    required this.value,
    this.minOrderAmount,
    this.channel,
    required this.active,
    this.startsAt,
    this.endsAt,
    this.priority = 100,
    this.exclusive = false,
    this.couponCode,
    this.maxRedemptions,
    this.maxPerCustomer,
    this.buyQty,
    this.getQty,
    this.getDiscountPct,
  });

  /// How this promotion reads on one line of the list.
  String get summary {
    switch (type) {
      case 'PERCENT':
        return '${value.toStringAsFixed(0)}% off each item';
      case 'FLAT':
        return '${value.toStringAsFixed(2)} off each item';
      case 'BASKET_PERCENT':
        return '${value.toStringAsFixed(0)}% off the basket';
      case 'BASKET_FLAT':
        return '${value.toStringAsFixed(2)} off the basket';
      case 'SPEND_THRESHOLD':
        return '${value.toStringAsFixed(2)} off over '
            '${(minOrderAmount ?? 0).toStringAsFixed(2)}';
      case 'BOGO':
        final free = (getDiscountPct ?? 0) >= 100;
        return 'Buy ${(buyQty ?? 0).toStringAsFixed(0)}, '
            'get ${(getQty ?? 0).toStringAsFixed(0)} '
            '${free ? 'free' : '${(getDiscountPct ?? 0).toStringAsFixed(0)}% off'}';
      default:
        return type;
    }
  }

  factory Promotion.fromJson(Map<String, dynamic> j) => Promotion(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        type: j['type'] as String? ?? 'PERCENT',
        value: (j['value'] as num?)?.toDouble() ?? 0,
        minOrderAmount: (j['minOrderAmount'] as num?)?.toDouble(),
        channel: j['channel'] as String?,
        active: j['active'] as bool? ?? false,
        priority: (j['priority'] as num?)?.toInt() ?? 100,
        exclusive: j['exclusive'] as bool? ?? false,
        couponCode: j['couponCode'] as String?,
        maxRedemptions: (j['maxRedemptions'] as num?)?.toInt(),
        maxPerCustomer: (j['maxPerCustomer'] as num?)?.toInt(),
        buyQty: (j['buyQty'] as num?)?.toDouble(),
        getQty: (j['getQty'] as num?)?.toDouble(),
        getDiscountPct: (j['getDiscountPct'] as num?)?.toDouble(),
        startsAt: j['startsAt'] as String?,
        endsAt: j['endsAt'] as String?,
      );
}

class VatRate {
  final String code;
  final String name;
  final double rate;
  final bool exempt;
  final String? description;
  final String? effectiveFrom;

  const VatRate({
    required this.code,
    required this.name,
    required this.rate,
    required this.exempt,
    this.description,
    this.effectiveFrom,
  });

  factory VatRate.fromJson(Map<String, dynamic> j) => VatRate(
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        rate: (j['rate'] as num?)?.toDouble() ?? 0,
        exempt: j['exempt'] as bool? ?? false,
        description: j['description'] as String?,
        effectiveFrom: j['effectiveFrom'] as String?,
      );
}

// ── Providers ────────────────────────────────────────────────────────────────

final priceListsProvider = FutureProvider.autoDispose<List<PriceList>>((ref) async {
  final data = await fetchAllPages(
      ref.read(apiClientProvider).dio, '/${ApiConstants.pricing}/price-lists');
  return data.map((e) => PriceList.fromJson(e as Map<String, dynamic>)).toList();
});

final priceListItemsProvider = FutureProvider.autoDispose
    .family<List<PriceListItem>, String>((ref, priceListId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/price-lists/$priceListId/items');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => PriceListItem.fromJson(e as Map<String, dynamic>)).toList();
});

final promotionsProvider = FutureProvider.autoDispose<List<Promotion>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/promotions');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => Promotion.fromJson(e as Map<String, dynamic>)).toList();
});

final vatRatesProvider = FutureProvider.autoDispose<List<VatRate>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/vat-rates');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => VatRate.fromJson(e as Map<String, dynamic>)).toList();
});

/// HMRC MTD VAT return boxes 1–9 for a period (ISO-8601 timestamps).
class VatReturn {
  final double box1;
  final double box2;
  final double box3;
  final double box4;
  final double box5;
  final double box6;
  final double box7;
  final double box8;
  final double box9;
  final String? periodFrom;
  final String? periodTo;

  const VatReturn({
    required this.box1,
    required this.box2,
    required this.box3,
    required this.box4,
    required this.box5,
    required this.box6,
    required this.box7,
    required this.box8,
    required this.box9,
    this.periodFrom,
    this.periodTo,
  });

  factory VatReturn.fromJson(Map<String, dynamic> j) => VatReturn(
        box1: (j['box1'] as num?)?.toDouble() ?? 0,
        box2: (j['box2'] as num?)?.toDouble() ?? 0,
        box3: (j['box3'] as num?)?.toDouble() ?? 0,
        box4: (j['box4'] as num?)?.toDouble() ?? 0,
        box5: (j['box5'] as num?)?.toDouble() ?? 0,
        box6: (j['box6'] as num?)?.toDouble() ?? 0,
        box7: (j['box7'] as num?)?.toDouble() ?? 0,
        box8: (j['box8'] as num?)?.toDouble() ?? 0,
        box9: (j['box9'] as num?)?.toDouble() ?? 0,
        periodFrom: j['periodFrom'] as String?,
        periodTo: j['periodTo'] as String?,
      );
}

/// ISO timestamp range for the VAT return query.
class VatReturnRange {
  final String from;
  final String to;
  const VatReturnRange({required this.from, required this.to});

  @override
  bool operator ==(Object other) =>
      other is VatReturnRange && other.from == from && other.to == to;

  @override
  int get hashCode => Object.hash(from, to);
}

/// Default: current UK VAT quarter (calendar quarter) in UTC.
VatReturnRange defaultVatReturnRange() {
  final now = DateTime.now().toUtc();
  final qMonth = ((now.month - 1) ~/ 3) * 3 + 1;
  final from = DateTime.utc(now.year, qMonth, 1);
  final to = DateTime.utc(
      qMonth == 10 ? now.year + 1 : now.year, qMonth == 10 ? 1 : qMonth + 3, 1);
  return VatReturnRange(
    from: from.toIso8601String(),
    to: to.toIso8601String(),
  );
}

final vatReturnProvider =
    FutureProvider.autoDispose.family<VatReturn, VatReturnRange>((ref, range) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.pricing}/vat-return',
    queryParameters: {'from': range.from, 'to': range.to},
  );
  return VatReturn.fromJson(resp.data['data'] as Map<String, dynamic>);
});
