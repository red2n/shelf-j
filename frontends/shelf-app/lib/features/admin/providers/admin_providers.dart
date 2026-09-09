import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/paged.dart';

// ── Models ──────────────────────────────────────────────────────────────────

class OrderSummary {
  final String id;
  final String storeId;
  final String channel;
  final String fulfilmentType;
  final String status;
  final double total;
  final String currency;
  final String createdAt;

  /// The tender the customer declared at checkout (CASH/CARD/UPI/WALLET); null for
  /// legacy orders and POS split-tender sales.
  final String? paymentMethod;

  const OrderSummary({
    required this.id,
    this.storeId = '',
    required this.channel,
    this.fulfilmentType = 'INSTORE',
    required this.status,
    required this.total,
    required this.currency,
    required this.createdAt,
    this.paymentMethod,
  });

  factory OrderSummary.fromJson(Map<String, dynamic> j) => OrderSummary(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        channel: j['channel'] as String? ?? '-',
        fulfilmentType: j['fulfilmentType'] as String? ?? 'INSTORE',
        status: j['status'] as String? ?? '-',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? 'INR',
        createdAt: j['createdAt'] as String? ?? '',
        paymentMethod: j['paymentMethod'] as String?,
      );
}

class InventoryLevel {
  final String variantId;
  final String storeId;
  final double onHand;
  final double reserved;
  final double available;

  const InventoryLevel({
    required this.variantId,
    required this.storeId,
    required this.onHand,
    required this.reserved,
    required this.available,
  });

  factory InventoryLevel.fromJson(Map<String, dynamic> j) => InventoryLevel(
        variantId: j['variantId'] as String? ?? '-',
        storeId: j['storeId'] as String? ?? '-',
        onHand: (j['onHand'] as num?)?.toDouble() ?? 0,
        reserved: (j['reserved'] as num?)?.toDouble() ?? 0,
        available: (j['available'] as num?)?.toDouble() ?? 0,
      );

  /// Default low-stock heuristic when no reorder threshold is configured.
  bool get isLow => available <= 5;

  /// Low stock against real reorder thresholds (key = `storeId:variantId`).
  /// Falls back to [isLow] when this store+variant has no threshold.
  bool isLowAgainst(Map<String, double> thresholds) {
    final t = thresholds['$storeId:$variantId'];
    if (t == null) return isLow;
    return available <= t;
  }
}

/// Reorder threshold (min qty that triggers low-stock) for a store+variant.
class ReorderThreshold {
  final String id;
  final String storeId;
  final String variantId;
  final double threshold;
  final double? maxQty;

  const ReorderThreshold({
    required this.id,
    required this.storeId,
    required this.variantId,
    required this.threshold,
    this.maxQty,
  });

  factory ReorderThreshold.fromJson(Map<String, dynamic> j) => ReorderThreshold(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        threshold: (j['threshold'] as num?)?.toDouble() ?? 0,
        maxQty: (j['maxQty'] as num?)?.toDouble(),
      );
}

class TenantInfo {
  final String id;
  final String name;
  final String status;
  final String currency;
  final String country;

  const TenantInfo({
    required this.id,
    required this.name,
    required this.status,
    required this.currency,
    required this.country,
  });

  factory TenantInfo.fromJson(Map<String, dynamic> j) => TenantInfo(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        status: j['status'] as String? ?? '-',
        currency: j['currency'] as String? ?? 'INR',
        country: j['country'] as String? ?? '-',
      );
}

class StoreInfo {
  final String id;
  final String name;
  final String code;
  final String type;
  final String status;
  final String? line1;
  final String? line2;
  final String? city;
  final String? state;
  final String? country;
  final String? pincode;
  final double? geoLat;
  final double? geoLng;
  final String? timezone;
  final String? businessHours;
  final bool showPrices;

  /// Tenders the owner enabled for this store (subset of CASH, CARD, UPI, WALLET).
  final List<String> enabledPaymentMethods;

  const StoreInfo({
    required this.id,
    required this.name,
    required this.code,
    required this.type,
    required this.status,
    this.line1,
    this.line2,
    this.city,
    this.state,
    this.country,
    this.pincode,
    this.geoLat,
    this.geoLng,
    this.timezone,
    this.businessHours,
    this.showPrices = true,
    this.enabledPaymentMethods = const ['CASH', 'CARD'],
  });

  factory StoreInfo.fromJson(Map<String, dynamic> j) => StoreInfo(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        code: j['code'] as String? ?? '-',
        type: j['type'] as String? ?? 'STORE',
        status: j['status'] as String? ?? '-',
        line1: j['line1'] as String?,
        line2: j['line2'] as String?,
        city: j['city'] as String?,
        state: j['state'] as String?,
        country: j['country'] as String?,
        pincode: j['pincode'] as String?,
        geoLat: (j['geoLat'] as num?)?.toDouble(),
        geoLng: (j['geoLng'] as num?)?.toDouble(),
        timezone: j['timezone'] as String?,
        businessHours: j['businessHours'] as String?,
        showPrices: j['showPrices'] as bool? ?? true,
        enabledPaymentMethods: (j['enabledPaymentMethods'] as List?)
                ?.map((e) => e.toString().toUpperCase())
                .toList() ??
            const ['CASH', 'CARD'],
      );
}

class ZoneInfo {
  final String id;
  final String storeId;
  final String name;
  final String code;
  final String type;
  final String status;

  const ZoneInfo({
    required this.id,
    required this.storeId,
    required this.name,
    required this.code,
    required this.type,
    required this.status,
  });

  factory ZoneInfo.fromJson(Map<String, dynamic> j) => ZoneInfo(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        code: j['code'] as String? ?? '-',
        type: j['type'] as String? ?? 'AISLE',
        status: j['status'] as String? ?? '-',
      );
}

class BatchInfo {
  final String id;
  final String storeId;
  final String variantId;
  final String batchNo;
  final double receivedQty;
  final double remainingQty;
  final double? costPrice;
  final String? expiryDate;
  final String createdAt;
  final String status;
  final String materialStatus;
  final String? materialStatusReason;
  final String? grade;
  final String? zoneId;

  const BatchInfo({
    required this.id,
    required this.storeId,
    required this.variantId,
    required this.batchNo,
    required this.receivedQty,
    required this.remainingQty,
    this.costPrice,
    this.expiryDate,
    required this.createdAt,
    required this.status,
    required this.materialStatus,
    this.materialStatusReason,
    this.grade,
    this.zoneId,
  });

  factory BatchInfo.fromJson(Map<String, dynamic> j) => BatchInfo(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '-',
        variantId: j['variantId'] as String? ?? '-',
        batchNo: j['batchNo'] as String? ?? '-',
        receivedQty: (j['receivedQty'] as num?)?.toDouble() ?? 0,
        remainingQty: (j['remainingQty'] as num?)?.toDouble() ?? 0,
        costPrice: (j['costPrice'] as num?)?.toDouble(),
        expiryDate: j['expiryDate'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
        status: j['status'] as String? ?? '-',
        materialStatus: j['materialStatus'] as String? ?? '-',
        materialStatusReason: j['materialStatusReason'] as String?,
        grade: j['grade'] as String?,
        zoneId: j['zoneId'] as String?,
      );
}

class OnHandRow {
  final String storeId;
  final String variantId;
  final double onHand;

  const OnHandRow({required this.storeId, required this.variantId, required this.onHand});

