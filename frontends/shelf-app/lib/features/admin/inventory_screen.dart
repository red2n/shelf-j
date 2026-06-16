import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

class InventoryScreen extends ConsumerStatefulWidget {
  const InventoryScreen({super.key});

  @override
  ConsumerState<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends ConsumerState<InventoryScreen> {
  String _search = '';
  bool _lowOnly = false;

  @override
  Widget build(BuildContext context) {
    final levelsAsync = ref.watch(inventoryLevelsProvider);
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            children: [
              Text('Inventory', style: Theme.of(context).textTheme.headlineMedium),
              const Spacer(),
              FilledButton.icon(
                onPressed: () => _showReceiveDialog(context, ref),
                icon: const Icon(Icons.add),
                label: const Text('Receive Stock'),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: () => ref.invalidate(inventoryLevelsProvider),
              ),
            ],
          ),
        ),

        // Search + filter bar
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  decoration: const InputDecoration(
                    hintText: 'Search variant ID…',
                    prefixIcon: Icon(Icons.search),
                    isDense: true,
                  ),
                  onChanged: (v) => setState(() => _search = v.trim()),
                ),
              ),
              const SizedBox(width: 12),
              FilterChip(
                label: const Text('Low stock'),
                avatar: Icon(Icons.warning_amber_outlined,
                    size: 14, color: _lowOnly ? cs.onError : null),
                selected: _lowOnly,
                selectedColor: cs.errorContainer,
                onSelected: (v) => setState(() => _lowOnly = v),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // Summary strip
        levelsAsync.when(
          loading: () => const SizedBox.shrink(),
          error: (_, __) => const SizedBox.shrink(),
          data: (levels) {
            final low = levels.where((l) => l.isLow).length;
            return Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Row(
                children: [
                  _SummaryChip(
                      icon: Icons.inventory_2_outlined,
                      label: '${levels.length} SKUs',
                      color: cs.secondaryContainer),
                  const SizedBox(width: 8),
                  if (low > 0)
                    _SummaryChip(
                        icon: Icons.warning_amber_outlined,
                        label: '$low low stock',
                        color: cs.errorContainer),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 12),

        // Table
        Expanded(
          child: levelsAsync.when(
            loading: () => const LoadingView(label: 'Loading inventory…'),
            error: (e, _) => ErrorView(
              message: 'Could not load inventory levels.',
              onRetry: () => ref.invalidate(inventoryLevelsProvider),
            ),
            data: (levels) {
              var filtered = levels.where((l) {
                if (_lowOnly && !l.isLow) return false;
                if (_search.isNotEmpty &&
                    !l.variantId
                        .toLowerCase()
                        .contains(_search.toLowerCase())) return false;
                return true;
              }).toList();

              if (filtered.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.inventory_2_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text('No items found',
                          style: Theme.of(context).textTheme.titleMedium),
                      if (_search.isNotEmpty || _lowOnly)
                        TextButton(
                          onPressed: () =>
                              setState(() { _search = ''; _lowOnly = false; }),
                          child: const Text('Clear filters'),
                        ),
                    ],
                  ),
                );
              }

              return LayoutBuilder(builder: (context, bc) {
                final wide = bc.maxWidth >= 600;
                if (wide) {
                  return _WideTable(levels: filtered);
                }
                return _NarrowList(levels: filtered);
              });
            },
          ),
        ),
      ],
    );
  }

  void _showReceiveDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (_) => _ReceiveStockDialog(
        onReceived: () => ref.invalidate(inventoryLevelsProvider),
      ),
    );
  }
}

class _ReceiveStockDialog extends ConsumerStatefulWidget {
  final VoidCallback onReceived;
  const _ReceiveStockDialog({required this.onReceived});

  @override
  ConsumerState<_ReceiveStockDialog> createState() => _ReceiveStockDialogState();
}

