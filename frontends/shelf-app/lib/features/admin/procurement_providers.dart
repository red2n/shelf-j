import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ── Models ───────────────────────────────────────────────────────────────────

class Supplier {
  final String id;
  final String name;
  final String? vatNumber;
  final bool vatRegistered;
  final String? countryCode;
  final String? currency;
  final int paymentTermsDays;

  const Supplier({
    required this.id,
    required this.name,
    this.vatNumber,
    required this.vatRegistered,
    this.countryCode,
    this.currency,
    required this.paymentTermsDays,
  });

  factory Supplier.fromJson(Map<String, dynamic> j) => Supplier(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        vatNumber: j['vatNumber'] as String?,
        vatRegistered: j['vatRegistered'] as bool? ?? false,
        countryCode: j['countryCode'] as String?,
        currency: j['currency'] as String?,
        paymentTermsDays: (j['paymentTermsDays'] as num?)?.toInt() ?? 0,
      );
}

class PurchaseOrder {
  final String id;
  final String supplierId;
  final String storeId;
  final String status;
  final String currency;
  final double totalNet;
  final double totalVat;
  final double totalGross;
  final String? expectedDelivery;
  final String createdAt;

  const PurchaseOrder({
    required this.id,
    required this.supplierId,
    required this.storeId,
    required this.status,
    required this.currency,
    required this.totalNet,
    required this.totalVat,
    required this.totalGross,
    this.expectedDelivery,
    required this.createdAt,
  });

  factory PurchaseOrder.fromJson(Map<String, dynamic> j) => PurchaseOrder(
        id: j['id'] as String? ?? '',
        supplierId: j['supplierId'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        status: j['status'] as String? ?? '-',
        currency: j['currency'] as String? ?? '',
        totalNet: (j['totalNet'] as num?)?.toDouble() ?? 0,
        totalVat: (j['totalVat'] as num?)?.toDouble() ?? 0,
        totalGross: (j['totalGross'] as num?)?.toDouble() ?? 0,
        expectedDelivery: j['expectedDelivery'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
      );
}

class PurchaseOrderLine {
  final String id;
  final String variantId;
  final double qty;
  final double unitPrice;
  final String? vatCode;

  const PurchaseOrderLine({
    required this.id,
    required this.variantId,
    required this.qty,
    required this.unitPrice,
    this.vatCode,
  });

  factory PurchaseOrderLine.fromJson(Map<String, dynamic> j) => PurchaseOrderLine(
        id: j['id'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        vatCode: j['vatCode'] as String?,
      );
}

// ── Providers ────────────────────────────────────────────────────────────────

final suppliersProvider = FutureProvider.autoDispose<List<Supplier>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/suppliers');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => Supplier.fromJson(e as Map<String, dynamic>)).toList();
});

final purchaseOrdersProvider =
    FutureProvider.autoDispose<List<PurchaseOrder>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/purchase-orders');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => PurchaseOrder.fromJson(e as Map<String, dynamic>)).toList();
});

/// How much of each ordered line has actually turned up.
///
/// Separate from the lines themselves because it answers a different question:
/// the lines say what was ordered, this says what is still owed. A
/// PARTIALLY_RECEIVED badge tells a buyer that something is missing but not
/// what, which is the only thing they can act on.
class PurchaseOrderLineProgress {
  final String variantId;
  final double qtyOrdered;
  final double qtyReceived;
  final double qtyOutstanding;

  const PurchaseOrderLineProgress({
    required this.variantId,
    required this.qtyOrdered,
    required this.qtyReceived,
    required this.qtyOutstanding,
  });

  factory PurchaseOrderLineProgress.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderLineProgress(
        variantId: j['variantId'] as String? ?? '',
        qtyOrdered: (j['qtyOrdered'] as num?)?.toDouble() ?? 0,
        qtyReceived: (j['qtyReceived'] as num?)?.toDouble() ?? 0,
        qtyOutstanding: (j['qtyOutstanding'] as num?)?.toDouble() ?? 0,
      );
}

final purchaseOrderProgressProvider = FutureProvider.autoDispose
    .family<List<PurchaseOrderLineProgress>, String>((ref, poId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/purchase-orders/$poId/progress');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => PurchaseOrderLineProgress.fromJson(e as Map<String, dynamic>))
      .toList();
});

final purchaseOrderLinesProvider = FutureProvider.autoDispose
    .family<List<PurchaseOrderLine>, String>((ref, poId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/purchase-orders/$poId/lines');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => PurchaseOrderLine.fromJson(e as Map<String, dynamic>))
      .toList();
});