  factory OnHandRow.fromJson(Map<String, dynamic> j) => OnHandRow(
        storeId: j['storeId'] as String? ?? '-',
        variantId: j['variantId'] as String? ?? '-',
        onHand: (j['onHand'] as num?)?.toDouble() ?? 0,
      );
}

// ── Providers ────────────────────────────────────────────────────────────────

/// Recent 10 orders for dashboard summary.
final recentOrdersProvider = FutureProvider.autoDispose<List<OrderSummary>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.order}/orders',
    queryParameters: {'limit': 10},
  );
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList();
});

// Paginated orders now live in orders_pagination.dart (ordersPaginationProvider) —
// server-side channel/status filtering + cursor infinite scroll, replacing the old
// "fetch 50 and filter in Dart" ordersProvider.
//
// Paginated inventory levels + the dashboard summary KPIs live in
// inventory_levels_pagination.dart (inventoryLevelsPaginationProvider /
// inventoryLevelsSummaryProvider), replacing the old fetch-all inventoryLevelsProvider.

/// A human-readable label for a variant — so screens show a name + SKU instead of
/// the raw variant UUID the inventory/order APIs return.
class VariantLabel {
  final String productName;
  final String sku;
  const VariantLabel({required this.productName, required this.sku});
}

/// Display name for a variant id: the resolved product name, or a short UUID
/// fallback while labels load / for unknown ids.
String variantDisplayName(String variantId, Map<String, VariantLabel> labels) {
  final l = labels[variantId];
  if (l != null && l.productName.isNotEmpty) return l.productName;
  final n = variantId.length >= 8 ? variantId.substring(0, 8) : variantId;
  return '$n…';
}

/// Resolved SKU for a variant id, or empty string when unknown.
String variantSku(String variantId, Map<String, VariantLabel> labels) =>
    labels[variantId]?.sku ?? '';

/// Builds a stable cache key (sorted, de-duped, comma-joined) from a set of
/// variant ids, so [variantLabelsProvider] reuses results across screens that
/// happen to reference the same variants.
String variantIdsKey(Iterable<String> ids) {
  final set = ids.where((s) => s.isNotEmpty).toSet().toList()..sort();
  return set.join(',');
}

/// Resolves a set of variant UUIDs (passed as the [variantIdsKey] csv) to their
/// product name + SKU via product-svc's batch resolve endpoint, so screens show
/// names instead of raw UUIDs. Missing ids simply aren't in the returned map.
final variantLabelsProvider = FutureProvider.autoDispose
    .family<Map<String, VariantLabel>, String>((ref, idsCsv) async {
  if (idsCsv.isEmpty) return const {};
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.product}/admin/products/variants/resolve',
        queryParameters: {'ids': idsCsv},
      );
  final data = (resp.data['data'] as List?) ?? [];
  final map = <String, VariantLabel>{};
  for (final e in data) {
    final m = e as Map<String, dynamic>;
    final id = m['variantId'] as String?;
    if (id == null) continue;
    map[id] = VariantLabel(
      productName: (m['productName'] as String?) ?? '',
      sku: (m['sku'] as String?) ?? '',
    );
  }
  return map;
});

/// Current tenant info (name, currency, status).
final tenantInfoProvider = FutureProvider.autoDispose<TenantInfo>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/tenant');
  return TenantInfo.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// All stores for the tenant (walks the cursor-paginated admin list).
final storesProvider = FutureProvider.autoDispose<List<StoreInfo>>((ref) async {
  final data = await fetchAllPages(
      ref.read(apiClientProvider).dio, '/${ApiConstants.tenant}/admin/stores');
  return data.map((e) => StoreInfo.fromJson(e as Map<String, dynamic>)).toList();
});

/// Zones (aisles/racks) within a store. Stock batches live in a (store, zone).
final zonesProvider =
    FutureProvider.autoDispose.family<List<ZoneInfo>, String>((ref, storeId) async {
  final data = await fetchAllPages(ref.read(apiClientProvider).dio,
      '/${ApiConstants.tenant}/admin/stores/$storeId/zones');
  return data.map((e) => ZoneInfo.fromJson(e as Map<String, dynamic>)).toList();
});

/// Recent batches received into a store (most recent 100), newest first.
final batchesProvider =
    FutureProvider.autoDispose.family<List<BatchInfo>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}/admin/inventory/batches',
    queryParameters: {'store': storeId, 'limit': 100},
  );
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => BatchInfo.fromJson(e as Map<String, dynamic>)).toList();
});

class ExpiringBatch {
  final String id;
  final String storeId;
  final String variantId;
  final String batchNo;
  final double remainingQty;
  final String? expiryDate;
  final int daysUntilExpiry;

  const ExpiringBatch({
    required this.id,
    required this.storeId,
    required this.variantId,
    required this.batchNo,
    required this.remainingQty,
    this.expiryDate,
    required this.daysUntilExpiry,
  });

  factory ExpiringBatch.fromJson(Map<String, dynamic> j) => ExpiringBatch(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        batchNo: j['batchNo'] as String? ?? '-',
        remainingQty: (j['remainingQty'] as num?)?.toDouble() ?? 0,
        expiryDate: j['expiryDate'] as String?,
        daysUntilExpiry: (j['daysUntilExpiry'] as num?)?.toInt() ?? 0,
      );
}

/// Batches expiring within [withinDays] at a store.
final expiringBatchesProvider = FutureProvider.autoDispose
    .family<List<ExpiringBatch>, ({String storeId, int withinDays})>((ref, args) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}/admin/inventory/batches/expiring',
    queryParameters: {
      'store': args.storeId,
      'withinDays': args.withinDays,
    },
  );
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => ExpiringBatch.fromJson(e as Map<String, dynamic>)).toList();
});

class TransferOrderLine {
  final String? id;
  final String variantId;
  final double requestedQty;
  final double? shippedQty;
  final double? receivedQty;

  const TransferOrderLine({
    this.id,
    required this.variantId,
    required this.requestedQty,
    this.shippedQty,
    this.receivedQty,
  });

  factory TransferOrderLine.fromJson(Map<String, dynamic> j) => TransferOrderLine(
        id: j['id'] as String?,
        variantId: j['variantId'] as String? ?? '',
        requestedQty: (j['requestedQty'] as num?)?.toDouble() ?? 0,
        shippedQty: (j['shippedQty'] as num?)?.toDouble(),
        receivedQty: (j['receivedQty'] as num?)?.toDouble(),
      );
}

class TransferOrder {
  final String id;
  final String fromStoreId;
  final String toStoreId;
  final String? transferType;
  final String status;
  final String? notes;
  final String? createdAt;
  final String? shippedAt;
  final String? receivedAt;
  final List<TransferOrderLine> lines;

  const TransferOrder({
    required this.id,
    required this.fromStoreId,
    required this.toStoreId,
    this.transferType,
    required this.status,
    this.notes,
    this.createdAt,
    this.shippedAt,
    this.receivedAt,
    required this.lines,
  });

