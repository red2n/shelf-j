import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/theme.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import '../../shared/widgets/empty_state.dart';

/// The statistical demand forecast (06.x) for a store: run it, read what it
/// expects of each item over the next week and month, and how sure it is.
/// Accuracy the server could not honestly compute is a dash, never a zero.
class InventoryForecastTab extends ConsumerStatefulWidget {
  const InventoryForecastTab({super.key});

  @override
  ConsumerState<InventoryForecastTab> createState() => _InventoryForecastTabState();
}

class _InventoryForecastTabState extends ConsumerState<InventoryForecastTab> {
  String? _storeId;
  bool _running = false;

  Future<void> _run(String storeId) async {
    setState(() => _running = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.inventory}/admin/inventory/forecasts/run',
            data: {'storeId': storeId, 'horizonDays': 28},
          );
      final d = (resp.data['data'] as Map<String, dynamic>?) ?? {};
      final byMethod = (d['byMethod'] as Map<String, dynamic>?) ?? {};
      final methods = byMethod.entries.map((e) => '${e.key} ${e.value}').join(', ');
      final mape = d['meanMape'];
      messenger.showSnackBar(SnackBar(
        content: Text(d['variants'] == 0
            ? 'Nothing to forecast yet: no day of history at this store.'
            : 'Forecast ${d['variants']} variants — $methods'
                '${mape == null ? '' : ' · mean MAPE ${(mape as num).toStringAsFixed(1)}%'}'),
      ));
      ref.invalidate(forecastsProvider(storeId));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: 'The forecast could not run.'))));
    } finally {
      if (mounted) setState(() => _running = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    final stores = storesAsync.value ?? const [];
    final storeId = _storeId ?? (stores.isNotEmpty ? stores.first.id : null);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Wrap(
            spacing: 12,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 260,
                child: DropdownButtonFormField<String>(
                  key: const Key('forecast-store'),
                  initialValue: storeId,
                  decoration: const InputDecoration(labelText: 'Store', isDense: true),
                  items: stores
                      .map((s) => DropdownMenuItem(value: s.id, child: Text(s.name)))
                      .toList(),
                  onChanged: (v) => setState(() => _storeId = v),
                ),
              ),
              FilledButton.icon(
                key: const Key('forecast-run'),
                onPressed: storeId == null || _running ? null : () => _run(storeId),
                icon: _running
                    ? const SizedBox(
                        width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.auto_graph),
                label: const Text('Run forecast'),
              ),
              if (storeId != null)
                IconButton(
                  tooltip: 'Refresh',
                  onPressed: () => ref.invalidate(forecastsProvider(storeId)),
                  icon: const Icon(Icons.refresh),
                ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            'Expected demand per item over the next week and month, from the store\'s own sales '
            'history. SES is smoothing with a weekday profile; Croston (SBA) is for items that sell on '
            'fewer than three days in four; a mean means under fourteen days of history. MAPE and bias '
            'come from forecasting the last quarter of the history from the rest.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: storeId == null
              ? const EmptyState(title: 'Add a store to forecast its demand.')
              : ref.watch(forecastsProvider(storeId)).when(
                    loading: () => const LoadingView(label: 'Loading forecasts…'),
                    error: (e, _) => ErrorView(
                      message: friendlyError(e, fallback: 'Could not load forecasts.'),
                      onRetry: () => ref.invalidate(forecastsProvider(storeId)),
                    ),
                    data: (rows) => rows.isEmpty
                        ? const Center(
                            child: Padding(
                              padding: EdgeInsets.all(24),
                              child: Text(
                                'No forecasts for this store yet. Run the forecast to read its demand history.',
                                textAlign: TextAlign.center,
                              ),
                            ),
                          )
                        : _ForecastTable(rows: rows, storeId: storeId),
                  ),
        ),
      ],
    );
  }
}

String _fmt(double? v, {int decimals = 1, String suffix = ''}) =>
    v == null ? '—' : '${v.toStringAsFixed(decimals)}$suffix';

