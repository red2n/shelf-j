import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

class InventoryScreen extends ConsumerStatefulWidget {
  const InventoryScreen({super.key});

  @override
  ConsumerState<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends ConsumerState<InventoryScreen> {
  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.xl, AppSpacing.xl, AppSpacing.xl, 0),
            child: Row(
              children: [
                Text('Inventory', style: Theme.of(context).textTheme.headlineMedium),
                const Spacer(),
                FilledButton.icon(
                  onPressed: () => _showReceiveDialog(context, ref),
                  icon: const Icon(Icons.add),
                  label: const Text('Receive Stock'),
                ),
              ],
            ),
          ),
          const TabBar(
            isScrollable: true,
            tabs: [
              Tab(text: 'Levels'),
              Tab(text: 'Batches'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _LevelsTab(),
                _BatchesTab(),
              ],
            ),
          ),
        ],
      ),
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

class _LevelsTab extends ConsumerStatefulWidget {
  const _LevelsTab();

  @override
  ConsumerState<_LevelsTab> createState() => _LevelsTabState();
}

class _LevelsTabState extends ConsumerState<_LevelsTab> {
  String _search = '';
  bool _lowOnly = false;

  @override
  Widget build(BuildContext context) {
    final levelsAsync = ref.watch(inventoryLevelsProvider);
    final labels = ref.watch(inventoryVariantLabelsProvider).valueOrNull ??
        const <String, VariantLabel>{};
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Search + filter bar
        Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, 0),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  decoration: const InputDecoration(
                    hintText: 'Search product, SKU or ID…',
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
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh inventory',
                onPressed: () => ref.invalidate(inventoryLevelsProvider),
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
                if (_search.isNotEmpty) {
                  final label = labels[l.variantId];
                  final hay =
                      '${label?.productName ?? ''} ${label?.sku ?? ''} ${l.variantId}'
                          .toLowerCase();
                  if (!hay.contains(_search.toLowerCase())) return false;
                }
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
                  return _WideTable(levels: filtered, labels: labels);
                }
                return _NarrowList(levels: filtered, labels: labels);
              });
            },
          ),
        ),
      ],
    );
  }
}

class _BatchesTab extends ConsumerStatefulWidget {
  const _BatchesTab();

  @override
  ConsumerState<_BatchesTab> createState() => _BatchesTabState();
}

