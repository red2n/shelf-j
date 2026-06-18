import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';

// ── Models ──────────────────────────────────────────────────────────────────

class OrderSummary {
  final String id;
  final String channel;
  final String status;
  final double total;
  final String currency;
  final String createdAt;

  const OrderSummary({
    required this.id,
    required this.channel,
    required this.status,
    required this.total,
    required this.currency,
    required this.createdAt,
  });

  factory OrderSummary.fromJson(Map<String, dynamic> j) => OrderSummary(
        id: j['id'] as String? ?? '',
        channel: j['channel'] as String? ?? '-',
        status: j['status'] as String? ?? '-',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? 'INR',
        createdAt: j['createdAt'] as String? ?? '',
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

  bool get isLow => available <= 5;
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

/// Orders with optional channel filter ('ALL', 'POS', 'ONLINE').
final ordersProvider =
    FutureProvider.autoDispose.family<List<OrderSummary>, String>((ref, channel) async {
  final params = <String, dynamic>{'limit': 50};
  if (channel != 'ALL') params['channel'] = channel;
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.order}/orders',
    queryParameters: params,
  );
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList();
});

/// All inventory levels for the tenant (across all stores).
final inventoryLevelsProvider = FutureProvider.autoDispose<List<InventoryLevel>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.inventory}/admin/inventory/levels');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => InventoryLevel.fromJson(e as Map<String, dynamic>)).toList();
});

/// Current tenant info (name, currency, status).
final tenantInfoProvider = FutureProvider.autoDispose<TenantInfo>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/tenant');
  return TenantInfo.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// All stores for the tenant.
final storesProvider = FutureProvider.autoDispose<List<StoreInfo>>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/stores');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => StoreInfo.fromJson(e as Map<String, dynamic>)).toList();
});

/// Zones (aisles/racks) within a store. Stock batches live in a (store, zone).
final zonesProvider =
    FutureProvider.autoDispose.family<List<ZoneInfo>, String>((ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.tenant}/admin/stores/$storeId/zones');
  final data = (resp.data['data'] as List?) ?? [];
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

/// All staff assignments for the tenant (GET /tenant-svc/admin/staff).
final staffProvider = FutureProvider.autoDispose<List<StaffMember>>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/staff');
  final data = (resp.data['data'] as List?) ?? [];
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
  final resp = await dio.get('/${ApiConstants.pricing}/price-lists');
  final lists = (resp.data['data'] as List?) ?? [];

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
