import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/theme.dart';
import 'pos_providers.dart';

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
    final s = e.toString();
    if (s.contains('404') || s.contains('No product')) {
      return 'No product found for that barcode.';
    }
    return 'Scan failed: $s';
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
      ref.invalidate(parkedSalesProvider);
      _snack('Sale held.');
    } catch (e) {
      _snack('Could not hold sale: $e', error: true);
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
              loading: () =>
                  const SizedBox(height: 80, child: Center(child: CircularProgressIndicator())),
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
      _snack('Could not log no-sale: $e', error: true);
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
    final items = ref.watch(posCartProvider);
    final total = ref.watch(posCartProvider.notifier).total;
    final currency = items.isNotEmpty ? items.first.currency : '';

    return Column(
      children: [
        _StoreSelector(),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
          child: TextField(
            controller: _barcodeCtrl,
            focusNode: _barcodeFocus,
            autofocus: true,
            enabled: !_scanning,
            decoration: InputDecoration(
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
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
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
        Expanded(
          child: items.isEmpty
              ? const Center(child: Text('Scan a product to start the sale'))
              : ListView.separated(
                  itemCount: items.length,
                  separatorBuilder: (_, __) => const Divider(height: 1),
                  itemBuilder: (_, idx) {
                    final item = items[idx];
                    return ListTile(
                      leading: const Icon(Icons.inventory_2_outlined),
                      title: Text(item.name),
                      subtitle: Text(
                          'SKU: ${item.sku}  ·  ${item.currency} ${item.unitPrice.toStringAsFixed(2)}'),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: const Icon(Icons.remove),
                            onPressed: () => ref
                                .read(posCartProvider.notifier)
                                .setQty(item.variantId, item.qty - 1),
                          ),
                          Text('${item.qty}',
                              style: const TextStyle(fontWeight: FontWeight.bold)),
                          IconButton(
                            icon: const Icon(Icons.add),
                            onPressed: () => ref
                                .read(posCartProvider.notifier)
                                .setQty(item.variantId, item.qty + 1),
                          ),
                          const SizedBox(width: 8),
                          SizedBox(
                            width: 80,
                            child: Text(
                              '${item.currency} ${item.lineTotal.toStringAsFixed(2)}',
                              textAlign: TextAlign.right,
                              style: const TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
        ),
        const Divider(height: 1),
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Text(
                'Total: $currency ${total.toStringAsFixed(2)}',
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.bold),
              ),
              const Spacer(),
              OutlinedButton(
                onPressed: items.isEmpty
                    ? null
                    : () => ref.read(posCartProvider.notifier).clear(),
                child: const Text('Clear'),
              ),
              const SizedBox(width: 12),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: AppTheme.posAccent),
                onPressed: items.isEmpty ? null : () => context.go('/pos/tender'),
                child: const Text('Tender  →'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// Lets the cashier pick which store this terminal sells from. Defaults to the
/// first store once the list loads.
class _StoreSelector extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(posStoresProvider);
    final selected = ref.watch(posStoreProvider);
    final cs = Theme.of(context).colorScheme;

    return storesAsync.when(
      loading: () => const LinearProgressIndicator(),
      error: (e, _) => Padding(
        padding: const EdgeInsets.all(12),
        child: Text('Could not load stores: $e', style: TextStyle(color: cs.error)),
      ),
      data: (stores) {
        if (stores.isEmpty) {
          return Padding(
            padding: const EdgeInsets.all(12),
            child: Text('No stores configured.', style: TextStyle(color: cs.error)),
          );
        }
        // Default to the first store once.
        final current = selected ?? stores.first.id;
        if (selected == null) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            ref.read(posStoreProvider.notifier).state = current;
          });
        }
        return Padding(
          padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
          child: Row(
            children: [
              Icon(Icons.store_outlined, size: 18, color: cs.outline),
              const SizedBox(width: 8),
              Expanded(
                child: DropdownButton<String>(
                  isExpanded: true,
                  value: current,
                  underline: const SizedBox.shrink(),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(value: s.id, child: Text(s.name)),
                  ],
                  onChanged: (v) => ref.read(posStoreProvider.notifier).state = v,
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
