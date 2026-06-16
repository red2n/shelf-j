import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/constants.dart';

/// Dev seam for "subdomain → tenant". In production the gateway derives the
/// tenant from the storefront's domain; here we read `?tenant=<id>` from the URL
/// (put it BEFORE the hash, e.g. `/?tenant=<id>#/store/products`) and send it as
/// the `X-Storefront-Tenant` header. Overridable at runtime via the picker.
final storefrontTenantProvider = StateProvider<String?>((ref) {
  final t = Uri.base.queryParameters['tenant'];
  return (t != null && t.isNotEmpty) ? t : null;
});

/// Store used for online order fulfilment. `?store=<id>` override; otherwise the
/// seeded dev Main Store. (A real storefront would resolve this server-side.)
const _devDefaultStore = '85aa2d24-157b-4986-8a3c-8c2002f5e0c0';
final storefrontStoreProvider = StateProvider<String>((ref) {
  final s = Uri.base.queryParameters['store'];
  return (s != null && s.isNotEmpty) ? s : _devDefaultStore;
});

/// A tokenless Dio that stamps the storefront tenant header on every request.
final storefrontDioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: ApiConstants.baseUrl,
    connectTimeout: const Duration(seconds: 8),
    receiveTimeout: const Duration(seconds: 15),
    headers: const {'Content-Type': 'application/json'},
  ));
  dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    final tenant = ref.read(storefrontTenantProvider);
    if (tenant != null && tenant.isNotEmpty) {
      options.headers['X-Storefront-Tenant'] = tenant;
    }
    // Attach the signed-in customer's bearer token when present.
    final token = ref.read(storefrontAuthProvider).accessToken;
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }));
  return dio;
});

// ── Customer authentication (storefront self-service) ────────────────────────

class StorefrontAuthState {
  final String? accessToken;
  final String? refreshToken;
  final String? email;

  const StorefrontAuthState({this.accessToken, this.refreshToken, this.email});

  bool get isSignedIn => accessToken != null && accessToken!.isNotEmpty;
}

class StorefrontAuthNotifier extends StateNotifier<StorefrontAuthState> {
  StorefrontAuthNotifier() : super(const StorefrontAuthState()) {
    _load();
  }

  static const _storage = FlutterSecureStorage();
  static const _kAccess = 'sf_cust_access';
  static const _kRefresh = 'sf_cust_refresh';
  static const _kEmail = 'sf_cust_email';

  Future<void> _load() async {
    final access = await _storage.read(key: _kAccess);
    if (access == null || access.isEmpty) return;
    state = StorefrontAuthState(
      accessToken: access,
      refreshToken: await _storage.read(key: _kRefresh),
      email: await _storage.read(key: _kEmail),
    );
  }

  Future<void> register(String email, String password, String? phone) =>
      _auth('/${ApiConstants.iam}/auth/register', {
        'email': email,
        'password': password,
        if (phone != null && phone.isNotEmpty) 'phone': phone,
      }, email);

  Future<void> login(String email, String password) => _auth(
      '/${ApiConstants.iam}/auth/login',
      {'email': email, 'password': password},
      email);

  Future<void> _auth(String path, Map<String, dynamic> body, String email) async {
    // Use a clean Dio (no stale Authorization header) for the auth call.
    final dio = Dio(BaseOptions(baseUrl: ApiConstants.baseUrl));
    final resp = await dio.post(path, data: body);
    final data = resp.data['data'] as Map<String, dynamic>;
    final access = data['accessToken'] as String?;
    final refresh = data['refreshToken'] as String?;
    await _storage.write(key: _kAccess, value: access);
    await _storage.write(key: _kRefresh, value: refresh);
    await _storage.write(key: _kEmail, value: email);
    state = StorefrontAuthState(
        accessToken: access, refreshToken: refresh, email: email);
  }