class _BatchesTabState extends ConsumerState<_BatchesTab> {
  String? _storeId;
  String? _zoneId;
  String? _materialStatus;
  String _search = '';

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl, AppSpacing.lg, AppSpacing.xl, 0),
          child: Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              SizedBox(
                width: 220,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text('Could not load stores',
                      style: TextStyle(color: cs.error)),
                  data: (stores) => DropdownButtonFormField<String>(
                    value: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      isDense: true,
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: stores
                        .map((s) => DropdownMenuItem(
                              value: s.id,
                              child: Text('${s.name} (${s.code})',
                                  overflow: TextOverflow.ellipsis),
                            ))
                        .toList(),
                    onChanged: (v) => setState(() {
                      _storeId = v;
                      _zoneId = null;
                    }),
                  ),
                ),
              ),
              if (_storeId != null)
                SizedBox(
                  width: 200,
                  child: Consumer(
                    builder: (context, ref, _) {
                      final zonesAsync = ref.watch(zonesProvider(_storeId!));
                      return zonesAsync.when(
                        loading: () => const LinearProgressIndicator(),
                        error: (_, __) => const SizedBox.shrink(),
                        data: (zones) => DropdownButtonFormField<String?>(
                          value: _zoneId,
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: 'Zone',
                            isDense: true,
                            prefixIcon: Icon(Icons.grid_view_outlined),
                          ),
                          items: [
                            const DropdownMenuItem(
                                value: null, child: Text('All zones')),
                            ...zones.map((z) => DropdownMenuItem(
                                  value: z.id,
                                  child: Text('${z.name} (${z.code})',
                                      overflow: TextOverflow.ellipsis),
                                )),
                          ],
                          onChanged: (v) => setState(() => _zoneId = v),
                        ),
                      );
                    },
                  ),
                ),
              SizedBox(
                width: 190,
                child: DropdownButtonFormField<String?>(
                  value: _materialStatus,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Material status',
                    isDense: true,
                  ),
                  items: const [
                    DropdownMenuItem(value: null, child: Text('Any status')),
                    DropdownMenuItem(value: 'AVAILABLE', child: Text('Available')),
                    DropdownMenuItem(value: 'QUARANTINE', child: Text('Quarantine')),
                    DropdownMenuItem(value: 'REJECTED', child: Text('Rejected')),
                    DropdownMenuItem(value: 'HOLD', child: Text('Hold')),
                  ],
                  onChanged: (v) => setState(() => _materialStatus = v),
                ),
              ),
              SizedBox(
                width: 240,
                child: TextField(
                  decoration: const InputDecoration(
                    hintText: 'Search batch no. / variant…',
                    prefixIcon: Icon(Icons.search),
                    isDense: true,
                  ),
                  onChanged: (v) => setState(() => _search = v.trim()),
                ),
              ),
              if (_storeId != null)
                IconButton(
                  icon: const Icon(Icons.refresh),
                  tooltip: 'Refresh batches',
                  onPressed: () => ref.invalidate(batchesProvider(_storeId!)),
                ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: _storeId == null
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.inventory_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text('Select a store to view its batches',
                          style: Theme.of(context).textTheme.titleMedium),
                    ],
                  ),
                )
              : Consumer(
                  builder: (context, ref, _) {
                    final batchesAsync = ref.watch(batchesProvider(_storeId!));
                    final zonesAsync = ref.watch(zonesProvider(_storeId!));
                    final zoneNames = <String, String>{
                      for (final z in zonesAsync.value ?? const <ZoneInfo>[])
                        z.id: '${z.name} (${z.code})',
                    };
                    return batchesAsync.when(
                      loading: () => const LoadingView(label: 'Loading batches…'),
                      error: (e, _) => ErrorView(
                        message: 'Could not load batches.',
                        onRetry: () => ref.invalidate(batchesProvider(_storeId!)),
                      ),
                      data: (batches) {
                        var filtered = batches.where((b) {
                          if (_zoneId != null && b.zoneId != _zoneId) return false;
                          if (_materialStatus != null &&
                              b.materialStatus != _materialStatus) return false;
                          if (_search.isNotEmpty &&
                              !b.batchNo.toLowerCase().contains(_search.toLowerCase()) &&
                              !b.variantId.toLowerCase().contains(_search.toLowerCase())) {
                            return false;
                          }
                          return true;
                        }).toList();

                        if (filtered.isEmpty) {
                          return Center(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(Icons.inventory_outlined,
                                    size: 64, color: cs.outlineVariant),
                                const SizedBox(height: 16),
                                Text('No batches found',
                                    style: Theme.of(context).textTheme.titleMedium),
                              ],
                            ),
                          );
                        }

                        return LayoutBuilder(builder: (context, bc) {
                          final wide = bc.maxWidth >= 700;
                          if (wide) {
                            return _BatchWideTable(
                                batches: filtered, zoneNames: zoneNames);
                          }
                          return _BatchNarrowList(
                              batches: filtered, zoneNames: zoneNames);
                        });
                      },
                    );
                  },
                ),
        ),
      ],
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
  String? _zoneId;
  bool _loading = false;
  bool _resolving = false;
  String? _error;
  String? _resolvedLabel;

  @override
  void dispose() {
    _variantCtrl.dispose();
    _qtyCtrl.dispose();
    _costCtrl.dispose();
    _batchCtrl.dispose();
    _expiryCtrl.dispose();
    super.dispose();
  }

  Future<void> _scanVariant() async {
    final code = await scanBarcodeWithCamera(context);
    if (code == null || code.isEmpty || !mounted) return;
    setState(() {
      _resolving = true;
      _resolvedLabel = null;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.product}/catalog/variants/by-barcode/$code');
      final v = resp.data['data'] as Map<String, dynamic>;
      final variantId = v['variantId'] as String? ?? '';
      if (variantId.isEmpty) throw Exception('No product found for "$code"');
      setState(() {
        _variantCtrl.text = variantId;
        _resolvedLabel =
            '${v['productName'] ?? v['sku'] ?? variantId} (${v['sku'] ?? code})';
      });
    } catch (e) {
      setState(() => _error = 'No product found for barcode "$code".');
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
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
          if (_zoneId != null) 'zoneId': _zoneId,
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
    // Read the backend's structured error; fall back to a screen-specific hint.
    final code = apiErrorCode(e);
    if (code == 'INVALID_UUID') return 'Check the variant ID (UUID) and quantity.';
    if (e is DioException && e.response?.statusCode == 404) {
      return 'No variant with that ID exists.';
    }
    return friendlyError(e, fallback: 'Could not receive stock.');
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
                    onChanged: (v) => setState(() {
                      _storeId = v;
                      _zoneId = null;
                    }),
                    validator: (v) => v == null ? 'Required' : null,
                  ),
                ),
                if (_storeId != null) ...[
                  const SizedBox(height: 12),
                  Consumer(
                    builder: (context, ref, _) {
                      final zonesAsync = ref.watch(zonesProvider(_storeId!));
                      return zonesAsync.when(
                        loading: () => const LinearProgressIndicator(),
                        error: (e, _) => const SizedBox.shrink(),
                        data: (zones) => zones.isEmpty
                            ? const SizedBox.shrink()
                            : DropdownButtonFormField<String>(
                                value: _zoneId,
                                isExpanded: true,
                                decoration: const InputDecoration(
                                  labelText: 'Zone / aisle',
                                  prefixIcon: Icon(Icons.grid_view_outlined),
                                ),
                                items: zones
                                    .map((z) => DropdownMenuItem(
                                          value: z.id,
                                          child: Text(
                                              '${z.name} (${z.code})',
                                              overflow: TextOverflow.ellipsis),
                                        ))
                                    .toList(),
                                onChanged: (v) =>
                                    setState(() => _zoneId = v),
                              ),
                      );
                    },
                  ),
                ],
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _variantCtrl,
                        decoration: InputDecoration(
                          labelText: 'Variant ID (UUID) *',
                          prefixIcon: const Icon(Icons.qr_code_2_outlined),
                          helperText: _resolvedLabel != null
                              ? 'Resolved: $_resolvedLabel'
                              : 'Scan a barcode or paste the variant UUID',
                        ),
                        onChanged: (_) =>
                            setState(() => _resolvedLabel = null),
                        validator: (v) =>
                            v == null || v.trim().isEmpty ? 'Required' : null,
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filledTonal(
                      tooltip: 'Scan barcode',
                      onPressed: _resolving ? null : _scanVariant,
                      icon: _resolving
                          ? const SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.camera_alt_outlined),
                    ),
                  ],
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
                          prefixText: '£ ',
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

