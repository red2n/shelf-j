import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';

/// A single scanned line on the POS sale.
class PosLine {
  final String variantId;
  final String sku;
  final String name;
  final int qty;
  final double unitPrice;
  final String currency;

  const PosLine({
    required this.variantId,
    required this.sku,
    required this.name,
    required this.qty,
    required this.unitPrice,
    required this.currency,
  });

  double get lineTotal => qty * unitPrice;

  PosLine copyWith({int? qty}) => PosLine(
        variantId: variantId,
        sku: sku,
        name: name,
        qty: qty ?? this.qty,
        unitPrice: unitPrice,
        currency: currency,
      );
}

/// The store the POS terminal is operating in. Defaults to the tenant's first
/// store; the cashier can switch it from the cart screen.
final posStoreProvider = StateProvider<String?>((ref) => null);

/// Holds the in-progress POS sale (client-side until tendered).
class PosCartNotifier extends StateNotifier<List<PosLine>> {
  PosCartNotifier() : super(const []);

  void addOrIncrement(PosLine line) {
    final idx = state.indexWhere((l) => l.variantId == line.variantId);
    if (idx >= 0) {
      final existing = state[idx];
      final updated = [...state];
      updated[idx] = existing.copyWith(qty: existing.qty + line.qty);
      state = updated;
    } else {
      state = [...state, line];
    }
  }

  void setQty(String variantId, int qty) {
    if (qty <= 0) {
      state = state.where((l) => l.variantId != variantId).toList();
      return;
    }
    state = [
      for (final l in state)
        if (l.variantId == variantId) l.copyWith(qty: qty) else l,
    ];
  }

  void clear() => state = const [];

  /// Replace the cart contents (used when resuming a parked sale).
  void loadLines(List<PosLine> lines) => state = lines;

  double get total => state.fold(0.0, (s, l) => s + l.lineTotal);
}

final posCartProvider =
    StateNotifierProvider<PosCartNotifier, List<PosLine>>((ref) => PosCartNotifier());

/// Looks a barcode up in the catalog, resolves its POS price, and returns a
/// ready-to-add line. Throws on not-found / pricing failures so the UI can show
/// a clear message.
Future<PosLine> scanBarcode(WidgetRef ref, String rawCode) async {
  final code = rawCode.trim();
  final dio = ref.read(apiClientProvider).dio;

  // 1. Resolve the barcode/SKU to a catalog variant (one round-trip).
  final scanResp =
      await dio.get('/${ApiConstants.product}/catalog/variants/by-barcode/$code');
  final v = scanResp.data['data'] as Map<String, dynamic>;
  final variantId = v['variantId'] as String? ?? '';
  if (variantId.isEmpty) {
    throw Exception('No product found for "$code"');
  }

  // 2. Resolve the POS price for this variant.
  final priceResp = await dio.post(
    '/${ApiConstants.pricing}/prices/resolve',
    data: {'variantId': variantId, 'channel': 'POS', 'qty': 1},
  );
  final p = priceResp.data['data'] as Map<String, dynamic>;

  return PosLine(
    variantId: variantId,
    sku: v['sku'] as String? ?? code,
    name: v['productName'] as String? ?? (v['sku'] as String? ?? code),
    qty: 1,
    unitPrice: (p['unitPrice'] as num?)?.toDouble() ?? 0,
    currency: p['currency'] as String? ?? 'GBP',
  );
}

/// Stores the cashier can clock in to. Uses the cashier-safe storefront store
/// list (`/tenant-svc/storefront/stores`) — the admin `/admin/stores` list is
/// management-gated, so a plain CASHIER token can't read it. The tenant is taken
/// from the authenticated staff JWT.
final posStoresProvider = FutureProvider.autoDispose<List<StoreInfo>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.tenant}/storefront/stores');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) {
    final m = e as Map<String, dynamic>;
    return StoreInfo(
      id: m['storeId'] as String? ?? '',
      name: m['storeName'] as String? ?? '-',
      code: '',
      type: 'STORE',
      status: m['status'] as String? ?? 'ACTIVE',
      showPrices: m['showPrices'] as bool? ?? true,
      enabledPaymentMethods: (m['enabledPaymentMethods'] as List?)
              ?.map((e) => e.toString().toUpperCase())
              .toList() ??
          const ['CASH', 'CARD'],
      line1: m['line1'] as String?,
      city: m['city'] as String?,
      country: m['country'] as String?,
      pincode: m['pincode'] as String?,
    );
  }).toList();
});

