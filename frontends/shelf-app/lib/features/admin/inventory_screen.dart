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
import 'providers/inventory_levels_pagination.dart';
import 'inventory_markdown_tab.dart';
import 'inventory_warehouse_tabs.dart';
import '../../shared/util/short_ref.dart';

class InventoryScreen extends ConsumerStatefulWidget {
  const InventoryScreen({super.key});

  @override
  ConsumerState<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends ConsumerState<InventoryScreen> {
  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 6,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.xl,
              AppSpacing.xl,
              AppSpacing.xl,
              0,
            ),
            child: Row(
              children: [
                Text(
                  'Inventory',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
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
              Tab(text: 'Transfers'),
              Tab(text: 'Movements'),
              Tab(text: 'Thresholds'),
              Tab(text: 'Reduce to clear'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _LevelsTab(),
                _BatchesTab(),
                InventoryTransfersTab(),
                InventoryMovementsTab(),
                _ThresholdsTab(),
                InventoryMarkdownTab(),
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
        onReceived: () {
          ref.read(inventoryLevelsPaginationProvider.notifier).refresh();
          ref.invalidate(inventoryLevelsSummaryProvider);
        },
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

  void _refreshLevels() {
    ref.read(inventoryLevelsPaginationProvider.notifier).refresh();
    ref.invalidate(inventoryLevelsSummaryProvider);
    ref.invalidate(thresholdsMapProvider);
    ref.invalidate(thresholdsProvider(''));
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(inventoryLevelsPaginationProvider);
    final summaryAsync = ref.watch(inventoryLevelsSummaryProvider);
    final labels =
        ref.watch(inventoryVariantLabelsProvider).value ??
        const <String, VariantLabel>{};
    final thresholds =
        ref.watch(thresholdsMapProvider).value ?? const <String, double>{};
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Search + filter bar
        Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.xl,
            AppSpacing.lg,
            AppSpacing.xl,
            0,
          ),
          child: Row(
            children: [
              Expanded(
                child: SearchBar(
                  hintText: 'Search product, SKU or ID…',
                  leading: const Icon(Icons.search),
                  onChanged: (v) => setState(() => _search = v.trim()),
                ),
              ),
              const SizedBox(width: 12),
              FilterChip(
                label: const Text('Low stock'),
                avatar: Icon(
                  Icons.warning_amber_outlined,
                  size: 14,
                  color: _lowOnly ? cs.onError : null,
                ),
                selected: _lowOnly,
                selectedColor: cs.errorContainer,
                onSelected: (v) => setState(() => _lowOnly = v),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh inventory',
                onPressed: _refreshLevels,
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // Summary strip — tenant-wide totals from the server-side aggregate, so
        // the counts stay accurate regardless of how many pages are loaded.
        summaryAsync.when(
          loading: () => const SizedBox.shrink(),
          error: (_, _) => const SizedBox.shrink(),
          data: (summary) {
            return Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Row(
                children: [
                  _SummaryChip(
                    icon: Icons.inventory_2_outlined,
                    label: '${summary.skuCount} SKUs',
                    color: cs.secondaryContainer,
                  ),
                  const SizedBox(width: 8),
                  if (summary.lowStockCount > 0)
                    _SummaryChip(
                      icon: Icons.warning_amber_outlined,
                      label: '${summary.lowStockCount} low stock',
                      color: cs.errorContainer,
                    ),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 12),

        // Table — one cursor page at a time; free-text search / low-stock filtering
        // stays a client-side filter over the rows loaded so far.
        Expanded(
          child: Builder(
            builder: (context) {
              if (page.isLoadingInitial) {
                return const LoadingView(label: 'Loading inventory…');
              }
              if (page.error != null && page.levels.isEmpty) {
                return ErrorView(
                  message: 'Could not load inventory levels.',
                  onRetry: () => ref
                      .read(inventoryLevelsPaginationProvider.notifier)
                      .refresh(),
                );
              }

              final filtered = page.levels.where((l) {
                if (_lowOnly && !l.isLowAgainst(thresholds)) return false;
                if (_search.isNotEmpty) {
                  final label = labels[l.variantId];
                  final hay =
                      '${label?.productName ?? ''} ${label?.sku ?? ''} ${l.variantId}'
                          .toLowerCase();
                  if (!hay.contains(_search.toLowerCase())) return false;
                }
                return true;
              }).toList();

              final loadMore = (page.hasMore || page.isLoadingMore)
                  ? Padding(
                      padding: const EdgeInsets.all(12),
                      child: page.isLoadingMore
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : OutlinedButton(
                              onPressed: () => ref
                                  .read(
                                    inventoryLevelsPaginationProvider.notifier,
                                  )
                                  .loadMore(),
                              child: const Text('Load more'),
                            ),
                    )
                  : null;

              if (filtered.isEmpty) {
                final filtering = _search.isNotEmpty || _lowOnly;
                return Column(
                  children: [
                    Expanded(
                      child: Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.inventory_2_outlined,
                              size: 64,
                              color: cs.outlineVariant,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              filtering
                                  ? 'No matches on loaded items'
                                  : 'No items found',
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            if (filtering)
                              TextButton(
                                onPressed: () => setState(() {
                                  _search = '';
                                  _lowOnly = false;
                                }),
                                child: const Text('Clear filters'),
                              ),
                          ],
                        ),
                      ),
                    ),
                    ?loadMore,
                  ],
                );
              }

              return Column(
                children: [
                  Expanded(
                    child: LayoutBuilder(
                      builder: (context, bc) {
                        final wide = bc.maxWidth >= 600;
                        if (wide) {
                          return _WideTable(
                            levels: filtered,
                            labels: labels,
                            thresholds: thresholds,
                            onChanged: _refreshLevels,
                          );
                        }
                        return _NarrowList(
                          levels: filtered,
                          labels: labels,
                          thresholds: thresholds,
                          onChanged: _refreshLevels,
                        );
                      },
                    ),
                  ),
                  ?loadMore,
                ],
              );
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
            AppSpacing.xl,
            AppSpacing.lg,
            AppSpacing.xl,
            0,
          ),
          child: Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              SizedBox(
                width: 220,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    'Could not load stores',
                    style: TextStyle(color: cs.error),
                  ),
                  data: (stores) => DropdownButtonFormField<String>(
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      isDense: true,
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: stores
                        .map(
                          (s) => DropdownMenuItem(
                            value: s.id,
                            child: Text(
                              '${s.name} (${s.code})',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        )
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
                        error: (_, _) => const SizedBox.shrink(),
                        data: (zones) => DropdownButtonFormField<String?>(
                          initialValue: _zoneId,
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: 'Zone',
                            isDense: true,
                            prefixIcon: Icon(Icons.grid_view_outlined),
                          ),
                          items: [
                            const DropdownMenuItem(
                              value: null,
                              child: Text('All zones'),
                            ),
                            ...zones.map(
                              (z) => DropdownMenuItem(
                                value: z.id,
                                child: Text(
                                  '${z.name} (${z.code})',
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            ),
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
                  initialValue: _materialStatus,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Material status',
                    isDense: true,
                  ),
                  items: const [
                    DropdownMenuItem(value: null, child: Text('Any status')),
                    DropdownMenuItem(
                      value: 'AVAILABLE',
                      child: Text('Available'),
                    ),
                    DropdownMenuItem(
                      value: 'QUARANTINE',
                      child: Text('Quarantine'),
                    ),
                    DropdownMenuItem(
                      value: 'REJECTED',
                      child: Text('Rejected'),
                    ),
                    DropdownMenuItem(value: 'HOLD', child: Text('Hold')),
                  ],
                  onChanged: (v) => setState(() => _materialStatus = v),
                ),
              ),
              SizedBox(
                width: 240,
                child: SearchBar(
                  hintText: 'Search batch no. / variant…',
                  leading: const Icon(Icons.search),
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
        if (_storeId != null) ...[
          const SizedBox(height: 8),
          _ExpiringBanner(storeId: _storeId!),
        ],
        const SizedBox(height: 8),
        Expanded(
          child: _storeId == null
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.inventory_outlined,
                        size: 64,
                        color: cs.outlineVariant,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'Select a store to view its batches',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
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
                      loading: () =>
                          const LoadingView(label: 'Loading batches…'),
                      error: (e, _) => ErrorView(
                        message: 'Could not load batches.',
                        onRetry: () =>
                            ref.invalidate(batchesProvider(_storeId!)),
                      ),
                      data: (batches) {
                        var filtered = batches.where((b) {
                          if (_zoneId != null && b.zoneId != _zoneId) {
                            return false;
                          }
                          if (_materialStatus != null &&
                              b.materialStatus != _materialStatus) {
                            return false;
                          }
                          if (_search.isNotEmpty &&
                              !b.batchNo.toLowerCase().contains(
                                _search.toLowerCase(),
                              ) &&
                              !b.variantId.toLowerCase().contains(
                                _search.toLowerCase(),
                              )) {
                            return false;
                          }
                          return true;
                        }).toList();

                        if (filtered.isEmpty) {
                          return Center(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  Icons.inventory_outlined,
                                  size: 64,
                                  color: cs.outlineVariant,
                                ),
                                const SizedBox(height: 16),
                                Text(
                                  'No batches found',
                                  style: Theme.of(
                                    context,
                                  ).textTheme.titleMedium,
                                ),
                              ],
                            ),
                          );
                        }

                        void refresh() =>
                            ref.invalidate(batchesProvider(_storeId!));

                        return LayoutBuilder(
                          builder: (context, bc) {
                            final wide = bc.maxWidth >= 700;
                            if (wide) {
                              return _BatchWideTable(
                                batches: filtered,
                                zoneNames: zoneNames,
                                onMaterialStatus: (b) =>
                                    showMaterialStatusDialog(
                                      context,
                                      ref,
                                      batch: b,
                                      onChanged: refresh,
                                    ),
                              );
                            }
                            return _BatchNarrowList(
                              batches: filtered,
                              zoneNames: zoneNames,
                              onMaterialStatus: (b) => showMaterialStatusDialog(
                                context,
                                ref,
                                batch: b,
                                onChanged: refresh,
                              ),
                            );
                          },
                        );
                      },
                    );
                  },
                ),
        ),
      ],
    );
  }
}

/// Banner of batches expiring within 30 days for the selected store.
class _ExpiringBanner extends ConsumerStatefulWidget {
  final String storeId;
  const _ExpiringBanner({required this.storeId});

  @override
  ConsumerState<_ExpiringBanner> createState() => _ExpiringBannerState();
}

class _ExpiringBannerState extends ConsumerState<_ExpiringBanner> {
  bool _dismissed = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(
      expiringBatchesProvider((storeId: widget.storeId, withinDays: 30)),
    );
    return async.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => const SizedBox.shrink(),
      data: (rows) {
        if (rows.isEmpty || _dismissed) return const SizedBox.shrink();
        final cs = Theme.of(context).colorScheme;
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
          child: MaterialBanner(
            backgroundColor: cs.errorContainer.withValues(alpha: 0.45),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            leading: Icon(
              Icons.event_busy,
              size: 18,
              color: cs.onErrorContainer,
            ),
            content: Text(
              '${rows.length} batch(es) expiring within 30 days'
              ' — e.g. ${rows.first.batchNo}'
              '${rows.first.expiryDate != null ? ' (${rows.first.expiryDate})' : ''}',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: cs.onErrorContainer),
            ),
            actions: [
              TextButton(
                onPressed: () => setState(() => _dismissed = true),
                child: Text(
                  'Dismiss',
                  style: TextStyle(color: cs.onErrorContainer),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ReceiveStockDialog extends ConsumerStatefulWidget {
  final VoidCallback onReceived;
  const _ReceiveStockDialog({required this.onReceived});

  @override
  ConsumerState<_ReceiveStockDialog> createState() =>
      _ReceiveStockDialogState();
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
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('/${ApiConstants.product}/catalog/variants/by-barcode/$code');
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
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.inventory}/admin/inventory/receive',
            data: {
              'storeId': _storeId,
              'variantId': _variantCtrl.text.trim(),
              'qty': double.parse(_qtyCtrl.text.trim()),
              if (_batchCtrl.text.trim().isNotEmpty)
                'batchNo': _batchCtrl.text.trim(),
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
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Stock received.')));
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
    if (code == 'INVALID_UUID') {
      return 'Check the variant ID (UUID) and quantity.';
    }
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
                    child: Text(
                      _error!,
                      style: TextStyle(color: cs.onErrorContainer),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load stores.'),
                    style: TextStyle(color: cs.error),
                  ),
                  data: (stores) => DropdownButtonFormField<String>(
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store *',
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: stores
                        .map(
                          (s) => DropdownMenuItem(
                            value: s.id,
                            child: Text(
                              '${s.name} (${s.code})',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        )
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
                                initialValue: _zoneId,
                                isExpanded: true,
                                decoration: const InputDecoration(
                                  labelText: 'Zone / aisle',
                                  prefixIcon: Icon(Icons.grid_view_outlined),
                                ),
                                items: zones
                                    .map(
                                      (z) => DropdownMenuItem(
                                        value: z.id,
                                        child: Text(
                                          '${z.name} (${z.code})',
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                    )
                                    .toList(),
                                onChanged: (v) => setState(() => _zoneId = v),
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
                        onChanged: (_) => setState(() => _resolvedLabel = null),
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
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
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
                          decimal: true,
                        ),
                        decoration: const InputDecoration(
                          labelText: 'Quantity *',
                        ),
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
                          decimal: true,
                        ),
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
                        decoration: const InputDecoration(
                          labelText: 'Batch no.',
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _expiryCtrl,
                        readOnly: true,
                        decoration: const InputDecoration(
                          labelText: 'Expiry',
                          suffixIcon: Icon(Icons.calendar_today_outlined),
                        ),
                        onTap: () async {
                          final now = DateTime.now();
                          final picked = await showDatePicker(
                            context: context,
                            initialDate:
                                DateTime.tryParse(_expiryCtrl.text) ?? now,
                            firstDate: DateTime(now.year - 5),
                            lastDate: DateTime(now.year + 20),
                          );
                          if (picked != null) {
                            _expiryCtrl.text =
                                '${picked.year.toString().padLeft(4, '0')}-'
                                '${picked.month.toString().padLeft(2, '0')}-'
                                '${picked.day.toString().padLeft(2, '0')}';
                          }
                        },
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
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
  return '…${shortRef(variantId)}';
}

String _skuOf(String variantId, Map<String, VariantLabel> labels) =>
    labels[variantId]?.sku ?? '';

void _showAdjustDialog(
  BuildContext context,
  InventoryLevel level,
  VoidCallback onChanged,
) {
  showDialog(
    context: context,
    builder: (_) => _AdjustStockDialog(level: level, onDone: onChanged),
  );
}

void _showSetThresholdDialog(
  BuildContext context,
  InventoryLevel level,
  VoidCallback onChanged,
) {
  showDialog(
    context: context,
    builder: (_) => _SetThresholdDialog(
      storeId: level.storeId,
      variantId: level.variantId,
      onDone: onChanged,
    ),
  );
}

class _WideTable extends StatelessWidget {
  final List<InventoryLevel> levels;
  final Map<String, VariantLabel> labels;
  final Map<String, double> thresholds;
  final VoidCallback onChanged;
  const _WideTable({
    required this.levels,
    required this.labels,
    required this.thresholds,
    required this.onChanged,
  });

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
            DataColumn(label: Text('')),
          ],
          rows: levels.map((l) {
            final isLow = l.isLowAgainst(thresholds);
            final sku = _skuOf(l.variantId, labels);
            final threshold = thresholds['${l.storeId}:${l.variantId}'];
            return DataRow(
              color: isLow
                  ? WidgetStatePropertyAll(cs.errorContainer.withAlpha(80))
                  : null,
              cells: [
                DataCell(
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _productNameOf(l.variantId, labels),
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 13,
                        ),
                      ),
                      if (sku.isNotEmpty)
                        Text(
                          sku,
                          style: TextStyle(fontSize: 11, color: cs.outline),
                        ),
                    ],
                  ),
                ),
                DataCell(
                  Text(
                    shortRef(l.storeId),
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 12,
                    ),
                  ),
                ),
                DataCell(Text(l.onHand.toStringAsFixed(0))),
                DataCell(Text(l.reserved.toStringAsFixed(0))),
                DataCell(
                  Text(
                    l.available.toStringAsFixed(0),
                    style: TextStyle(
                      color: isLow ? cs.error : cs.onSurface,
                      fontWeight: isLow ? FontWeight.bold : null,
                    ),
                  ),
                ),
                DataCell(
                  Semantics(
                    label: isLow ? 'Low stock' : 'Stock OK',
                    child: isLow
                        ? Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(
                                Icons.warning_amber_outlined,
                                size: 14,
                                color: cs.error,
                              ),
                              const SizedBox(width: 4),
                              Text(
                                threshold != null
                                    ? 'Low (≤${threshold.toStringAsFixed(0)})'
                                    : 'Low',
                                style: TextStyle(
                                  color: cs.error,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          )
                        : Text(
                            'OK',
                            style: TextStyle(
                              color: context.status.success,
                              fontSize: 12,
                            ),
                          ),
                  ),
                ),
                DataCell(
                  PopupMenuButton<String>(
                    tooltip: 'Actions',
                    onSelected: (v) {
                      if (v == 'adjust') {
                        _showAdjustDialog(context, l, onChanged);
                      } else if (v == 'threshold') {
                        _showSetThresholdDialog(context, l, onChanged);
                      }
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(
                        value: 'adjust',
                        child: Text('Adjust stock'),
                      ),
                      PopupMenuItem(
                        value: 'threshold',
                        child: Text('Set reorder level'),
                      ),
                    ],
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
  final Map<String, double> thresholds;
  final VoidCallback onChanged;
  const _NarrowList({
    required this.levels,
    required this.labels,
    required this.thresholds,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: levels.length,
      separatorBuilder: (_, _) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final l = levels[i];
        final cs = Theme.of(context).colorScheme;
        final sku = _skuOf(l.variantId, labels);
        final isLow = l.isLowAgainst(thresholds);
        return Card(
          color: isLow ? cs.errorContainer.withAlpha(80) : null,
          child: ListTile(
            leading: Icon(
              Icons.inventory_2_outlined,
              color: isLow ? cs.error : cs.primary,
            ),
            title: Text(
              _productNameOf(l.variantId, labels),
              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
            ),
            subtitle: Text(
              '${sku.isNotEmpty ? '$sku  ·  ' : ''}On-hand: ${l.onHand.toStringAsFixed(0)}  ·  Reserved: ${l.reserved.toStringAsFixed(0)}',
            ),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  'Avail: ${l.available.toStringAsFixed(0)}',
                  style: TextStyle(
                    color: isLow ? cs.error : cs.onSurface,
                    fontWeight: isLow ? FontWeight.bold : null,
                  ),
                ),
                PopupMenuButton<String>(
                  tooltip: 'Actions',
                  onSelected: (v) {
                    if (v == 'adjust') {
                      _showAdjustDialog(context, l, onChanged);
                    } else if (v == 'threshold') {
                      _showSetThresholdDialog(context, l, onChanged);
                    }
                  },
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'adjust', child: Text('Adjust stock')),
                    PopupMenuItem(
                      value: 'threshold',
                      child: Text('Set reorder level'),
                    ),
                  ],
                ),
              ],
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
  final void Function(BatchInfo batch) onMaterialStatus;
  const _BatchWideTable({
    required this.batches,
    required this.zoneNames,
    required this.onMaterialStatus,
  });

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
              DataColumn(label: Text('')),
            ],
            rows: batches.map((b) {
              return DataRow(
                cells: [
                  DataCell(
                    Text(b.batchNo, style: const TextStyle(fontSize: 12)),
                  ),
                  DataCell(
                    Text(
                      b.variantId.length > 16
                          ? '…${shortRef(b.variantId)}'
                          : b.variantId,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 12,
                      ),
                    ),
                  ),
                  DataCell(
                    Text(
                      '${b.remainingQty.toStringAsFixed(0)} / ${b.receivedQty.toStringAsFixed(0)}',
                    ),
                  ),
                  DataCell(
                    Text(
                      b.zoneId == null
                          ? '—'
                          : zoneNames[b.zoneId] ?? 'Unassigned',
                    ),
                  ),
                  DataCell(Text(b.expiryDate ?? '—')),
                  DataCell(Text(b.grade ?? '—')),
                  DataCell(_MaterialStatusChip(status: b.materialStatus)),
                  DataCell(
                    IconButton(
                      icon: const Icon(Icons.tune, size: 18),
                      tooltip: 'Change material status',
                      onPressed: () => onMaterialStatus(b),
                    ),
                  ),
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
  final void Function(BatchInfo batch) onMaterialStatus;
  const _BatchNarrowList({
    required this.batches,
    required this.zoneNames,
    required this.onMaterialStatus,
  });

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      itemCount: batches.length,
      separatorBuilder: (_, _) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final b = batches[i];
        return Card(
          child: ListTile(
            leading: const Icon(Icons.inventory_outlined),
            title: Text(b.batchNo, style: const TextStyle(fontSize: 13)),
            subtitle: Text(
              '${b.variantId.length > 20 ? '…${shortRef(b.variantId, length: 20)}' : b.variantId}\n'
              'Zone: ${b.zoneId == null ? '—' : zoneNames[b.zoneId] ?? 'Unassigned'}'
              '${b.expiryDate != null ? '  ·  Exp: ${b.expiryDate}' : ''}',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
            isThreeLine: true,
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Text(
                      '${b.remainingQty.toStringAsFixed(0)} / ${b.receivedQty.toStringAsFixed(0)}',
                    ),
                    const SizedBox(height: 4),
                    _MaterialStatusChip(status: b.materialStatus),
                  ],
                ),
                IconButton(
                  icon: const Icon(Icons.tune, size: 18),
                  tooltip: 'Change material status',
                  onPressed: () => onMaterialStatus(b),
                ),
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
      child: Text(
        status,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _SummaryChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;

  const _SummaryChip({
    required this.icon,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14),
          const SizedBox(width: 6),
          Text(
            label,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

// ── Adjust stock ─────────────────────────────────────────────────────────────

String _newIdempotencyKey() {
  final ms = DateTime.now().microsecondsSinceEpoch.toRadixString(16);
  final r = (ms.hashCode & 0xFFFFFFFF).toRadixString(16).padLeft(8, '0');
  return '$ms-$r-adjust';
}

class _AdjustStockDialog extends ConsumerStatefulWidget {
  final InventoryLevel level;
  final VoidCallback onDone;
  const _AdjustStockDialog({required this.level, required this.onDone});

  @override
  ConsumerState<_AdjustStockDialog> createState() => _AdjustStockDialogState();
}

class _AdjustStockDialogState extends ConsumerState<_AdjustStockDialog> {
  final _formKey = GlobalKey<FormState>();
  final _deltaCtrl = TextEditingController();
  final _reasonCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _deltaCtrl.dispose();
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.inventory}/admin/inventory/adjust',
            data: {
              'storeId': widget.level.storeId,
              'variantId': widget.level.variantId,
              'delta': double.parse(_deltaCtrl.text.trim()),
              if (_reasonCtrl.text.trim().isNotEmpty)
                'reason': _reasonCtrl.text.trim(),
            },
            options: Options(
              headers: {'Idempotency-Key': _newIdempotencyKey()},
            ),
          );
      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Stock adjusted.')));
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not adjust stock.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final l = widget.level;
    return AlertDialog(
      title: const Text('Adjust stock'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _error!,
                    style: TextStyle(color: cs.onErrorContainer),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              Text(
                'Available: ${l.available.toStringAsFixed(0)}  ·  On-hand: ${l.onHand.toStringAsFixed(0)}',
                style: TextStyle(color: cs.outline, fontSize: 13),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _deltaCtrl,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                  signed: true,
                ),
                decoration: const InputDecoration(
                  labelText: 'Delta *',
                  helperText: 'Positive adds stock, negative removes',
                  prefixIcon: Icon(Icons.exposure_outlined),
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Required';
                  final n = double.tryParse(v.trim());
                  if (n == null || n == 0) return 'Non-zero number';
                  return null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _reasonCtrl,
                decoration: const InputDecoration(
                  labelText: 'Reason',
                  hintText: 'e.g. shrinkage, damage, found stock',
                  prefixIcon: Icon(Icons.notes_outlined),
                ),
              ),
            ],
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Adjust'),
        ),
      ],
    );
  }
}

// ── Set reorder level ────────────────────────────────────────────────────────

class _SetThresholdDialog extends ConsumerStatefulWidget {
  final String storeId;
  final String variantId;
  final VoidCallback onDone;
  const _SetThresholdDialog({
    required this.storeId,
    required this.variantId,
    required this.onDone,
  });

  @override
  ConsumerState<_SetThresholdDialog> createState() =>
      _SetThresholdDialogState();
}

class _SetThresholdDialogState extends ConsumerState<_SetThresholdDialog> {
  final _formKey = GlobalKey<FormState>();
  final _thresholdCtrl = TextEditingController();
  final _maxQtyCtrl = TextEditingController();
  final _variantCtrl = TextEditingController();
  String? _storeId;
  bool _loading = false;
  String? _error;

  bool get _needsStorePick => widget.storeId.isEmpty;
  bool get _needsVariantPick => widget.variantId.isEmpty;

  @override
  void initState() {
    super.initState();
    _storeId = widget.storeId.isEmpty ? null : widget.storeId;
    if (widget.variantId.isNotEmpty) {
      _variantCtrl.text = widget.variantId;
    }
  }

  @override
  void dispose() {
    _thresholdCtrl.dispose();
    _maxQtyCtrl.dispose();
    _variantCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final storeId = _storeId ?? widget.storeId;
    final variantId = _variantCtrl.text.trim().isNotEmpty
        ? _variantCtrl.text.trim()
        : widget.variantId;
    if (storeId.isEmpty) {
      setState(() => _error = 'Select a store.');
      return;
    }
    if (variantId.isEmpty) {
      setState(() => _error = 'Enter a variant ID.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.inventory}/admin/inventory/thresholds',
            data: {
              'storeId': storeId,
              'variantId': variantId,
              'threshold': double.parse(_thresholdCtrl.text.trim()),
              if (_maxQtyCtrl.text.trim().isNotEmpty)
                'maxQty': double.parse(_maxQtyCtrl.text.trim()),
            },
          );
      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Reorder level saved.')));
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not set reorder level.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Set reorder level'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (_error != null) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Text(
                      _error!,
                      style: TextStyle(color: cs.onErrorContainer),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                Text(
                  'Raises a low-stock signal when available qty is at or below this level.',
                  style: TextStyle(color: cs.outline, fontSize: 13),
                ),
                if (_needsStorePick) ...[
                  const SizedBox(height: 12),
                  storesAsync.when(
                    loading: () => const LinearProgressIndicator(),
                    error: (e, _) => Text(
                      friendlyError(e, fallback: 'Could not load stores.'),
                      style: TextStyle(color: cs.error),
                    ),
                    data: (stores) => DropdownButtonFormField<String>(
                      initialValue: _storeId,
                      isExpanded: true,
                      decoration: const InputDecoration(
                        labelText: 'Store *',
                        prefixIcon: Icon(Icons.store_outlined),
                      ),
                      items: stores
                          .map(
                            (s) => DropdownMenuItem(
                              value: s.id,
                              child: Text(
                                '${s.name} (${s.code})',
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          )
                          .toList(),
                      onChanged: (v) => setState(() => _storeId = v),
                      validator: (v) => v == null ? 'Required' : null,
                    ),
                  ),
                ],
                if (_needsVariantPick) ...[
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
                ],
                const SizedBox(height: 12),
                TextFormField(
                  controller: _thresholdCtrl,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(
                    labelText: 'Threshold *',
                    prefixIcon: Icon(Icons.vertical_align_bottom),
                  ),
                  validator: (v) {
                    if (v == null || v.trim().isEmpty) return 'Required';
                    final n = double.tryParse(v.trim());
                    if (n == null || n <= 0) return '> 0';
                    return null;
                  },
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _maxQtyCtrl,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(
                    labelText: 'Max qty (optional)',
                    helperText: 'Cap on suggested replenishment qty',
                    prefixIcon: Icon(Icons.vertical_align_top),
                  ),
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Save'),
        ),
      ],
    );
  }
}

// ── Thresholds tab ───────────────────────────────────────────────────────────

class _ThresholdsTab extends ConsumerStatefulWidget {
  const _ThresholdsTab();

  @override
  ConsumerState<_ThresholdsTab> createState() => _ThresholdsTabState();
}

class _ThresholdsTabState extends ConsumerState<_ThresholdsTab> {
  String? _storeId;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    final storeKey = _storeId ?? '';
    final async = ref.watch(thresholdsProvider(storeKey));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.xl,
            AppSpacing.lg,
            AppSpacing.xl,
            0,
          ),
          child: Row(
            children: [
              SizedBox(
                width: 240,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load stores.'),
                    style: TextStyle(color: cs.error),
                  ),
                  data: (stores) => DropdownButtonFormField<String?>(
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      isDense: true,
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: [
                      const DropdownMenuItem(
                        value: null,
                        child: Text('All stores'),
                      ),
                      ...stores.map(
                        (s) => DropdownMenuItem(
                          value: s.id,
                          child: Text(
                            '${s.name} (${s.code})',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                    ],
                    onChanged: (v) => setState(() => _storeId = v),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh thresholds',
                onPressed: () {
                  ref.invalidate(thresholdsProvider(storeKey));
                  ref.invalidate(thresholdsMapProvider);
                },
              ),
              const Spacer(),
              FilledButton.icon(
                onPressed: () => showDialog(
                  context: context,
                  builder: (_) => _SetThresholdDialog(
                    storeId: _storeId ?? '',
                    variantId: '',
                    onDone: () {
                      ref.invalidate(thresholdsProvider(storeKey));
                      ref.invalidate(thresholdsMapProvider);
                    },
                  ),
                ),
                icon: const Icon(Icons.add),
                label: const Text('Set reorder level'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: async.when(
            loading: () =>
                const LoadingView(label: 'Loading reorder thresholds…'),
            error: (e, _) => ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load reorder thresholds.',
              ),
              onRetry: () => ref.invalidate(thresholdsProvider(storeKey)),
            ),
            data: (rows) {
              if (rows.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.tune, size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text(
                        'No reorder thresholds set',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Set a reorder level on a stock row, or use the button above.',
                        style: TextStyle(color: cs.outline),
                      ),
                    ],
                  ),
                );
              }
              final labels =
                  ref
                      .watch(
                        variantLabelsProvider(
                          variantIdsKey(rows.map((r) => r.variantId)),
                        ),
                      )
                      .value ??
                  const <String, VariantLabel>{};
              return ListView.separated(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 8,
                ),
                itemCount: rows.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final t = rows[i];
                  final sku = _skuOf(t.variantId, labels);
                  return Card(
                    child: ListTile(
                      leading: Icon(
                        Icons.vertical_align_bottom,
                        color: cs.primary,
                      ),
                      title: Text(
                        _productNameOf(t.variantId, labels),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: Text(
                        [
                          if (sku.isNotEmpty) sku,
                          'Store ${shortRef(t.storeId)}',
                          if (t.maxQty != null)
                            'max ${t.maxQty!.toStringAsFixed(0)}',
                        ].join(' · '),
                      ),
                      trailing: Text(
                        '≤ ${t.threshold.toStringAsFixed(0)}',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      onTap: () => showDialog(
                        context: context,
                        builder: (_) => _SetThresholdDialog(
                          storeId: t.storeId,
                          variantId: t.variantId,
                          onDone: () {
                            ref.invalidate(thresholdsProvider(storeKey));
                            ref.invalidate(thresholdsMapProvider);
                          },
                        ),
                      ),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}
