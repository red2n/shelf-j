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
  });

  factory Promotion.fromJson(Map<String, dynamic> j) => Promotion(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        type: j['type'] as String? ?? 'PERCENT',
        value: (j['value'] as num?)?.toDouble() ?? 0,
        minOrderAmount: (j['minOrderAmount'] as num?)?.toDouble(),
        channel: j['channel'] as String?,
        active: j['active'] as bool? ?? false,
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