class _ReceiveStockDialogState extends ConsumerState<_ReceiveStockDialog> {
  final _formKey = GlobalKey<FormState>();
  final _variantCtrl = TextEditingController();
  final _qtyCtrl = TextEditingController();
  final _costCtrl = TextEditingController();
  final _batchCtrl = TextEditingController();
  final _expiryCtrl = TextEditingController();
  String? _storeId;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _variantCtrl.dispose();
    _qtyCtrl.dispose();
    _costCtrl.dispose();
    _batchCtrl.dispose();
    _expiryCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_storeId == null) {
      setState(() => _error = 'Select a store.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.inventory}/admin/inventory/receive',
        data: {
          'storeId': _storeId,
          'variantId': _variantCtrl.text.trim(),
          'qty': double.parse(_qtyCtrl.text.trim()),
          if (_batchCtrl.text.trim().isNotEmpty) 'batchNo': _batchCtrl.text.trim(),
          if (_costCtrl.text.trim().isNotEmpty)
            'costPrice': double.parse(_costCtrl.text.trim()),
          if (_expiryCtrl.text.trim().isNotEmpty)
            'expiryDate': _expiryCtrl.text.trim(),
        },
      );
      if (!mounted) return;
      widget.onReceived();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Stock received.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = _friendly(e);
      });
    }
  }

  String _friendly(Object e) {
    final s = e.toString();
    if (s.contains('404')) return 'No variant with that ID exists.';
    if (s.contains('400')) return 'Check the variant ID (UUID) and quantity.';
    return 'Could not receive stock: $s';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Receive Stock'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_error != null) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child:
                        Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                  ),
                  const SizedBox(height: 12),
                ],
                storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text('Could not load stores: $e',
                      style: TextStyle(color: cs.error)),
                  data: (stores) => DropdownButtonFormField<String>(
                    value: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store *',
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: stores
                        .map((s) => DropdownMenuItem(
                              value: s.id,
                              child: Text('${s.name} (${s.code})',
                                  overflow: TextOverflow.ellipsis),
                            ))
                        .toList(),
                    onChanged: (v) => setState(() => _storeId = v),
                    validator: (v) => v == null ? 'Required' : null,
                  ),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _variantCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Variant ID (UUID) *',
                    prefixIcon: Icon(Icons.qr_code_2_outlined),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _qtyCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                            decimal: true),
                        decoration: const InputDecoration(labelText: 'Quantity *'),
                        validator: (v) {
                          if (v == null || v.trim().isEmpty) return 'Required';
                          final n = double.tryParse(v.trim());
                          if (n == null || n <= 0) return '> 0';
                          return null;
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _costCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                            decimal: true),
                        decoration: const InputDecoration(
                          labelText: 'Cost price',
                          prefixText: '\$ ',
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _batchCtrl,
                        decoration: const InputDecoration(labelText: 'Batch no.'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _expiryCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Expiry',
                          hintText: 'YYYY-MM-DD',
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child:
                      CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Receive'),
        ),
      ],
    );
  }
}

class _WideTable extends StatelessWidget {
  final List<InventoryLevel> levels;
  const _WideTable({required this.levels});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Card(
        child: DataTable(
          headingRowColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
          columnSpacing: 24,
          columns: const [
            DataColumn(label: Text('Variant ID')),
            DataColumn(label: Text('Store')),
            DataColumn(label: Text('On-Hand'), numeric: true),
            DataColumn(label: Text('Reserved'), numeric: true),
            DataColumn(label: Text('Available'), numeric: true),
            DataColumn(label: Text('Status')),
          ],
          rows: levels.map((l) {
            final isLow = l.isLow;
            return DataRow(
              color: isLow
                  ? WidgetStatePropertyAll(cs.errorContainer.withAlpha(80))
                  : null,
              cells: [
                DataCell(Text(
                  l.variantId.length > 16
                      ? '${l.variantId.substring(0, 8)}…'
                      : l.variantId,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                )),
                DataCell(Text(
                  l.storeId.length > 8 ? l.storeId.substring(0, 8) : l.storeId,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                )),
                DataCell(Text(l.onHand.toStringAsFixed(0))),
                DataCell(Text(l.reserved.toStringAsFixed(0))),
                DataCell(Text(l.available.toStringAsFixed(0),
                    style: TextStyle(
                        color: isLow ? cs.error : cs.onSurface,
                        fontWeight: isLow ? FontWeight.bold : null))),
                DataCell(
                  isLow
                      ? Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.warning_amber_outlined,
                                size: 14, color: cs.error),
                            const SizedBox(width: 4),
                            Text('Low',
                                style: TextStyle(
                                    color: cs.error,
                                    fontWeight: FontWeight.bold,
                                    fontSize: 12)),
                          ],
                        )
                      : const Text('OK',
                          style: TextStyle(color: Colors.green, fontSize: 12)),
                ),
              ],
            );
          }).toList(),
        ),
      ),
    );
  }
}

class _NarrowList extends StatelessWidget {
  final List<InventoryLevel> levels;
  const _NarrowList({required this.levels});

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: levels.length,
      separatorBuilder: (_, __) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final l = levels[i];
        final cs = Theme.of(context).colorScheme;
        return Card(
          color: l.isLow ? cs.errorContainer.withAlpha(80) : null,
          child: ListTile(
            leading: Icon(
              Icons.inventory_2_outlined,
              color: l.isLow ? cs.error : cs.primary,
            ),
            title: Text(
              l.variantId.length > 20
                  ? '${l.variantId.substring(0, 20)}…'
                  : l.variantId,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
            ),
            subtitle: Text(
                'On-hand: ${l.onHand.toStringAsFixed(0)}  ·  Reserved: ${l.reserved.toStringAsFixed(0)}'),
            trailing: Text(
              'Avail: ${l.available.toStringAsFixed(0)}',
              style: TextStyle(
                color: l.isLow ? cs.error : cs.onSurface,
                fontWeight: l.isLow ? FontWeight.bold : null,
              ),
            ),
          ),
        );
      },
    );
  }
}

class _SummaryChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;

  const _SummaryChip(
      {required this.icon, required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration:
          BoxDecoration(color: color, borderRadius: BorderRadius.circular(20)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14),
          const SizedBox(width: 6),
          Text(label, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }
}