/// Tenders the owner enabled for the till's current store — drives which tender
/// buttons the tender screen offers (gift card / store credit are store-issued
/// instruments and always available). Falls back to CASH+CARD while loading so
/// the till is never left without a tender.
final posEnabledPaymentMethodsProvider = Provider.autoDispose<List<String>>((ref) {
  final storeId = ref.watch(posStoreProvider);
  final stores = ref.watch(posStoresProvider).value;
  if (storeId == null || stores == null) return const ['CASH', 'CARD'];
  for (final s in stores) {
    if (s.id == storeId) return s.enabledPaymentMethods;
  }
  return const ['CASH', 'CARD'];
});

/// POS always shows prices — show_prices is a customer-facing storefront flag only.
/// Staff at the till always need to see and charge the correct price.
final posShowPricesProvider = Provider.autoDispose<bool>((ref) => true);

/// Whether to show only in-stock products on the POS catalog pane.
final posInStockOnlyProvider = StateProvider.autoDispose<bool>((ref) => false);

/// The customer attached to the in-progress sale (null = walk-in). Lets POS
/// attribute the order so loyalty / store-credit can apply.
final posCustomerProvider = StateProvider<Customer?>((ref) => null);

/// Contact phone for walk-in sales (used when no customer account is linked).
/// Cleared automatically when the sale is completed or voided.
final posWalkInPhoneProvider = StateProvider<String>((ref) => '');

/// Order-level discount (absolute amount) applied to the in-progress sale.
final posDiscountProvider = StateProvider<double>((ref) => 0);

/// A single tender (part-payment) staged against the sale before completion.
/// POS supports splitting one sale across several tenders of different methods.
class PosTender {
  final String method; // CASH | CARD | UPI | WALLET | GIFT_CARD | STORE_CREDIT
  final double amount; // amount applied to the balance
  final double cashGiven; // for CASH: what the customer handed over (for change)
  final String? giftCardCode; // for GIFT_CARD
  final String? customerId; // for STORE_CREDIT

  const PosTender({
    required this.method,
    required this.amount,
    this.cashGiven = 0,
    this.giftCardCode,
    this.customerId,
  });

  /// Payment-svc method code (store credit is recorded as a VOUCHER tender).
  String get paymentMethod => method == 'STORE_CREDIT' ? 'VOUCHER' : method;

  String get label => switch (method) {
        'CASH' => 'Cash',
        'CARD' => 'Card',
        'UPI' => 'UPI',
        'WALLET' => 'Wallet',
        'GIFT_CARD' => 'Gift card',
        'STORE_CREDIT' => 'Store credit',
        _ => method,
      };

  double get change => method == 'CASH' && cashGiven > amount
      ? cashGiven - amount
      : 0;
}

/// Looks up a gift card by code; returns (balance, currency, status). Throws on
/// not-found so the tender dialog can show a clear message.
Future<({double balance, String currency, String status})> giftCardLookup(
    WidgetRef ref, String code) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/gift-cards/$code');
  final d = resp.data['data'] as Map<String, dynamic>;
  return (
    balance: (d['currentBalance'] as num?)?.toDouble() ?? 0,
    currency: d['currency'] as String? ?? 'GBP',
    status: d['status'] as String? ?? '',
  );
}

// ── Cashier-safe POS catalog (uses /catalog, not management-gated /admin) ─────

/// Product categories for the till's category filter. Catalog endpoint is
/// reachable by a plain CASHIER token (tenant from the JWT).
final posCategoriesProvider =
    FutureProvider.autoDispose<List<CategoryInfo>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.product}/catalog/categories');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => CategoryInfo.fromJson(e as Map<String, dynamic>)).toList();
});

/// Currently selected category in the till's catalog pane (null = All).
final posSelectedCategoryProvider = StateProvider.autoDispose<String?>((ref) => null);

/// Free-text product search in the till's catalog pane.
final posSearchProvider = StateProvider.autoDispose<String>((ref) => '');

/// Filter key for the till's product grid: free-text query + optional category.
typedef PosCatalogFilter = ({String? categoryId, String query});

/// POS-sellable products for the current store, via the cashier-safe catalog
/// (`channel=POS` → sellable_pos). Server honours q OR category.
final posCatalogProvider = FutureProvider.autoDispose
    .family<List<ProductInfo>, PosCatalogFilter>((ref, f) async {
  final dio = ref.read(apiClientProvider).dio;
  final store = ref.watch(posStoreProvider);
  final q = f.query.trim();
  final params = <String, dynamic>{'channel': 'POS', 'limit': 100};
  if (store != null) params['store'] = store;
  if (q.isNotEmpty) {
    params['q'] = q;
  } else if (f.categoryId != null) {
    params['category'] = f.categoryId;
  }
  final resp = await dio.get('/${ApiConstants.product}/catalog/products',
      queryParameters: params);
  final data = (resp.data['data'] as List?) ?? [];
  var list =
      data.map((e) => ProductInfo.fromJson(e as Map<String, dynamic>)).toList();
  // When both a search term and a category are active, narrow client-side.
  if (q.isNotEmpty && f.categoryId != null) {
    list = list.where((p) => p.categoryId == f.categoryId).toList();
  }
  return list;
});