  factory TransferOrder.fromJson(Map<String, dynamic> j) => TransferOrder(
        id: j['id'] as String? ?? '',
        fromStoreId: j['fromStoreId'] as String? ?? '',
        toStoreId: j['toStoreId'] as String? ?? '',
        transferType: j['transferType'] as String?,
        status: j['status'] as String? ?? '-',
        notes: j['notes'] as String?,
        createdAt: j['createdAt'] as String?,
        shippedAt: j['shippedAt'] as String?,
        receivedAt: j['receivedAt'] as String?,
        lines: ((j['lines'] as List?) ?? [])
            .map((e) => TransferOrderLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Transfer orders, optional store filter (empty = all).
final transferOrdersProvider =
    FutureProvider.autoDispose.family<List<TransferOrder>, String>((ref, storeId) async {
  final params = <String, dynamic>{'limit': 50};
  if (storeId.isNotEmpty) params['store'] = storeId;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.inventory}/admin/inventory/transfers',
        queryParameters: params,
      );
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => TransferOrder.fromJson(e as Map<String, dynamic>)).toList();
});

class StockMovement {
  final String id;
  final String storeId;
  final String variantId;
  final String? batchId;
  final String type;
  final double qty;
  final String? createdAt;

  const StockMovement({
    required this.id,
    required this.storeId,
    required this.variantId,
    this.batchId,
    required this.type,
    required this.qty,
    this.createdAt,
  });

  factory StockMovement.fromJson(Map<String, dynamic> j) => StockMovement(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        batchId: j['batchId'] as String?,
        type: j['type'] as String? ?? '-',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        createdAt: j['createdAt'] as String?,
      );
}

/// Recent stock movements for a store (empty storeId = tenant-wide if API allows).
final stockMovementsProvider =
    FutureProvider.autoDispose.family<List<StockMovement>, String>((ref, storeId) async {
  final params = <String, dynamic>{'limit': 50};
  if (storeId.isNotEmpty) params['store'] = storeId;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.inventory}/admin/inventory/movements',
        queryParameters: params,
      );
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => StockMovement.fromJson(e as Map<String, dynamic>)).toList();
});

/// Reorder thresholds for a store. Pass empty string for all stores.
final thresholdsProvider = FutureProvider.autoDispose
    .family<List<ReorderThreshold>, String>((ref, storeId) async {
  final params = <String, dynamic>{};
  if (storeId.isNotEmpty) params['store'] = storeId;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.inventory}/admin/inventory/thresholds',
        queryParameters: params.isEmpty ? null : params,
      );
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => ReorderThreshold.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Lookup map of `storeId:variantId` → threshold qty for low-stock checks.
final thresholdsMapProvider =
    FutureProvider.autoDispose<Map<String, double>>((ref) async {
  final list = await ref.watch(thresholdsProvider('').future);
  return {
    for (final t in list) '${t.storeId}:${t.variantId}': t.threshold,
  };
});

/// Store ids a product is restricted to (empty = sold at all stores).
final productStoresProvider =
    FutureProvider.autoDispose.family<List<String>, String>((ref, productId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.product}/admin/products/$productId/stores');
  return ((resp.data['data'] as List?) ?? []).map((e) => e.toString()).toList();
});

class StaffMember {
  final String id;
  final String userId;
  final String storeId;
  final String role;
  final String assignedAt;

  const StaffMember({
    required this.id,
    required this.userId,
    required this.storeId,
    required this.role,
    required this.assignedAt,
  });

  factory StaffMember.fromJson(Map<String, dynamic> j) => StaffMember(
        id: j['id'] as String? ?? '',
        userId: j['userId'] as String? ?? '-',
        storeId: j['storeId'] as String? ?? '-',
        role: j['role'] as String? ?? '-',
        assignedAt: j['assignedAt'] as String? ?? '',
      );
}

/// All staff assignments for the tenant (walks the cursor-paginated admin list).
final staffProvider = FutureProvider.autoDispose<List<StaffMember>>((ref) async {
  final data = await fetchAllPages(
      ref.read(apiClientProvider).dio, '/${ApiConstants.tenant}/admin/staff');
  return data.map((e) => StaffMember.fromJson(e as Map<String, dynamic>)).toList();
});

// ── Platform-admin models + providers ────────────────────────────────────────

class PlatformTenant {
  final String id;
  final String name;
  final String? legalName;
  final String status;
  final String country;
  final String currency;
  final String createdAt;

  const PlatformTenant({
    required this.id,
    required this.name,
    this.legalName,
    required this.status,
    required this.country,
    required this.currency,
    required this.createdAt,
  });

  factory PlatformTenant.fromJson(Map<String, dynamic> j) => PlatformTenant(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        legalName: j['legalName'] as String?,
        status: j['status'] as String? ?? '-',
        country: j['country'] as String? ?? '-',
        currency: j['currency'] as String? ?? '-',
        createdAt: j['createdAt'] as String? ?? '',
      );
}

/// All tenants — platform admin only (GET /tenant-svc/platform/tenants).
final allTenantsProvider = FutureProvider.autoDispose<List<PlatformTenant>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.tenant}/platform/tenants');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => PlatformTenant.fromJson(e as Map<String, dynamic>)).toList();
});

// ── Product catalog models + providers ───────────────────────────────────────

class CategoryInfo {
  final String id;
  final String? parentId;
  final String name;
  final String status;
  final String createdAt;

  const CategoryInfo({
    required this.id,
    this.parentId,
    required this.name,
    required this.status,
    required this.createdAt,
  });

  factory CategoryInfo.fromJson(Map<String, dynamic> j) => CategoryInfo(
        id: j['id'] as String? ?? '',
        parentId: j['parentId'] as String?,
        name: j['name'] as String? ?? '-',
        status: j['status'] as String? ?? '-',
        createdAt: j['createdAt'] as String? ?? '',
      );
}

class ProductInfo {
  final String id;
  final String name;
  final String? description;
  final String? categoryId;
  final String? brandId;
  final String status;
  final bool sellableOnline;
  final bool sellablePos;
  final String createdAt;

  const ProductInfo({
    required this.id,
    required this.name,
    this.description,
    this.categoryId,
    this.brandId,
    required this.status,
    required this.sellableOnline,
    required this.sellablePos,
    required this.createdAt,
  });

  factory ProductInfo.fromJson(Map<String, dynamic> j) => ProductInfo(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        description: j['description'] as String?,
        categoryId: j['categoryId'] as String?,
        brandId: j['brandId'] as String?,
        status: j['status'] as String? ?? '-',
        sellableOnline: j['sellableOnline'] as bool? ?? false,
        sellablePos: j['sellablePos'] as bool? ?? false,
        createdAt: j['createdAt'] as String? ?? '',
      );
}

class VariantInfo {
  final String id;
  final String productId;
  final String sku;
  final String? barcode;
  final String? unit;
  final String status;

  const VariantInfo({
    required this.id,
    required this.productId,
    required this.sku,
    this.barcode,
    this.unit,
    required this.status,
  });

  factory VariantInfo.fromJson(Map<String, dynamic> j) => VariantInfo(
        id: j['id'] as String? ?? '',
        productId: j['productId'] as String? ?? '',
        sku: j['sku'] as String? ?? '-',
        barcode: j['barcode'] as String?,
        unit: j['unit'] as String?,
        status: j['status'] as String? ?? '-',
      );
}

final categoriesProvider = FutureProvider.autoDispose<List<CategoryInfo>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.product}/admin/categories');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => CategoryInfo.fromJson(e as Map<String, dynamic>)).toList();
});

final productsProvider = FutureProvider.autoDispose<List<ProductInfo>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.product}/admin/products', queryParameters: {'limit': 100});
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => ProductInfo.fromJson(e as Map<String, dynamic>)).toList();
});