/// Product name for a variant, falling back to a short UUID while labels resolve.
String _productNameOf(String variantId, Map<String, VariantLabel> labels) {
  final l = labels[variantId];
  if (l != null && l.productName.isNotEmpty) return l.productName;
  final n = variantId.length >= 8 ? variantId.substring(0, 8) : variantId;
  return '$n…';
}

String _skuOf(String variantId, Map<String, VariantLabel> labels) =>
    labels[variantId]?.sku ?? '';

class _WideTable extends StatelessWidget {
  final List<InventoryLevel> levels;
  final Map<String, VariantLabel> labels;
  const _WideTable({required this.levels, required this.labels});

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
            DataColumn(label: Text('Product')),
            DataColumn(label: Text('Store')),
            DataColumn(label: Text('On-Hand'), numeric: true),
            DataColumn(label: Text('Reserved'), numeric: true),
            DataColumn(label: Text('Available'), numeric: true),
            DataColumn(label: Text('Status')),
          ],
          rows: levels.map((l) {
            final isLow = l.isLow;
            final sku = _skuOf(l.variantId, labels);
            return DataRow(
              color: isLow
                  ? WidgetStatePropertyAll(cs.errorContainer.withAlpha(80))
                  : null,
              cells: [
                DataCell(Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(_productNameOf(l.variantId, labels),
                        style: const TextStyle(
                            fontWeight: FontWeight.w600, fontSize: 13)),
                    if (sku.isNotEmpty)
                      Text(sku,
                          style: TextStyle(fontSize: 11, color: cs.outline)),
                  ],
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
                  Semantics(
                    label: isLow ? 'Low stock' : 'Stock OK',
                    child: isLow
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
                        : Text('OK',
                            style: TextStyle(
                                color: context.status.success, fontSize: 12)),
                  ),
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
  final Map<String, VariantLabel> labels;
  const _NarrowList({required this.levels, required this.labels});

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: levels.length,
      separatorBuilder: (_, __) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final l = levels[i];
        final cs = Theme.of(context).colorScheme;
        final sku = _skuOf(l.variantId, labels);
        return Card(
          color: l.isLow ? cs.errorContainer.withAlpha(80) : null,
          child: ListTile(
            leading: Icon(
              Icons.inventory_2_outlined,
              color: l.isLow ? cs.error : cs.primary,
            ),
            title: Text(
              _productNameOf(l.variantId, labels),
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
            ),
            subtitle: Text(
                '${sku.isNotEmpty ? '$sku  ·  ' : ''}On-hand: ${l.onHand.toStringAsFixed(0)}  ·  Reserved: ${l.reserved.toStringAsFixed(0)}'),
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

class _BatchWideTable extends StatelessWidget {
  final List<BatchInfo> batches;
  final Map<String, String> zoneNames;
  const _BatchWideTable({required this.batches, required this.zoneNames});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Card(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            headingRowColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
            columnSpacing: 24,
            columns: const [
              DataColumn(label: Text('Batch No.')),
              DataColumn(label: Text('Variant ID')),
              DataColumn(label: Text('Remaining'), numeric: true),
              DataColumn(label: Text('Zone')),
              DataColumn(label: Text('Expiry')),
              DataColumn(label: Text('Grade')),
              DataColumn(label: Text('Material status')),
            ],
            rows: batches.map((b) {
              return DataRow(
                cells: [
                  DataCell(Text(b.batchNo,
                      style: const TextStyle(fontSize: 12))),
                  DataCell(Text(
                    b.variantId.length > 16
                        ? '${b.variantId.substring(0, 8)}…'
                        : b.variantId,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  )),
                  DataCell(Text(
                      '${b.remainingQty.toStringAsFixed(0)} / ${b.receivedQty.toStringAsFixed(0)}')),
                  DataCell(Text(b.zoneId == null
                      ? '—'
                      : zoneNames[b.zoneId] ?? 'Unassigned')),
                  DataCell(Text(b.expiryDate ?? '—')),
                  DataCell(Text(b.grade ?? '—')),
                  DataCell(_MaterialStatusChip(status: b.materialStatus)),
                ],
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}

class _BatchNarrowList extends StatelessWidget {
  final List<BatchInfo> batches;
  final Map<String, String> zoneNames;
  const _BatchNarrowList({required this.batches, required this.zoneNames});

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: batches.length,
      separatorBuilder: (_, __) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final b = batches[i];
        return Card(
          child: ListTile(
            leading: const Icon(Icons.inventory_outlined),
            title: Text(b.batchNo, style: const TextStyle(fontSize: 13)),
            subtitle: Text(
              '${b.variantId.length > 20 ? '${b.variantId.substring(0, 20)}…' : b.variantId}\n'
              'Zone: ${b.zoneId == null ? '—' : zoneNames[b.zoneId] ?? 'Unassigned'}'
              '${b.expiryDate != null ? '  ·  Exp: ${b.expiryDate}' : ''}',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
            isThreeLine: true,
            trailing: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text('${b.remainingQty.toStringAsFixed(0)} / ${b.receivedQty.toStringAsFixed(0)}'),
                const SizedBox(height: 4),
                _MaterialStatusChip(status: b.materialStatus),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _MaterialStatusChip extends StatelessWidget {
  final String status;
  const _MaterialStatusChip({required this.status});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final statusColors = context.status;
    Color color;
    switch (status) {
      case 'AVAILABLE':
        color = statusColors.success;
        break;
      case 'QUARANTINE':
      case 'HOLD':
        color = statusColors.warning;
        break;
      case 'REJECTED':
        color = cs.error;
        break;
      default:
        color = cs.outline;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withAlpha(30),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(status,
          style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w600)),
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
