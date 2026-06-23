import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/theme.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_providers.dart';
import 'pos_session_providers.dart';

/// The register screen. On a wide terminal it's a two-pane supermarket till —
/// a persistent product catalog on the left, the live sale on the right. On a
/// phone it collapses to the sale with a "Browse" sheet for the catalog.
class PosCartScreen extends ConsumerStatefulWidget {
  const PosCartScreen({super.key});

  @override
  ConsumerState<PosCartScreen> createState() => _PosCartScreenState();
}

class _PosCartScreenState extends ConsumerState<PosCartScreen> {
  final _barcodeCtrl = TextEditingController();
  final _barcodeFocus = FocusNode();
  bool _scanning = false;

  @override
  void dispose() {
    _barcodeCtrl.dispose();
    _barcodeFocus.dispose();
    super.dispose();
  }

  Future<void> _scan(String raw) async {
    final code = raw.trim();
    if (code.isEmpty || _scanning) return;
    setState(() => _scanning = true);
    try {
      final line = await scanBarcode(ref, code);
      ref.read(posCartProvider.notifier).addOrIncrement(line);
      _barcodeCtrl.clear();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_friendly(e)),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _scanning = false);
      _barcodeFocus.requestFocus();
    }
  }

  String _friendly(Object e) {
    // Prefer the backend's structured error; PRODUCT_NOT_FOUND (or a bare 404)
    // means the scanned barcode matched nothing.
    if (apiErrorCode(e) == 'PRODUCT_NOT_FOUND' ||
        (e is DioException && e.response?.statusCode == 404)) {
      return 'No product found for that barcode.';
    }
    return friendlyError(e, fallback: 'Scan failed. Please try again.');
  }

  Future<void> _scanWithCamera() async {
    final code = await scanBarcodeWithCamera(context);
    if (code != null && code.isNotEmpty) {
      _barcodeCtrl.text = code;
      await _scan(code);
    }
  }

  void _addOffer(PosOffer offer) {
    ref.read(posCartProvider.notifier).addOrIncrement(offer.toLine());
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
          content: Text('Added ${offer.name}'),
          duration: const Duration(milliseconds: 600)),
    );
  }

  /// Narrow-screen catalog: open the same catalog pane as a full-height sheet.
  Future<void> _browse() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => SizedBox(
        height: MediaQuery.of(context).size.height * 0.85,
        child: _CatalogPane(onPick: (offer) {
          _addOffer(offer);
          Navigator.pop(context);
        }),
      ),
    );
    _barcodeFocus.requestFocus();
  }

  Future<void> _park() async {
    final items = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    if (items.isEmpty || storeId == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/pos/parked-sales',
        data: {
          'storeId': storeId,
          'items': [
            for (final l in items)
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
        },
      );
      ref.read(posCartProvider.notifier).clear();
      ref.read(posDiscountProvider.notifier).state = 0;
      ref.invalidate(parkedSalesProvider);
      _snack('Sale held.');
    } catch (e) {
      _snack(friendlyError(e, fallback: 'Could not hold sale.'), error: true);
    }
  }

  Future<void> _resume() async {
    final selected = await showDialog<ParkedSale>(
      context: context,
      builder: (ctx) => Consumer(builder: (ctx, ref, _) {
        final async = ref.watch(parkedSalesProvider);
        return AlertDialog(
          title: const Text('Resume held sale'),
          content: SizedBox(
            width: 380,
            child: async.when(
              loading: () => const SizedBox(
                  height: 80, child: Center(child: CircularProgressIndicator())),
              error: (e, _) => Text('Failed: $e'),
              data: (sales) => sales.isEmpty
                  ? const Text('No held sales.')
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        for (final s in sales)
                          ListTile(
                            title: Text(s.customerName ?? 'Held sale'),
                            subtitle: Text(
                                '${s.lines.length} items · ${s.subtotal.toStringAsFixed(2)}'),
                            onTap: () => Navigator.pop(ctx, s),
                          ),
                      ],
                    ),
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
          ],
        );
      }),
    );
    if (selected == null) return;
    ref.read(posCartProvider.notifier).loadLines(selected.lines);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.order}/pos/parked-sales/${selected.id}');
      ref.invalidate(parkedSalesProvider);
    } catch (_) {
      // Resumed locally even if the delete failed; it will expire server-side.
    }
  }

  Future<void> _noSale() async {
    final storeId = ref.read(posStoreProvider);
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/pos/no-sale',
        data: {'storeId': storeId, 'reason': 'No sale'},
      );
      _snack('Drawer opened (no sale logged).');
    } catch (e) {
      _snack(friendlyError(e, fallback: 'Could not log no-sale.'), error: true);
    }
  }

  void _snack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 880;
        if (wide) {
          return Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(child: _CatalogPane(onPick: _addOffer)),
              const VerticalDivider(width: 1),
              SizedBox(width: 420, child: _salePane(showBrowse: false)),
            ],
          );
        }
        return _salePane(showBrowse: true);
      },
    );
  }

  Widget _salePane({required bool showBrowse}) {
    final items = ref.watch(posCartProvider);
    return Column(
      children: [
        _StoreSelector(),
        const _CustomerBar(),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _barcodeCtrl,
                  focusNode: _barcodeFocus,
                  autofocus: true,
                  enabled: !_scanning,
                  decoration: InputDecoration(
                    isDense: true,
                    hintText: 'Scan barcode or type SKU…',
                    prefixIcon: const Icon(Icons.qr_code_scanner),
                    suffixIcon: _scanning
                        ? const Padding(
                            padding: EdgeInsets.all(12),
                            child: SizedBox(
                                height: 18,
                                width: 18,
                                child: CircularProgressIndicator(strokeWidth: 2)),
                          )
                        : IconButton(
                            icon: const Icon(Icons.add_circle_outline),
                            color: AppTheme.posAccent,
                            onPressed: () => _scan(_barcodeCtrl.text),
                          ),
                  ),
                  onSubmitted: _scan,
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filledTonal(
                tooltip: 'Scan with camera',
                onPressed: _scanning ? null : _scanWithCamera,
                icon: const Icon(Icons.camera_alt_outlined),
              ),
              if (showBrowse) ...[
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  onPressed: _scanning ? null : _browse,
                  icon: const Icon(Icons.grid_view, size: 18),
                  label: const Text('Browse'),
                  style: OutlinedButton.styleFrom(
                      padding:
                          const EdgeInsets.symmetric(horizontal: 14, vertical: 14)),
                ),
              ],
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
          child: Row(
            children: [
              TextButton.icon(
                onPressed: items.isEmpty ? null : _park,
                icon: const Icon(Icons.pause_circle_outline, size: 18),
                label: const Text('Hold'),
              ),
              TextButton.icon(
                onPressed: _resume,
                icon: const Icon(Icons.play_circle_outline, size: 18),
                label: const Text('Resume'),
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: _noSale,
                icon: const Icon(Icons.point_of_sale, size: 18),
                label: const Text('No sale'),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: items.isEmpty
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.shopping_cart_outlined,
                          size: 48,
                          color: Theme.of(context).colorScheme.outlineVariant),
                      const SizedBox(height: 12),
                      Text('Scan or tap a product to start',
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.outline)),
                    ],
                  ),
                )
              : ListView.separated(
                  itemCount: items.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, idx) => _SaleLine(line: items[idx]),
                ),
        ),
        const Divider(height: 1),
        _TotalsBar(),
      ],
    );
  }
}