/// variantId → in-stock at the terminal's store (real inventory). Shared by the
/// grid so each tile shows a live stock badge without an extra call per tile.
final posAvailabilityProvider =
    FutureProvider.autoDispose<Map<String, bool>>((ref) async {
  final store = ref.watch(posStoreProvider);
  if (store == null) return {};
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.inventory}/inventory/availability',
      queryParameters: {'store': store});
  final data = (resp.data['data'] as List?) ?? [];
  return {
    for (final e in data)
      (e['variantId'] as String): (e['inStock'] as bool? ?? false)
  };
});

/// A product's first sellable variant + resolved POS price + live stock, bundled
/// so a grid tile can show price/stock and add to the sale in one tap with no
/// further round-trip. Null when the product has no sellable variant.
class PosOffer {
  final String variantId;
  final String sku;
  final String name;
  final double unitPrice;
  final String currency;
  final bool inStock;

  const PosOffer({
    required this.variantId,
    required this.sku,
    required this.name,
    required this.unitPrice,
    required this.currency,
    required this.inStock,
  });

  PosLine toLine() => PosLine(
        variantId: variantId,
        sku: sku,
        name: name,
        qty: 1,
        unitPrice: unitPrice,
        currency: currency,
      );
}

final posProductOfferProvider = FutureProvider.autoDispose
    .family<PosOffer?, ProductInfo>((ref, product) async {
  final dio = ref.read(apiClientProvider).dio;
  final vResp = await dio
      .get('/${ApiConstants.product}/catalog/products/${product.id}/variants');
  final variants = (vResp.data['data'] as List?) ?? [];
  Map<String, dynamic>? v;
  for (final e in variants) {
    final m = e as Map<String, dynamic>;
    if ((m['status'] as String? ?? 'ACTIVE').toUpperCase() == 'ACTIVE') {
      v = m;
      break;
    }
  }
  if (v == null) return null;
  final variantId = v['id'] as String? ?? '';
  final priceResp = await dio.post('/${ApiConstants.pricing}/prices/resolve',
      data: {'variantId': variantId, 'channel': 'POS', 'qty': 1});
  final p = priceResp.data['data'] as Map<String, dynamic>;
  final avail = await ref.watch(posAvailabilityProvider.future);
  return PosOffer(
    variantId: variantId,
    sku: v['sku'] as String? ?? '',
    name: product.name,
    unitPrice: (p['unitPrice'] as num?)?.toDouble() ?? 0,
    currency: p['currency'] as String? ?? 'GBP',
    inStock: avail[variantId] ?? true,
  );
});

/// Resolve a tap-to-add line for a product (used by the narrow-screen dialog).
/// Throws with a clear message when the product has no sellable variant.
Future<PosLine> lineForProduct(WidgetRef ref, ProductInfo product) async {
  final offer = await ref.read(posProductOfferProvider(product).future);
  if (offer == null) {
    throw Exception('This product has no sellable variant.');
  }
  return offer.toLine();
}

/// A sale parked for later (held order) at a store.
class ParkedSale {
  final String id;
  final String? customerName;
  final double subtotal;
  final List<PosLine> lines;
  final String? parkedAt;

  const ParkedSale({
    required this.id,
    this.customerName,
    required this.subtotal,
    required this.lines,
    this.parkedAt,
  });

  factory ParkedSale.fromJson(Map<String, dynamic> j) => ParkedSale(
        id: j['id'] as String? ?? '',
        customerName: j['customerName'] as String?,
        subtotal: (j['subtotal'] as num?)?.toDouble() ?? 0,
        parkedAt: j['parkedAt'] as String?,
        lines: ((j['items'] as List?) ?? []).map((e) {
          final m = e as Map<String, dynamic>;
          final vid = m['variantId'] as String? ?? '';
          return PosLine(
            variantId: vid,
            sku: vid.length > 8 ? vid.substring(0, 8) : vid,
            name: 'Parked item',
            qty: (m['qty'] as num?)?.toInt() ?? 1,
            unitPrice: (m['unitPrice'] as num?)?.toDouble() ?? 0,
            currency: '',
          );
        }).toList(),
      );
}

final parkedSalesProvider =
    FutureProvider.autoDispose<List<ParkedSale>>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get('/${ApiConstants.order}/pos/parked-sales');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => ParkedSale.fromJson(e as Map<String, dynamic>)).toList();
});