  Future<void> logout() async {
    await _storage.delete(key: _kAccess);
    await _storage.delete(key: _kRefresh);
    await _storage.delete(key: _kEmail);
    state = const StorefrontAuthState();
  }
}

final storefrontAuthProvider =
    StateNotifierProvider<StorefrontAuthNotifier, StorefrontAuthState>(
        (ref) => StorefrontAuthNotifier());

// ── Models ───────────────────────────────────────────────────────────────────

class StoreProduct {
  final String id;
  final String name;
  final String? description;
  final String? categoryId;

  const StoreProduct({
    required this.id,
    required this.name,
    this.description,
    this.categoryId,
  });

  factory StoreProduct.fromJson(Map<String, dynamic> j) => StoreProduct(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        description: j['description'] as String?,
        categoryId: j['categoryId'] as String?,
      );
}

class StoreVariant {
  final String id;
  final String sku;
  final String? barcode;
  final String? unit;

  const StoreVariant({
    required this.id,
    required this.sku,
    this.barcode,
    this.unit,
  });

  factory StoreVariant.fromJson(Map<String, dynamic> j) => StoreVariant(
        id: j['id'] as String? ?? '',
        sku: j['sku'] as String? ?? '-',
        barcode: j['barcode'] as String?,
        unit: j['unit'] as String?,
      );
}

class ResolvedPrice {
  final double unitPrice;
  final double totalWithVat;
  final String currency;

  const ResolvedPrice({
    required this.unitPrice,
    required this.totalWithVat,
    required this.currency,
  });

  factory ResolvedPrice.fromJson(Map<String, dynamic> j) => ResolvedPrice(
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        totalWithVat: (j['totalWithVat'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? 'GBP',
      );
}

class StoreCategory {
  final String id;
  final String name;

  const StoreCategory({required this.id, required this.name});

  factory StoreCategory.fromJson(Map<String, dynamic> j) => StoreCategory(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
      );
}

// ── Store config & availability ──────────────────────────────────────────────

/// Per-store storefront display rules, fetched from tenant-svc.
class StorefrontConfig {
  final bool showPrices;
  const StorefrontConfig({required this.showPrices});
}

/// A tenant store, for the storefront's store switcher.
class StoreSummary {
  final String id;
  final String name;
  final bool showPrices;
  const StoreSummary(
      {required this.id, required this.name, required this.showPrices});

  factory StoreSummary.fromJson(Map<String, dynamic> j) => StoreSummary(
        id: j['storeId'] as String? ?? '',
        name: j['storeName'] as String? ?? '-',
        showPrices: j['showPrices'] as bool? ?? true,
      );
}

/// True when the storefront's tenant has been deactivated (gateway returns 403
/// TENANT_INACTIVE for every storefront path). Fails open to "available" on any
/// other error so a transient blip doesn't hide a working shop.
final storefrontSuspendedProvider =
    FutureProvider.autoDispose<bool>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  try {
    await dio.get('/${ApiConstants.tenant}/storefront/stores');
    return false;
  } on DioException catch (e) {
    return e.response?.statusCode == 403;
  } catch (_) {
    return false;
  }
});

/// Active stores for the tenant — powers the store switcher.
final storefrontStoresProvider =
    FutureProvider.autoDispose<List<StoreSummary>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.tenant}/storefront/stores');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => StoreSummary.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Whether the current store shows prices (priced shop) or hides them and shows
/// stock availability instead (catalog mode). Fails open to a priced shop.
final storefrontConfigProvider =
    FutureProvider.autoDispose<StorefrontConfig>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final store = ref.watch(storefrontStoreProvider);
  try {
    final resp = await dio.get('/${ApiConstants.tenant}/storefront/config',
        queryParameters: {'store': store});
    final d = resp.data['data'] as Map<String, dynamic>;
    return StorefrontConfig(showPrices: d['showPrices'] as bool? ?? true);
  } catch (_) {
    return const StorefrontConfig(showPrices: true);
  }
});