/// One line on the running sale: name, unit price, qty stepper, line total, and
/// a swipe-to-remove gesture.
class _SaleLine extends ConsumerWidget {
  final PosLine line;
  const _SaleLine({required this.line});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifier = ref.read(posCartProvider.notifier);
    final showPrices = ref.watch(posShowPricesProvider);
    return Dismissible(
      key: ValueKey(line.variantId),
      direction: DismissDirection.endToStart,
      background: Container(
        color: Theme.of(context).colorScheme.errorContainer,
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 20),
        child: Icon(Icons.delete_outline,
            color: Theme.of(context).colorScheme.onErrorContainer),
      ),
      onDismissed: (_) => notifier.setQty(line.variantId, 0),
      child: ListTile(
        dense: true,
        title: Text(line.name, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(showPrices
            ? '${line.currency} ${line.unitPrice.toStringAsFixed(2)} · ${line.sku}'
            : line.sku),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.remove_circle_outline),
              onPressed: () => notifier.setQty(line.variantId, line.qty - 1),
            ),
            Text('${line.qty}',
                style: const TextStyle(fontWeight: FontWeight.bold)),
            IconButton(
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.add_circle_outline),
              onPressed: () => notifier.setQty(line.variantId, line.qty + 1),
            ),
            if (showPrices)
              SizedBox(
                width: 72,
                child: Text(
                    '${line.currency} ${line.lineTotal.toStringAsFixed(2)}',
                    textAlign: TextAlign.right,
                    style: const TextStyle(fontWeight: FontWeight.bold)),
              ),
          ],
        ),
      ),
    );
  }
}