final productVariantsProvider =
    FutureProvider.autoDispose.family<List<VariantInfo>, String>((ref, productId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.product}/admin/products/$productId/variants');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => VariantInfo.fromJson(e as Map<String, dynamic>)).toList();
});

// ── Pricing (Phase A) ────────────────────────────────────────────────────────

/// Ensures the tenant has a default ALL-channel price list and returns its id.
/// A price list with channel 'ALL' resolves for every channel (ONLINE storefront
/// included), so one default list is enough to make products sellable.
final defaultPriceListProvider = FutureProvider.autoDispose<String>((ref) async {
  final dio = ref.read(apiClientProvider).dio;
  final lists =
      await fetchAllPages(dio, '/${ApiConstants.pricing}/price-lists');

  Map<String, dynamic>? chosen;
  for (final l in lists) {
    final m = l as Map<String, dynamic>;
    final active = m['active'] as bool? ?? true;
    final channel = m['channel'] as String?;
    if (active && (channel == 'ALL' || channel == null)) {
      chosen = m;
      break;
    }
  }
  chosen ??= lists.isNotEmpty ? lists.first as Map<String, dynamic> : null;
  if (chosen != null) return chosen['id'] as String;

  // None yet — create the tenant's default price list.
  final tenant = await ref.watch(tenantInfoProvider.future);
  final created = await dio.post(
    '/${ApiConstants.pricing}/price-lists',
    data: {
      'name': 'Default',
      'channel': 'ALL',
      'currency': tenant.currency,
      'effectiveFrom': DateTime.now().toUtc().toIso8601String(),
    },
  );
  return created.data['data']['id'] as String;
});

/// Map of variantId → selling price from the default price list.
final variantPricesProvider =
    FutureProvider.autoDispose<Map<String, double>>((ref) async {
  final listId = await ref.watch(defaultPriceListProvider.future);
  final dio = ref.read(apiClientProvider).dio;
  final resp =
      await dio.get('/${ApiConstants.pricing}/price-lists/$listId/items');
  final items = (resp.data['data'] as List?) ?? [];
  final map = <String, double>{};
  for (final it in items) {
    final m = it as Map<String, dynamic>;
    final vid = m['variantId'] as String?;
    final price = (m['price'] as num?)?.toDouble();
    if (vid != null && price != null) map[vid] = price;
  }
  return map;
});

class ShortageAlert {
  final String storeId;
  final String variantId;
  final double available;
  final double threshold;
  final String alertedAt;

  const ShortageAlert({
    required this.storeId,
    required this.variantId,
    required this.available,
    required this.threshold,
    required this.alertedAt,
  });

  factory ShortageAlert.fromJson(Map<String, dynamic> j) => ShortageAlert(
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        available: (j['available'] as num?)?.toDouble() ?? 0,
        threshold: (j['threshold'] as num?)?.toDouble() ?? 0,
        alertedAt: j['alertedAt'] as String? ?? '',
      );
}

/// Low-stock shortage alerts from notification-svc.
final shortageAlertsProvider =
    FutureProvider.autoDispose<List<ShortageAlert>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.notification}/admin/notifications/shortage-alerts',
      queryParameters: {'limit': 20});
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => ShortageAlert.fromJson(e as Map<String, dynamic>)).toList();
});

/// On-hand inventory report from reporting-svc.
final onHandReportProvider = FutureProvider.autoDispose<List<OnHandRow>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.reporting}/admin/reports/inventory/on-hand');
  final rows = (resp.data['data']?['rows'] as List?) ?? [];
  return rows.map((e) => OnHandRow.fromJson(e as Map<String, dynamic>)).toList();
});

// ── Order detail / returns ───────────────────────────────────────────────────

class OrderLine {
  final String variantId;
  final double qty;
  final double unitPrice;
  final double lineTotal;

  const OrderLine({
    required this.variantId,
    required this.qty,
    required this.unitPrice,
    required this.lineTotal,
  });

  factory OrderLine.fromJson(Map<String, dynamic> j) => OrderLine(
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        lineTotal: (j['lineTotal'] as num?)?.toDouble() ?? 0,
      );
}

class OrderDetail {
  final String id;
  final String status;
  final String currency;
  final double total;
  final List<OrderLine> items;

  const OrderDetail({
    required this.id,
    required this.status,
    required this.currency,
    required this.total,
    required this.items,
  });

  factory OrderDetail.fromJson(Map<String, dynamic> j) => OrderDetail(
        id: j['id'] as String? ?? '',
        status: j['status'] as String? ?? '-',
        currency: j['currency'] as String? ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        items: ((j['items'] as List?) ?? [])
            .map((e) => OrderLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Full order with line items (used by the returns dialog).
final orderDetailProvider =
    FutureProvider.autoDispose.family<OrderDetail, String>((ref, orderId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/orders/$orderId');
  return OrderDetail.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class ReturnRecord {
  final String id;
  final String reason;
  final double refundAmount;
  final String refundMethod;
  final String status;
  final String createdAt;

  const ReturnRecord({
    required this.id,
    required this.reason,
    required this.refundAmount,
    required this.refundMethod,
    required this.status,
    required this.createdAt,
  });

  factory ReturnRecord.fromJson(Map<String, dynamic> j) => ReturnRecord(
        id: j['id'] as String? ?? '',
        reason: j['reason'] as String? ?? '',
        refundAmount: (j['refundAmount'] as num?)?.toDouble() ?? 0,
        refundMethod: j['refundMethod'] as String? ?? '',
        status: j['status'] as String? ?? '',
        createdAt: j['createdAt'] as String? ?? '',
      );
}

/// Returns already recorded against an order.
final orderReturnsProvider =
    FutureProvider.autoDispose.family<List<ReturnRecord>, String>((ref, orderId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/orders/$orderId/returns');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => ReturnRecord.fromJson(e as Map<String, dynamic>)).toList();
});

class NettingRow {
  final String storeId;
  final String variantId;
  final double onHand;
  final double supplyInTransit;
  final double netAvailable;

  const NettingRow({
    required this.storeId,
    required this.variantId,
    required this.onHand,
    required this.supplyInTransit,
    required this.netAvailable,
  });

  factory NettingRow.fromJson(Map<String, dynamic> j) => NettingRow(
        storeId: j['storeId'] as String? ?? '-',
        variantId: j['variantId'] as String? ?? '-',
        onHand: (j['onHand'] as num?)?.toDouble() ?? 0,
        supplyInTransit: (j['supplyInTransit'] as num?)?.toDouble() ?? 0,
        netAvailable: (j['netAvailable'] as num?)?.toDouble() ?? 0,
      );
}

/// Supply/demand netting report (on-hand + in-transit → net available).
final supplyDemandReportProvider =
    FutureProvider.autoDispose<List<NettingRow>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.reporting}/admin/reports/inventory/supply-demand');
  final rows = (resp.data['data']?['rows'] as List?) ?? [];
  return rows.map((e) => NettingRow.fromJson(e as Map<String, dynamic>)).toList();
});

class MovementStatRow {
  final String storeId;
  final String variantId;
  final String bucket;
  final double totalIn;
  final double totalOut;
  final double net;

  const MovementStatRow({
    required this.storeId,
    required this.variantId,
    required this.bucket,
    required this.totalIn,
    required this.totalOut,
    required this.net,
  });

  factory MovementStatRow.fromJson(Map<String, dynamic> j) => MovementStatRow(
        storeId: j['storeId'] as String? ?? '-',
        variantId: j['variantId'] as String? ?? '-',
        bucket: j['bucket'] as String? ?? '-',
        totalIn: (j['totalIn'] as num?)?.toDouble() ?? 0,
        totalOut: (j['totalOut'] as num?)?.toDouble() ?? 0,
        net: (j['net'] as num?)?.toDouble() ?? 0,
      );
}

/// Movement statistics report (in/out/net per time bucket).
final movementStatsReportProvider =
    FutureProvider.autoDispose<List<MovementStatRow>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.reporting}/admin/reports/inventory/movement-stats');
  final rows = (resp.data['data']?['rows'] as List?) ?? [];
  return rows.map((e) => MovementStatRow.fromJson(e as Map<String, dynamic>)).toList();
});