class _ForecastTable extends ConsumerWidget {
  final List<DemandForecastRow> rows;
  final String storeId;
  const _ForecastTable({required this.rows, required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Card(
        child: DataTable(
          headingRowColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
          columnSpacing: 20,
          showCheckboxColumn: false,
          columns: const [
            DataColumn(label: Text('Variant')),
            DataColumn(label: Text('Method')),
            DataColumn(label: Text('Next 7 d'), numeric: true),
            DataColumn(label: Text('Next 28 d'), numeric: true),
            DataColumn(label: Text('MAPE %'), numeric: true),
            DataColumn(label: Text('Bias %'), numeric: true),
            DataColumn(label: Text('History'), numeric: true),
          ],
          rows: rows
              .map((r) => DataRow(
                    onSelectChanged: (_) => _showDetail(context, r),
                    cells: [
                      DataCell(Text('…${shortRef(r.variantId)}')),
                      DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                        Text(r.method),
                        if (r.fresh)
                          Padding(
                            padding: const EdgeInsets.only(left: 6),
                            child: Tooltip(
                              message:
                                  'Lives ${r.shelfLifeDays} days by its batches; an order covers no more'
                                  '${r.wasteRatePct == null ? '' : ' · ${r.wasteRatePct!.toStringAsFixed(1)}% went out of date unsold'}',
                              child: Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                decoration: BoxDecoration(
                                  color: cs.secondaryContainer,
                                  borderRadius: AppRadius.badge,
                                ),
                                child: Text('Fresh · ${r.shelfLifeDays} d',
                                    style: TextStyle(
                                        fontSize: 11,
                                        fontWeight: FontWeight.w600,
                                        color: cs.onSecondaryContainer)),
                              ),
                            ),
                          ),
                        if (r.intermittent)
                          Padding(
                            padding: const EdgeInsets.only(left: 6),
                            child: Tooltip(
                              message: 'Sells on fewer than three days in four',
                              child: Icon(Icons.scatter_plot_outlined,
                                  size: 16, color: cs.onSurfaceVariant),
                            ),
                          ),
                      ])),
                      DataCell(Text(_fmt(r.next7))),
                      DataCell(Text(_fmt(r.next28),
                          style: const TextStyle(fontWeight: FontWeight.bold))),
                      DataCell(Text(_fmt(r.mape))),
                      DataCell(Text(_fmt(r.bias))),
                      DataCell(Text('${r.historyDays} d')),
                    ],
                  ))
              .toList(),
        ),
      ),
    );
  }

  void _showDetail(BuildContext context, DemandForecastRow row) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (ctx) => _ForecastDetail(storeId: storeId, variantId: row.variantId),
    );
  }
}

class _ForecastDetail extends ConsumerWidget {
  final String storeId;
  final String variantId;
  const _ForecastDetail({required this.storeId, required this.variantId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(forecastDetailProvider('$storeId/$variantId'));
    return async.when(
      loading: () => const SizedBox(height: 240, child: LoadingView(label: 'Loading forecast…')),
      error: (e, _) => SizedBox(
        height: 240,
        child: ErrorView(
          message: friendlyError(e, fallback: 'Could not load the forecast.'),
          onRetry: () => ref.invalidate(forecastDetailProvider('$storeId/$variantId')),
        ),
      ),
      data: (f) => ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        children: [
          Text('Variant …${shortRef(f.variantId)} · ${f.method}'
              '${f.alpha == null ? '' : ' · α ${f.alpha!.toStringAsFixed(2)}'}',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 4),
          Text('${f.historyDays} days of history to ${f.fromDay}; ${f.horizonDays} days ahead. '
              'Tested on the last ${f.holdoutDays} days: MAPE ${_fmt(f.mape, suffix: '%')}, '
              'bias ${_fmt(f.bias, suffix: '%')}, MASE ${_fmt(f.mase, decimals: 2)}.'
              '${f.fresh ? ' Fresh: lives ${f.shelfLifeDays} days, so an order covers no more' : ''}'
              '${f.wasteRatePct == null ? '' : '; ${f.wasteRatePct!.toStringAsFixed(1)}% of what was received went out of date unsold'}'
              '${f.fresh || f.wasteRatePct != null ? '.' : ''}'),
          if (f.weekdayProfile.length == 7) ...[
            const SizedBox(height: 8),
            Text('Weekday profile: ${[
              'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'
            ].asMap().entries.map((e) => '${e.value} ${f.weekdayProfile[e.key].toStringAsFixed(2)}').join(' · ')}'),
          ],
          const SizedBox(height: 12),
          ...f.points.map((p) => Row(
                children: [
                  SizedBox(width: 110, child: Text(p.day)),
                  Expanded(
                    child: LinearProgressIndicator(
                      value: f.level <= 0 ? 0 : (p.qty / (f.level * 2)).clamp(0.0, 1.0),
                      minHeight: 8,
                    ),
                  ),
                  SizedBox(
                      width: 64,
                      child: Text(p.qty.toStringAsFixed(1), textAlign: TextAlign.end)),
                ],
              )),
        ],
      ),
    );
  }
}