/// The catalog pane: category filter + search + tap-to-add product grid. Each
/// tile resolves its own POS price + live stock lazily (only visible tiles
/// fetch), mirroring the storefront's per-card offer pattern.
class _CatalogPane extends ConsumerStatefulWidget {
  final void Function(PosOffer) onPick;
  const _CatalogPane({required this.onPick});

  @override
  ConsumerState<_CatalogPane> createState() => _CatalogPaneState();
}

class _CatalogPaneState extends ConsumerState<_CatalogPane> {
  final _searchCtrl = TextEditingController();

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final categoriesAsync = ref.watch(posCategoriesProvider);
    final selectedCat = ref.watch(posSelectedCategoryProvider);
    final query = ref.watch(posSearchProvider);
    final productsAsync =
        ref.watch(posCatalogProvider((categoryId: selectedCat, query: query)));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
          child: TextField(
            controller: _searchCtrl,
            decoration: InputDecoration(
              isDense: true,
              hintText: 'Search products…',
              prefixIcon: const Icon(Icons.search),
              suffixIcon: query.isEmpty
                  ? null
                  : IconButton(
                      icon: const Icon(Icons.clear),
                      onPressed: () {
                        _searchCtrl.clear();
                        ref.read(posSearchProvider.notifier).state = '';
                      },
                    ),
            ),
            onChanged: (v) =>
                ref.read(posSearchProvider.notifier).state = v.trim(),
          ),
        ),
        SizedBox(
          height: 40,
          child: categoriesAsync.when(
            loading: () => const SizedBox.shrink(),
            error: (_, __) => const SizedBox.shrink(),
            data: (cats) => ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              children: [
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: const Text('All'),
                    selected: selectedCat == null,
                    onSelected: (_) =>
                        ref.read(posSelectedCategoryProvider.notifier).state = null,
                  ),
                ),
                for (final c in cats)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Text(c.name),
                      selected: selectedCat == c.id,
                      onSelected: (_) => ref
                          .read(posSelectedCategoryProvider.notifier)
                          .state = c.id,
                    ),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: productsAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
                child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Text('Could not load products.\n$e',
                        textAlign: TextAlign.center))),
            data: (products) {
              if (products.isEmpty) {
                return const Center(child: Text('No POS-sellable products.'));
              }
              return GridView.builder(
                padding: const EdgeInsets.all(12),
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 170,
                  childAspectRatio: 1.15,
                  crossAxisSpacing: 10,
                  mainAxisSpacing: 10,
                ),
                itemCount: products.length,
                itemBuilder: (_, i) =>
                    _OfferTile(product: products[i], onPick: widget.onPick),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// A product tile that resolves its POS price + stock and adds to the sale on tap.
class _OfferTile extends ConsumerWidget {
  final ProductInfo product;
  final void Function(PosOffer) onPick;
  const _OfferTile({required this.product, required this.onPick});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final showPrices = ref.watch(posShowPricesProvider);
    final offerAsync = ref.watch(posProductOfferProvider(product));
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: offerAsync.maybeWhen(
          data: (o) => o == null ? null : () => onPick(o),
          orElse: () => null,
        ),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  product.name,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style:
                      const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
                ),
              ),
              const SizedBox(height: 6),
              offerAsync.when(
                loading: () => Text('…', style: TextStyle(color: cs.outline)),
                error: (_, __) =>
                    Text('—', style: TextStyle(color: cs.outline)),
                data: (o) {
                  if (o == null) {
                    return Text('No variant',
                        style: TextStyle(color: cs.error, fontSize: 11));
                  }
                  // Catalog mode: no price anywhere — show stock status instead.
                  if (!showPrices) {
                    return Row(
                      children: [
                        Expanded(
                          child: Text(o.inStock ? 'In stock' : 'Out of stock',
                              style: TextStyle(
                                  color: o.inStock
                                      ? Colors.green.shade700
                                      : cs.error,
                                  fontWeight: FontWeight.w600,
                                  fontSize: 12)),
                        ),
                        _StockDot(inStock: o.inStock),
                      ],
                    );
                  }
                  return Row(
                    children: [
                      Expanded(
                        child: Text('${o.currency} ${o.unitPrice.toStringAsFixed(2)}',
                            style: const TextStyle(
                                color: AppTheme.posAccent,
                                fontWeight: FontWeight.bold)),
                      ),
                      _StockDot(inStock: o.inStock),
                    ],
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StockDot extends StatelessWidget {
  final bool inStock;
  const _StockDot({required this.inStock});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Tooltip(
      message: inStock ? 'In stock' : 'Out of stock',
      child: Icon(Icons.circle,
          size: 10, color: inStock ? Colors.green.shade600 : cs.error),
    );
  }
}

/// Shows the store this terminal is clocked in to (fixed for the session) plus
/// how long the session has been open.
class _StoreSelector extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(posStoresProvider);
    final session = ref.watch(posSessionProvider);
    final selected = ref.watch(posStoreProvider);
    final cs = Theme.of(context).colorScheme;

    final storeId = session?.storeId ?? selected;
    final storeName = storesAsync.maybeWhen(
      data: (stores) {
        for (final s in stores) {
          if (s.id == storeId) return s.name;
        }
        return null;
      },
      orElse: () => null,
    );

    return Container(
      width: double.infinity,
      color: cs.surfaceContainerHighest,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          const Icon(Icons.store, size: 18, color: AppTheme.posAccent),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              storeName ?? 'Store',
              style: const TextStyle(fontWeight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (session != null) ...[
            Icon(Icons.schedule, size: 14, color: cs.outline),
            const SizedBox(width: 4),
            Text(_elapsed(session.startedAt),
                style: TextStyle(color: cs.outline, fontSize: 12)),
          ],
        ],
      ),
    );
  }

  String _elapsed(String startedAtIso) {
    final start = DateTime.tryParse(startedAtIso);
    if (start == null) return '';
    final d = DateTime.now().difference(start.toLocal());
    if (d.inHours > 0) return '${d.inHours}h ${d.inMinutes % 60}m';
    return '${d.inMinutes}m';
  }
}