/// One currency's sales totals (gross/refunded/net + order count) — from reporting-svc's
/// order/payment projection (N4).
class SalesSummaryRow {
  final String currency;
  final int orders;
  final double gross;
  final double refunded;
  final double net;

  const SalesSummaryRow({
    required this.currency,
    required this.orders,
    required this.gross,
    required this.refunded,
    required this.net,
  });

  factory SalesSummaryRow.fromJson(Map<String, dynamic> j) => SalesSummaryRow(
        currency: j['currency'] as String? ?? '-',
        orders: (j['orders'] as num?)?.toInt() ?? 0,
        gross: (j['gross'] as num?)?.toDouble() ?? 0,
        refunded: (j['refunded'] as num?)?.toDouble() ?? 0,
        net: (j['net'] as num?)?.toDouble() ?? 0,
      );
}

/// Daily sales bucket (reporting-svc sales/by-day).
class SalesDayRow {
  final String day;
  final String currency;
  final int orders;
  final double gross;
  final double refunded;
  final double net;

  const SalesDayRow({
    required this.day,
    required this.currency,
    required this.orders,
    required this.gross,
    required this.refunded,
    required this.net,
  });

  factory SalesDayRow.fromJson(Map<String, dynamic> j) => SalesDayRow(
        day: j['day'] as String? ?? '-',
        currency: j['currency'] as String? ?? '-',
        orders: (j['orders'] as num?)?.toInt() ?? 0,
        gross: (j['gross'] as num?)?.toDouble() ?? 0,
        refunded: (j['refunded'] as num?)?.toDouble() ?? 0,
        net: (j['net'] as num?)?.toDouble() ?? 0,
      );
}

/// Inclusive calendar date range (yyyy-MM-dd) for sales reports.
class ReportDateRange {
  final String? from;
  final String? to;

  const ReportDateRange({this.from, this.to});

  ReportDateRange copyWith({String? from, String? to}) =>
      ReportDateRange(from: from ?? this.from, to: to ?? this.to);

  @override
  bool operator ==(Object other) =>
      other is ReportDateRange && other.from == from && other.to == to;

  @override
  int get hashCode => Object.hash(from, to);
}

String yyyyMmDd(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

/// Default last 30 days; ReportsScreen mutates this to re-fetch sales reports.
final reportDateRangeProvider = StateProvider<ReportDateRange>((ref) {
  final now = DateTime.now();
  return ReportDateRange(
    from: yyyyMmDd(now.subtract(const Duration(days: 30))),
    to: yyyyMmDd(now),
  );
});

/// Sales revenue report grouped by currency, optional from/to (yyyy-MM-dd).
final salesSummaryReportProvider =
    FutureProvider.autoDispose<List<SalesSummaryRow>>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final params = <String, dynamic>{};
  if (range.from != null && range.from!.isNotEmpty) params['from'] = range.from;
  if (range.to != null && range.to!.isNotEmpty) params['to'] = range.to;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.reporting}/admin/reports/sales/summary',
        queryParameters: params.isEmpty ? null : params,
      );
  final rows = (resp.data['data']?['rows'] as List?) ?? [];
  return rows
      .map((e) => SalesSummaryRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Daily sales revenue buckets, newest day first.
final salesByDayReportProvider =
    FutureProvider.autoDispose<List<SalesDayRow>>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final params = <String, dynamic>{};
  if (range.from != null && range.from!.isNotEmpty) params['from'] = range.from;
  if (range.to != null && range.to!.isNotEmpty) params['to'] = range.to;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.reporting}/admin/reports/sales/by-day',
        queryParameters: params.isEmpty ? null : params,
      );
  final rows = (resp.data['data']?['rows'] as List?) ?? [];
  return rows.map((e) => SalesDayRow.fromJson(e as Map<String, dynamic>)).toList();
});

/// Delivery area (pincode coverage) for a store.
class DeliveryArea {
  final String id;
  final String storeId;
  final String pincode;
  final int priority;
  final String createdAt;

  const DeliveryArea({
    required this.id,
    required this.storeId,
    required this.pincode,
    required this.priority,
    required this.createdAt,
  });

  factory DeliveryArea.fromJson(Map<String, dynamic> j) => DeliveryArea(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        pincode: j['pincode'] as String? ?? '',
        priority: (j['priority'] as num?)?.toInt() ?? 0,
        createdAt: j['createdAt'] as String? ?? '',
      );
}

/// Pincodes a store fulfils for home delivery.
final deliveryAreasProvider =
    FutureProvider.autoDispose.family<List<DeliveryArea>, String>((ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.tenant}/admin/stores/$storeId/delivery-areas');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => DeliveryArea.fromJson(e as Map<String, dynamic>)).toList();
});

// ── Shrinkage · valuation · low stock · tax summary ──────────────────────────
//
// These four reports were built server-side and had no client at all: the admin
// app called reports/inventory and reports/sales and nothing else, so an
// endpoint a developer could curl was being counted as a report a store manager
// had. Everything below is the missing half.

/// The date pickers speak `yyyy-MM-dd`. inventory-svc and pricing-svc parse the
/// `from`/`to` params on these reports with `Instant.parse`, which rejects a bare
/// date outright — the exact mismatch SJ-D9 was about, on the other side of the
/// wire. So widen a day to the instant that opens it.
String? _dayStartInstant(String? day) =>
    (day == null || day.isEmpty) ? null : '${day}T00:00:00Z';

/// …and `to` to the instant that *opens the next* day, because every report
/// compares it with `<` rather than `<=`.
///
/// It used to send `T23:59:59Z`, which is a whole second short of closing the
/// day: against an exclusive bound that silently dropped anything logged in the
/// last second, and it disagreed with the one report that compared inclusively,
/// so a single screen could show a numerator and a denominator measured over
/// different windows. Sending the start of the next day makes the window a true
/// half-open interval — no lost second, no double-counted boundary — and the
/// exception report's four aggregates were changed to `<` to match everything
/// else rather than the other way round.
///
/// Sending `T00:00:00Z` of the *same* day would be the original bug in reverse:
/// "to today" would exclude everything that happened today.
String? _dayEndInstant(String? day) {
  if (day == null || day.isEmpty) return null;
  final next = DateTime.parse('${day}T00:00:00Z').add(const Duration(days: 1));
  return next.toIso8601String().replaceFirst('.000Z', 'Z');
}