/// variantId → in-stock at the current store (real inventory). Empty/failed = treat as available.
final storefrontAvailabilityProvider =
    FutureProvider.autoDispose<Map<String, bool>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final store = ref.watch(storefrontStoreProvider);
  final resp = await dio.get('/${ApiConstants.inventory}/inventory/availability',
      queryParameters: {'store': store});
  final data = (resp.data['data'] as List?) ?? [];
  return {
    for (final e in data)
      (e['variantId'] as String): (e['inStock'] as bool? ?? false)
  };
});

// ── Promotions (storefront offers banner) ────────────────────────────────────

/// An active, advertised promotion for the offers carousel.
class StorePromotion {
  final String name;
  final String type; // PERCENT | FLAT
  final double value;
  final double? minOrderAmount;

  const StorePromotion({
    required this.name,
    required this.type,
    required this.value,
    this.minOrderAmount,
  });

  factory StorePromotion.fromJson(Map<String, dynamic> j) => StorePromotion(
        name: j['name'] as String? ?? 'Offer',
        type: (j['type'] as String? ?? 'PERCENT').toUpperCase(),
        value: (j['value'] as num?)?.toDouble() ?? 0,
        minOrderAmount: (j['minOrderAmount'] as num?)?.toDouble(),
      );

  /// Short headline, e.g. "20% off" or "£5 off".
  String get headline => type == 'PERCENT'
      ? '${value.toStringAsFixed(value % 1 == 0 ? 0 : 2)}% off'
      : '${value.toStringAsFixed(2)} off';
}

/// Active promotions for the current tenant (advertised offers). Fails soft to an
/// empty list so the banner can fall back to evergreen content.
final storefrontPromotionsProvider =
    FutureProvider.autoDispose<List<StorePromotion>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get('/${ApiConstants.pricing}/promotions');
    final data = (resp.data['data'] as List?) ?? [];
    return data
        .map((e) => StorePromotion.fromJson(e as Map<String, dynamic>))
        .toList();
  } catch (_) {
    return const [];
  }
});

// ── Catalog providers ────────────────────────────────────────────────────────

/// Active categories for the storefront "browse by category" chips.
final storefrontCategoriesProvider =
    FutureProvider.autoDispose<List<StoreCategory>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.product}/catalog/categories');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => StoreCategory.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Currently selected category id for browsing; null = "All".
final selectedStorefrontCategoryProvider = StateProvider<String?>((ref) => null);

/// Filter key for the storefront product list: free-text query + optional category.
typedef ProductFilter = ({String query, String? categoryId});

/// Storefront product list, filtered by search query and/or category.
final storefrontProductsProvider =
    FutureProvider.autoDispose.family<List<StoreProduct>, ProductFilter>((ref, f) async {
  final dio = ref.watch(storefrontDioProvider);
  // Watching the store id re-runs this when the shopper switches store, so the
  // catalog reflects that store's assortment.
  final store = ref.watch(storefrontStoreProvider);
  final q = f.query.trim();
  final params = <String, dynamic>{'limit': 50, 'store': store};
  // The catalog endpoint routes to text-search when q is present (ignoring
  // category) or to a category-filtered list otherwise.
  if (q.isNotEmpty) {
    params['q'] = q;
  } else if (f.categoryId != null) {
    params['category'] = f.categoryId;
  }
  final resp =
      await dio.get('/${ApiConstants.product}/catalog/products', queryParameters: params);
  final data = (resp.data['data'] as List?) ?? [];
  var list = data.map((e) => StoreProduct.fromJson(e as Map<String, dynamic>)).toList();
  // When both a search term and a category are active, the server honoured only
  // the search — narrow to the category here.
  if (q.isNotEmpty && f.categoryId != null) {
    list = list.where((p) => p.categoryId == f.categoryId).toList();
  }
  return list;
});