/// Shows the customer attached to the sale (or a "walk-in" prompt to attach one).
class _CustomerBar extends ConsumerWidget {
  const _CustomerBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final customer = ref.watch(posCustomerProvider);
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 4, 4, 0),
      child: Row(
        children: [
          Icon(Icons.person_outline, size: 18, color: cs.outline),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              customer == null
                  ? 'Walk-in customer'
                  : (customer.fullName.isEmpty
                      ? customer.email
                      : customer.fullName),
              style: TextStyle(
                color: customer == null ? cs.outline : cs.onSurface,
                fontWeight:
                    customer == null ? FontWeight.normal : FontWeight.w600,
              ),
            ),
          ),
          if (customer != null)
            IconButton(
              icon: const Icon(Icons.close, size: 18),
              tooltip: 'Remove customer',
              onPressed: () =>
                  ref.read(posCustomerProvider.notifier).state = null,
            ),
          TextButton.icon(
            icon: Icon(customer == null ? Icons.person_add_alt : Icons.swap_horiz,
                size: 18),
            label: Text(customer == null ? 'Add' : 'Change'),
            onPressed: () async {
              final picked = await showDialog<Customer?>(
                context: context,
                builder: (_) => const _CustomerPickerDialog(),
              );
              if (picked != null) {
                ref.read(posCustomerProvider.notifier).state =
                    picked.id.isEmpty ? null : picked;
              }
            },
          ),
        ],
      ),
    );
  }
}