/// One line of the shrinkage report. [groupKey] is a reason code, an actor id or
/// a store id depending on the grouping; `UNSPECIFIED` means an adjustment made
/// with no reason code and `SYSTEM` one with no human actor.
class ShrinkageRow {
  final String groupKey;
  final double qtyWrittenOff;
  final double qtyFound;
  final double netQty;
  final int movements;

  const ShrinkageRow({
    required this.groupKey,
    required this.qtyWrittenOff,
    required this.qtyFound,
    required this.netQty,
    required this.movements,
  });

  factory ShrinkageRow.fromJson(Map<String, dynamic> j) => ShrinkageRow(
        groupKey: j['groupKey'] as String? ?? '-',
        qtyWrittenOff: (j['qtyWrittenOff'] as num?)?.toDouble() ?? 0,
        qtyFound: (j['qtyFound'] as num?)?.toDouble() ?? 0,
        netQty: (j['netQty'] as num?)?.toDouble() ?? 0,
        movements: (j['movements'] as num?)?.toInt() ?? 0,
      );
}

/// REASON (what stock is lost to) · ACTOR (who is writing it off) · STORE.
final shrinkageGroupingProvider = StateProvider<String>((ref) => 'REASON');

final shrinkageReportProvider =
    FutureProvider.autoDispose<List<ShrinkageRow>>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final params = <String, dynamic>{'groupBy': ref.watch(shrinkageGroupingProvider)};
  final from = _dayStartInstant(range.from);
  final to = _dayEndInstant(range.to);
  if (from != null) params['from'] = from;
  if (to != null) params['to'] = to;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.inventory}/admin/inventory/reports/shrinkage',
        queryParameters: params,
      );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => ShrinkageRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// One line of the stock valuation report. [unvaluedQty] is stock carrying no
/// cost — reported separately rather than valued at zero, which would understate
/// the holding.
class ValuationRow {
  final String groupKey;
  final String method;
  final double onHandQty;
  final double unvaluedQty;
  final double value;

  const ValuationRow({
    required this.groupKey,
    required this.method,
    required this.onHandQty,
    required this.unvaluedQty,
    required this.value,
  });

  factory ValuationRow.fromJson(Map<String, dynamic> j) => ValuationRow(
        groupKey: j['groupKey'] as String? ?? '-',
        method: j['method'] as String? ?? '-',
        onHandQty: (j['onHandQty'] as num?)?.toDouble() ?? 0,
        unvaluedQty: (j['unvaluedQty'] as num?)?.toDouble() ?? 0,
        value: (j['value'] as num?)?.toDouble() ?? 0,
      );
}

/// STORE ("what is our stock worth") before VARIANT (line level).
final valuationGroupingProvider = StateProvider<String>((ref) => 'STORE');

final valuationReportProvider =
    FutureProvider.autoDispose<List<ValuationRow>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}/admin/inventory/reports/valuation',
    queryParameters: {'groupBy': ref.watch(valuationGroupingProvider), 'limit': 200},
  );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => ValuationRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// One line of the low-stock report. [signal] names which configured level bound
/// this row — THRESHOLD, SAFETY_STOCK or REORDER_POINT — so a manager can see
/// *why* an item is flagged, not just that it is.
class LowStockRow {
  final String storeId;
  final String variantId;
  final String signal;
  final double reorderLevel;
  final double availableQty;
  final double shortfall;

  const LowStockRow({
    required this.storeId,
    required this.variantId,
    required this.signal,
    required this.reorderLevel,
    required this.availableQty,
    required this.shortfall,
  });

  factory LowStockRow.fromJson(Map<String, dynamic> j) => LowStockRow(
        storeId: j['storeId'] as String? ?? '-',
        variantId: j['variantId'] as String? ?? '-',
        signal: j['signal'] as String? ?? '-',
        reorderLevel: (j['reorderLevel'] as num?)?.toDouble() ?? 0,
        availableQty: (j['availableQty'] as num?)?.toDouble() ?? 0,
        shortfall: (j['shortfall'] as num?)?.toDouble() ?? 0,
      );
}

final lowStockReportProvider =
    FutureProvider.autoDispose<List<LowStockRow>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.inventory}/admin/inventory/reports/low-stock',
        queryParameters: {'limit': 200},
      );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => LowStockRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// One line of the tax summary. Exempt lines are kept separate because the VAT
/// return counts their net in Box 6 but their VAT in no box at all.
class TaxSummaryRow {
  final String groupKey;
  final bool exempt;
  final double netAmount;
  final double vatAmount;
  final double grossAmount;
  final int transactions;

  const TaxSummaryRow({
    required this.groupKey,
    required this.exempt,
    required this.netAmount,
    required this.vatAmount,
    required this.grossAmount,
    required this.transactions,
  });

  factory TaxSummaryRow.fromJson(Map<String, dynamic> j) => TaxSummaryRow(
        groupKey: j['groupKey'] as String? ?? '-',
        exempt: j['exempt'] as bool? ?? false,
        netAmount: (j['netAmount'] as num?)?.toDouble() ?? 0,
        vatAmount: (j['vatAmount'] as num?)?.toDouble() ?? 0,
        grossAmount: (j['grossAmount'] as num?)?.toDouble() ?? 0,
        transactions: (j['transactions'] as num?)?.toInt() ?? 0,
      );
}

/// [outputVat] is VAT on taxable lines only and ties to VAT return Box 1;
/// [vatAmount] includes exempt lines. The two differing means a line marked
/// exempt is carrying VAT, which is a data fault worth surfacing rather than
/// hiding — see [TaxSummaryReport.boxOneDisagrees].
class TaxSummaryTotals {
  final double netAmount;
  final double vatAmount;
  final double outputVat;
  final double grossAmount;
  final int transactions;

  const TaxSummaryTotals({
    required this.netAmount,
    required this.vatAmount,
    required this.outputVat,
    required this.grossAmount,
    required this.transactions,
  });

  factory TaxSummaryTotals.fromJson(Map<String, dynamic> j) => TaxSummaryTotals(
        netAmount: (j['netAmount'] as num?)?.toDouble() ?? 0,
        vatAmount: (j['vatAmount'] as num?)?.toDouble() ?? 0,
        outputVat: (j['outputVat'] as num?)?.toDouble() ?? 0,
        grossAmount: (j['grossAmount'] as num?)?.toDouble() ?? 0,
        transactions: (j['transactions'] as num?)?.toInt() ?? 0,
      );
}

class TaxSummaryReport {
  final List<TaxSummaryRow> rows;
  final TaxSummaryTotals totals;
  final String? periodFrom;
  final String? periodTo;

  const TaxSummaryReport({
    required this.rows,
    required this.totals,
    this.periodFrom,
    this.periodTo,
  });

  /// True when Box 1 and total VAT disagree — an exempt line is carrying VAT.
  /// The server's own DTO calls this out as "a data fault the Box 1 query drops
  /// silently"; silent is exactly what it must not be on a screen someone files
  /// a return from.
  bool get boxOneDisagrees =>
      (totals.vatAmount - totals.outputVat).abs() > 0.005;

  factory TaxSummaryReport.fromJson(Map<String, dynamic> j) => TaxSummaryReport(
        rows: ((j['rows'] as List?) ?? [])
            .map((e) => TaxSummaryRow.fromJson(e as Map<String, dynamic>))
            .toList(),
        totals: TaxSummaryTotals.fromJson(
            Map<String, dynamic>.from((j['totals'] as Map?) ?? const {})),
        periodFrom: j['periodFrom'] as String?,
        periodTo: j['periodTo'] as String?,
      );
}