final storefrontProductProvider =
    FutureProvider.autoDispose.family<StoreProduct, String>((ref, id) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.product}/catalog/products/$id');
  return StoreProduct.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final storefrontVariantsProvider =
    FutureProvider.autoDispose.family<List<StoreVariant>, String>((ref, productId) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp =
      await dio.get('/${ApiConstants.product}/catalog/products/$productId/variants');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => StoreVariant.fromJson(e as Map<String, dynamic>)).toList();
});

/// Whether the current store shows prices. When false (catalog mode) the whole
/// storefront hides prices AND skips price-resolve calls; checkout is order-only.
/// Defaults to true until the store config resolves.
final storefrontShowPricesProvider = Provider.autoDispose<bool>(
    (ref) => ref.watch(storefrontConfigProvider).valueOrNull?.showPrices ?? true);

/// First sellable variant of a product (no price) — used to add to cart in
/// catalog mode without ever resolving a price.
final productFirstVariantProvider =
    FutureProvider.autoDispose.family<StoreVariant?, String>((ref, productId) async {
  final variants = await ref.watch(storefrontVariantsProvider(productId).future);
  return variants.isEmpty ? null : variants.first;
});

/// Resolved ONLINE price for one variant.
final variantPriceProvider =
    FutureProvider.autoDispose.family<ResolvedPrice, String>((ref, variantId) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.post(
    '/${ApiConstants.pricing}/prices/resolve',
    data: {'variantId': variantId, 'channel': 'ONLINE', 'qty': 1},
  );
  return ResolvedPrice.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// Price to show on a product card = price of its first variant (or null).
final productCardPriceProvider =
    FutureProvider.autoDispose.family<ResolvedPrice?, String>((ref, productId) async {
  final variants = await ref.watch(storefrontVariantsProvider(productId).future);
  if (variants.isEmpty) return null;
  return ref.watch(variantPriceProvider(variants.first.id).future);
});

/// A product's first sellable variant + its resolved price, bundled so a listing
/// card can both show a price and add straight to cart in one tap.
class CardOffer {
  final StoreVariant variant;
  final ResolvedPrice price;
  const CardOffer(this.variant, this.price);
}

final productCardOfferProvider =
    FutureProvider.autoDispose.family<CardOffer?, String>((ref, productId) async {
  final variants = await ref.watch(storefrontVariantsProvider(productId).future);
  if (variants.isEmpty) return null;
  final v = variants.first;
  final price = await ref.watch(variantPriceProvider(v.id).future);
  return CardOffer(v, price);
});

// ── Cart (client-side; there is no cart-svc) ─────────────────────────────────

class CartLine {
  final String variantId;
  final String productName;
  final String sku;
  final double unitPrice;
  final String currency;
  int qty;

  CartLine({
    required this.variantId,
    required this.productName,
    required this.sku,
    required this.unitPrice,
    required this.currency,
    this.qty = 1,
  });

  double get lineTotal => unitPrice * qty;
}

class CartNotifier extends StateNotifier<List<CartLine>> {
  CartNotifier() : super([]);

  void add(CartLine line) {
    final idx = state.indexWhere((l) => l.variantId == line.variantId);
    if (idx >= 0) {
      final copy = [...state];
      copy[idx].qty += line.qty;
      state = copy;
    } else {
      state = [...state, line];
    }
  }

  void setQty(String variantId, int qty) {
    if (qty <= 0) {
      remove(variantId);
      return;
    }
    state = [
      for (final l in state)
        if (l.variantId == variantId)
          (CartLine(
            variantId: l.variantId,
            productName: l.productName,
            sku: l.sku,
            unitPrice: l.unitPrice,
            currency: l.currency,
            qty: qty,
          ))
        else
          l
    ];
  }

  void remove(String variantId) =>
      state = state.where((l) => l.variantId != variantId).toList();

  void clear() => state = [];

  double get total => state.fold(0.0, (s, l) => s + l.lineTotal);
  int get count => state.fold(0, (s, l) => s + l.qty);
}

final cartProvider =
    StateNotifierProvider<CartNotifier, List<CartLine>>((ref) => CartNotifier());

// ── Order history (device-local) ─────────────────────────────────────────────
//
// The storefront checks out as a guest (tokenless), and `GET /orders` is not a
// public gateway path, so there is no server-side "my orders" without customer
// auth. As a pragmatic stand-in we remember each order placed on THIS device.

class StorefrontOrderRecord {
  final String orderId;
  final double total;
  final String currency;
  final int itemCount;
  final DateTime placedAt;

  const StorefrontOrderRecord({
    required this.orderId,
    required this.total,
    required this.currency,
    required this.itemCount,
    required this.placedAt,
  });

  Map<String, dynamic> toJson() => {
        'orderId': orderId,
        'total': total,
        'currency': currency,
        'itemCount': itemCount,
        'placedAt': placedAt.toIso8601String(),
      };

  factory StorefrontOrderRecord.fromJson(Map<String, dynamic> j) =>
      StorefrontOrderRecord(
        orderId: j['orderId'] as String? ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        itemCount: (j['itemCount'] as num?)?.toInt() ?? 0,
        placedAt:
            DateTime.tryParse(j['placedAt'] as String? ?? '') ?? DateTime.now(),
      );
}

class StorefrontOrdersNotifier
    extends StateNotifier<List<StorefrontOrderRecord>> {
  StorefrontOrdersNotifier() : super(const []) {
    _load();
  }

  static const _storage = FlutterSecureStorage();

  Future<void> _load() async {
    try {
      final raw = await _storage.read(key: StorageKeys.storefrontOrders);
      if (raw == null || raw.isEmpty) return;
      final list = (jsonDecode(raw) as List)
          .map((e) => StorefrontOrderRecord.fromJson(e as Map<String, dynamic>))
          .toList();
      state = list;
    } catch (_) {
      // Corrupt/unreadable history is non-fatal — start empty.
    }
  }

  Future<void> add(StorefrontOrderRecord record) async {
    state = [record, ...state];
    await _persist();
  }

  Future<void> _persist() async {
    await _storage.write(
      key: StorageKeys.storefrontOrders,
      value: jsonEncode(state.map((e) => e.toJson()).toList()),
    );
  }
}

final storefrontOrdersProvider = StateNotifierProvider<StorefrontOrdersNotifier,
    List<StorefrontOrderRecord>>((ref) => StorefrontOrdersNotifier());

// ── Order history (server-backed, signed-in customers) ───────────────────────
//
// When the shopper is signed in, their real order history comes from order-svc
// `GET /orders/mine`: the gateway stamps the tenant from the storefront header
// and the customer identity from the bearer token, and the service filters to
// orders whose customer_id is the authenticated customer. Guests (no token) fall
// back to the device-local history above.

class ServerOrderSummary {
  final String id;
  final String status;
  final double total;
  final String currency;
  final DateTime placedAt;

  const ServerOrderSummary({
    required this.id,
    required this.status,
    required this.total,
    required this.currency,
    required this.placedAt,
  });

  factory ServerOrderSummary.fromJson(Map<String, dynamic> j) =>
      ServerOrderSummary(
        id: j['id'] as String? ?? '',
        status: j['status'] as String? ?? '-',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        placedAt: DateTime.tryParse(j['createdAt'] as String? ?? '')?.toLocal() ??
            DateTime.now(),
      );
}

/// The signed-in customer's real order history. Returns null when not signed in
/// (the UI then shows the device-local list / a sign-in prompt).
final serverOrdersProvider =
    FutureProvider.autoDispose<List<ServerOrderSummary>?>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.order}/orders/mine',
      queryParameters: {'limit': 50});
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => ServerOrderSummary.fromJson(e as Map<String, dynamic>))
      .toList();
});