/// Subtotal, optional order discount, net total, plus Clear / Tender actions.
class _TotalsBar extends ConsumerWidget {
  Future<void> _editDiscount(
      BuildContext context, WidgetRef ref, String currency, double subtotal) async {
    final ctrl = TextEditingController(
        text: ref.read(posDiscountProvider) > 0
            ? ref.read(posDiscountProvider).toStringAsFixed(2)
            : '');
    final result = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Order discount'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
              labelText: 'Discount amount', prefixText: '$currency '),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, 0.0),
              child: const Text('Clear')),
          FilledButton(
            onPressed: () =>
                Navigator.pop(ctx, double.tryParse(ctrl.text) ?? 0.0),
            child: const Text('Apply'),
          ),
        ],
      ),
    );
    if (result == null) return;
    ref.read(posDiscountProvider.notifier).state =
        result.clamp(0, subtotal).toDouble();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final items = ref.watch(posCartProvider);
    final showPrices = ref.watch(posShowPricesProvider);
    final subtotal = ref.watch(posCartProvider.notifier).total;
    final currency = items.isNotEmpty ? items.first.currency : '';
    final discount = ref.watch(posDiscountProvider).clamp(0, subtotal).toDouble();
    final net = subtotal - discount;
    final tt = Theme.of(context).textTheme;
    final qty = items.fold<int>(0, (s, l) => s + l.qty);

    final clearButton = Expanded(
      child: OutlinedButton(
        onPressed: items.isEmpty
            ? null
            : () {
                ref.read(posCartProvider.notifier).clear();
                ref.read(posDiscountProvider.notifier).state = 0;
              },
        child: const Text('Clear'),
      ),
    );

    // Catalog mode: no prices anywhere, checkout just places the order.
    if (!showPrices) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
        child: Row(
          children: [
            clearButton,
            const SizedBox(width: 12),
            Expanded(
              flex: 2,
              child: FilledButton.icon(
                style: FilledButton.styleFrom(
                    backgroundColor: AppTheme.posAccent,
                    padding: const EdgeInsets.symmetric(vertical: 16)),
                onPressed:
                    items.isEmpty ? null : () => context.go('/pos/tender'),
                icon: const Icon(Icons.receipt_long),
                label: Text(
                    'Place order${qty > 0 ? '  ($qty)' : ''}',
                    style: const TextStyle(fontSize: 16)),
              ),
            ),
          ],
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text('Subtotal', style: tt.bodyMedium),
              const Spacer(),
              Text('$currency ${subtotal.toStringAsFixed(2)}',
                  style: tt.bodyMedium),
            ],
          ),
          const SizedBox(height: 2),
          Row(
            children: [
              TextButton.icon(
                onPressed:
                    items.isEmpty ? null : () => _editDiscount(context, ref, currency, subtotal),
                icon: const Icon(Icons.percent, size: 16),
                label: Text(discount > 0 ? 'Discount' : 'Add discount'),
                style: TextButton.styleFrom(
                    padding: EdgeInsets.zero, minimumSize: const Size(0, 30)),
              ),
              const Spacer(),
              if (discount > 0)
                Text('− $currency ${discount.toStringAsFixed(2)}',
                    style: tt.bodyMedium
                        ?.copyWith(color: Theme.of(context).colorScheme.error)),
            ],
          ),
          const Divider(),
          Row(
            children: [
              Text('Total',
                  style: tt.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
              const Spacer(),
              Text('$currency ${net.toStringAsFixed(2)}',
                  style: tt.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              clearButton,
              const SizedBox(width: 12),
              Expanded(
                flex: 2,
                child: FilledButton(
                  style: FilledButton.styleFrom(
                      backgroundColor: AppTheme.posAccent,
                      padding: const EdgeInsets.symmetric(vertical: 16)),
                  onPressed:
                      items.isEmpty ? null : () => context.go('/pos/tender'),
                  child: Text('Charge $currency ${net.toStringAsFixed(2)}',
                      style: const TextStyle(fontSize: 16)),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Search + pick a customer to attach to the sale (or clear to walk-in).
class _CustomerPickerDialog extends ConsumerStatefulWidget {
  const _CustomerPickerDialog();

  @override
  ConsumerState<_CustomerPickerDialog> createState() =>
      _CustomerPickerDialogState();
}

class _CustomerPickerDialogState extends ConsumerState<_CustomerPickerDialog> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(customersProvider);
    return Dialog(
      child: SizedBox(
        width: 460,
        height: 520,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      autofocus: true,
                      decoration: const InputDecoration(
                        hintText: 'Search name, email or phone…',
                        prefixIcon: Icon(Icons.search),
                        isDense: true,
                      ),
                      onChanged: (v) =>
                          setState(() => _query = v.trim().toLowerCase()),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            ListTile(
              leading: const Icon(Icons.person_off_outlined),
              title: const Text('Walk-in customer (no account)'),
              onTap: () => Navigator.pop(
                  context,
                  const Customer(
                      id: '',
                      email: '',
                      firstName: '',
                      lastName: '',
                      status: '')),
            ),
            const Divider(height: 1),
            Expanded(
              child: async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(
                    child: Text('Could not load customers.\n$e',
                        textAlign: TextAlign.center)),
                data: (all) {
                  final list = _query.isEmpty
                      ? all
                      : all.where((c) {
                          final hay =
                              '${c.fullName} ${c.email} ${c.phone ?? ''}'
                                  .toLowerCase();
                          return hay.contains(_query);
                        }).toList();
                  if (list.isEmpty) {
                    return const Center(child: Text('No customers match.'));
                  }
                  return ListView.separated(
                    itemCount: list.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final c = list[i];
                      return ListTile(
                        leading: const Icon(Icons.person_outline),
                        title:
                            Text(c.fullName.isEmpty ? c.email : c.fullName),
                        subtitle: Text([c.email, c.phone]
                            .where((e) => e != null && e.isNotEmpty)
                            .join(' · ')),
                        onTap: () => Navigator.pop(context, c),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