/// CODE (by VAT rate band) · STORE · MONTH.
final taxGroupingProvider = StateProvider<String>((ref) => 'CODE');

/// Unlike the other three, `from` and `to` are required here — pricing-svc
/// rejects the call without them — so the date bar is not optional on this one.
final taxSummaryReportProvider =
    FutureProvider.autoDispose<TaxSummaryReport>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.pricing}/admin/reports/tax-summary',
    queryParameters: {
      'from': _dayStartInstant(range.from),
      'to': _dayEndInstant(range.to),
      'groupBy': ref.watch(taxGroupingProvider),
    },
  );
  return TaxSummaryReport.fromJson(
      Map<String, dynamic>.from(resp.data['data'] as Map));
});

// ── Staff exception report ───────────────────────────────────────────────────

/// One group's staff-initiated exceptions. [sales] is the denominator from the
/// POS transaction journal — see [ExceptionReport.journalCoverage] before
/// reading any rate off it.
class ExceptionRow {
  final String groupKey;
  final int discounts;
  final double discountAmount;
  final int voids;
  final int noSales;
  final int sales;
  final double salesValue;

  const ExceptionRow({
    required this.groupKey,
    required this.discounts,
    required this.discountAmount,
    required this.voids,
    required this.noSales,
    required this.sales,
    required this.salesValue,
  });

  int get totalExceptions => discounts + voids + noSales;

  /// Exceptions per hundred sales, or null when there is no denominator to
  /// divide by. Null is the honest answer: zero would read as "well behaved".
  double? get ratePerHundredSales =>
      sales == 0 ? null : (totalExceptions * 100) / sales;

  factory ExceptionRow.fromJson(Map<String, dynamic> j) => ExceptionRow(
        groupKey: j['groupKey'] as String? ?? '-',
        discounts: (j['discounts'] as num?)?.toInt() ?? 0,
        discountAmount: (j['discountAmount'] as num?)?.toDouble() ?? 0,
        voids: (j['voids'] as num?)?.toInt() ?? 0,
        noSales: (j['noSales'] as num?)?.toInt() ?? 0,
        sales: (j['sales'] as num?)?.toInt() ?? 0,
        salesValue: (j['salesValue'] as num?)?.toDouble() ?? 0,
      );
}

class ExceptionReport {
  final List<ExceptionRow> rows;

  /// False when nothing journalled a sale in the period, so every [ExceptionRow.sales]
  /// is zero for want of data rather than want of selling. The screen says so
  /// instead of showing rates computed from nothing.
  final bool journalCoverage;

  const ExceptionReport({required this.rows, required this.journalCoverage});

  factory ExceptionReport.fromJson(Map<String, dynamic> j) => ExceptionReport(
        rows: ((j['rows'] as List?) ?? [])
            .map((e) => ExceptionRow.fromJson(e as Map<String, dynamic>))
            .toList(),
        journalCoverage: j['journalCoverage'] as bool? ?? false,
      );
}

/// ACTOR (which member of staff) · STORE.
final exceptionGroupingProvider = StateProvider<String>((ref) => 'ACTOR');

final exceptionReportProvider =
    FutureProvider.autoDispose<ExceptionReport>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final params = <String, dynamic>{'groupBy': ref.watch(exceptionGroupingProvider)};
  final from = _dayStartInstant(range.from);
  final to = _dayEndInstant(range.to);
  if (from != null) params['from'] = from;
  if (to != null) params['to'] = to;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/reports/exceptions',
        queryParameters: params,
      );
  return ExceptionReport.fromJson(
      Map<String, dynamic>.from(resp.data['data'] as Map));
});

// ── Stock turn ───────────────────────────────────────────────────────────────

/// One line of the stock-turn report. [turnoverRatio] and [daysOnHand] are null
/// when the group held nothing to turn — which is not the same as turning it
/// zero times, so the screen shows a dash rather than a 0.
class StockTurnRow {
  final String groupKey;
  final double cogs;
  final double uncostedSaleQty;
  final double openingValue;
  final double closingValue;
  final double averageValue;
  final double? turnoverRatio;
  final double? daysOnHand;

  const StockTurnRow({
    required this.groupKey,
    required this.cogs,
    required this.uncostedSaleQty,
    required this.openingValue,
    required this.closingValue,
    required this.averageValue,
    this.turnoverRatio,
    this.daysOnHand,
  });

  factory StockTurnRow.fromJson(Map<String, dynamic> j) => StockTurnRow(
        groupKey: j['groupKey'] as String? ?? '-',
        cogs: (j['cogs'] as num?)?.toDouble() ?? 0,
        uncostedSaleQty: (j['uncostedSaleQty'] as num?)?.toDouble() ?? 0,
        openingValue: (j['openingValue'] as num?)?.toDouble() ?? 0,
        closingValue: (j['closingValue'] as num?)?.toDouble() ?? 0,
        averageValue: (j['averageValue'] as num?)?.toDouble() ?? 0,
        turnoverRatio: (j['turnoverRatio'] as num?)?.toDouble(),
        daysOnHand: (j['daysOnHand'] as num?)?.toDouble(),
      );
}

class StockTurnReport {
  final List<StockTurnRow> rows;

  /// False when the movement ledger was purged past the start of the window, so
  /// every opening value is a floor rather than a figure. The screen says so.
  final bool historyComplete;
  final int windowDays;

  const StockTurnReport({
    required this.rows,
    required this.historyComplete,
    required this.windowDays,
  });

  factory StockTurnReport.fromJson(Map<String, dynamic> j) => StockTurnReport(
        rows: ((j['rows'] as List?) ?? [])
            .map((e) => StockTurnRow.fromJson(e as Map<String, dynamic>))
            .toList(),
        historyComplete: j['historyComplete'] as bool? ?? true,
        windowDays: (j['windowDays'] as num?)?.toInt() ?? 0,
      );
}

/// STORE (compare sites) · VARIANT (find the slow lines).
final stockTurnGroupingProvider = StateProvider<String>((ref) => 'STORE');

/// Stock turn. Unlike the other period reports, from/to are REQUIRED by the
/// endpoint — a turnover ratio has no meaning without a window and daysOnHand
/// divides by its length — so the date range falls back to the provider default
/// rather than being omitted.
final stockTurnReportProvider =
    FutureProvider.autoDispose<StockTurnReport>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}/admin/inventory/reports/stock-turn',
    queryParameters: {
      'from': _dayStartInstant(range.from),
      'to': _dayEndInstant(range.to),
      'groupBy': ref.watch(stockTurnGroupingProvider),
    },
  );
  return StockTurnReport.fromJson(
      Map<String, dynamic>.from(resp.data['data'] as Map));
});

// ── Dead stock ───────────────────────────────────────────────────────────────

/// One line of the dead-stock ageing report. [neverSold] means the age is
/// measured from receipt because the line has never sold at all.
class DeadStockRow {
  final String groupKey;
  final double onHandQty;
  final double value;
  final double uncostedQty;
  final int? daysSinceLastSale;
  final bool neverSold;

  const DeadStockRow({
    required this.groupKey,
    required this.onHandQty,
    required this.value,
    required this.uncostedQty,
    this.daysSinceLastSale,
    required this.neverSold,
  });

