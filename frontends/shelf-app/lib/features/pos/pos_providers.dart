import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
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

/// Reuses the admin stores list so the cashier can pick the terminal's store.
final posStoresProvider = storesProvider;

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