  factory DeadStockRow.fromJson(Map<String, dynamic> j) => DeadStockRow(
        groupKey: j['groupKey'] as String? ?? '-',
        onHandQty: (j['onHandQty'] as num?)?.toDouble() ?? 0,
        value: (j['value'] as num?)?.toDouble() ?? 0,
        uncostedQty: (j['uncostedQty'] as num?)?.toDouble() ?? 0,
        daysSinceLastSale: (j['daysSinceLastSale'] as num?)?.toInt(),
        neverSold: j['neverSold'] as bool? ?? false,
      );
}

/// BUCKET (the ageing ladder) · STORE · VARIANT.
final deadStockGroupingProvider = StateProvider<String>((ref) => 'BUCKET');

/// Dead stock takes no date range — it is a question about now, not a period.
final deadStockReportProvider =
    FutureProvider.autoDispose<List<DeadStockRow>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}/admin/inventory/reports/dead-stock',
    queryParameters: {'groupBy': ref.watch(deadStockGroupingProvider)},
  );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => DeadStockRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

// ── Tender mix ───────────────────────────────────────────────────────────────

/// One payment method's share of the take. [shareOfNet] is null when the
/// window's total is zero or negative, where a percentage means nothing.
class TenderMixRow {
  final String method;
  final double capturedAmount;
  final int capturedCount;
  final double refundedAmount;
  final int refundedCount;
  final int failedCount;
  final double netAmount;
  final double? shareOfNet;

  const TenderMixRow({
    required this.method,
    required this.capturedAmount,
    required this.capturedCount,
    required this.refundedAmount,
    required this.refundedCount,
    required this.failedCount,
    required this.netAmount,
    this.shareOfNet,
  });

  factory TenderMixRow.fromJson(Map<String, dynamic> j) => TenderMixRow(
        method: j['method'] as String? ?? '-',
        capturedAmount: (j['capturedAmount'] as num?)?.toDouble() ?? 0,
        capturedCount: (j['capturedCount'] as num?)?.toInt() ?? 0,
        refundedAmount: (j['refundedAmount'] as num?)?.toDouble() ?? 0,
        refundedCount: (j['refundedCount'] as num?)?.toInt() ?? 0,
        failedCount: (j['failedCount'] as num?)?.toInt() ?? 0,
        netAmount: (j['netAmount'] as num?)?.toDouble() ?? 0,
        shareOfNet: (j['shareOfNet'] as num?)?.toDouble(),
      );
}

final tenderMixReportProvider =
    FutureProvider.autoDispose<List<TenderMixRow>>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final params = <String, dynamic>{};
  final from = _dayStartInstant(range.from);
  final to = _dayEndInstant(range.to);
  if (from != null) params['from'] = from;
  if (to != null) params['to'] = to;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.payment}/admin/reports/tender-mix',
        queryParameters: params,
      );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => TenderMixRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

// ── Sales by hour ────────────────────────────────────────────────────────────

/// One hour of the trading day, on the clock of the timezone asked for.
class SalesByHourRow {
  final int hourOfDay;
  final int orders;
  final double grossAmount;
  final double discountAmount;
  final double averageBasket;

  const SalesByHourRow({
    required this.hourOfDay,
    required this.orders,
    required this.grossAmount,
    required this.discountAmount,
    required this.averageBasket,
  });

  factory SalesByHourRow.fromJson(Map<String, dynamic> j) => SalesByHourRow(
        hourOfDay: (j['hourOfDay'] as num?)?.toInt() ?? 0,
        orders: (j['orders'] as num?)?.toInt() ?? 0,
        grossAmount: (j['grossAmount'] as num?)?.toDouble() ?? 0,
        discountAmount: (j['discountAmount'] as num?)?.toDouble() ?? 0,
        averageBasket: (j['averageBasket'] as num?)?.toDouble() ?? 0,
      );
}

/// Empty string means both channels; otherwise ONLINE or POS.
final salesByHourChannelProvider = StateProvider<String>((ref) => '');

final salesByHourReportProvider =
    FutureProvider.autoDispose<List<SalesByHourRow>>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final channel = ref.watch(salesByHourChannelProvider);
  final params = <String, dynamic>{
    // The browser's own zone, so "when are we busy" is answered on the clock
    // the manager reads. Without it the server buckets in UTC and a shop
    // outside it is told its peak is at the wrong time of day.
    'tz': _localZoneId(),
  };
  final from = _dayStartInstant(range.from);
  final to = _dayEndInstant(range.to);
  if (from != null) params['from'] = from;
  if (to != null) params['to'] = to;
  if (channel.isNotEmpty) params['channel'] = channel;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/reports/sales-by-hour',
        queryParameters: params,
      );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => SalesByHourRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Dart has no way to read the host's IANA zone name, and the server needs a
/// zone to do the conversion. A fixed offset is the honest fallback: it is
/// exactly right for the window being viewed and makes no claim about a DST
/// boundary it cannot know where to put.
///
/// The form matters. `+05:30` is an ISO offset and means five and a half hours
/// *ahead* of UTC to both Java's ZoneId and Postgres's AT TIME ZONE. Writing it
/// as `UTC+05:30` would be read by Postgres under the POSIX convention, where
/// the sign is inverted — Java would validate it happily and every hour would
/// come back on the wrong side of UTC. A test pins that this form agrees with a
/// named zone at the same offset.
String _localZoneId() {
  final minutes = DateTime.now().timeZoneOffset.inMinutes;
  if (minutes == 0) return 'UTC';
  final sign = minutes < 0 ? '-' : '+';
  final abs = minutes.abs();
  final hh = (abs ~/ 60).toString().padLeft(2, '0');
  final mm = (abs % 60).toString().padLeft(2, '0');
  return '$sign$hh:$mm';
}

// ── Sales by staff ───────────────────────────────────────────────────────────

/// One cashier's takings from the POS transaction journal. In-store only — an
/// online order has no cashier, so these will not add up to the sales summary.
class SalesByStaffRow {
  final String groupKey;
  final int sales;
  final double grossAmount;
  final double discountAmount;
  final double? averageBasket;
  final double? discountRate;

  const SalesByStaffRow({
    required this.groupKey,
    required this.sales,
    required this.grossAmount,
    required this.discountAmount,
    this.averageBasket,
    this.discountRate,
  });

  factory SalesByStaffRow.fromJson(Map<String, dynamic> j) => SalesByStaffRow(
        groupKey: j['groupKey'] as String? ?? '-',
        sales: (j['sales'] as num?)?.toInt() ?? 0,
        grossAmount: (j['grossAmount'] as num?)?.toDouble() ?? 0,
        discountAmount: (j['discountAmount'] as num?)?.toDouble() ?? 0,
        averageBasket: (j['averageBasket'] as num?)?.toDouble(),
        discountRate: (j['discountRate'] as num?)?.toDouble(),
      );
}

final salesByStaffReportProvider =
    FutureProvider.autoDispose<List<SalesByStaffRow>>((ref) async {
  final range = ref.watch(reportDateRangeProvider);
  final params = <String, dynamic>{};
  final from = _dayStartInstant(range.from);
  final to = _dayEndInstant(range.to);
  if (from != null) params['from'] = from;
  if (to != null) params['to'] = to;
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/reports/sales-by-staff',
        queryParameters: params,
      );
  final rows = (resp.data['data'] as List?) ?? [];
  return rows
      .map((e) => SalesByStaffRow.fromJson(e as Map<String, dynamic>))
      .toList();
});
