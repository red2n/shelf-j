import 'dart:js_interop';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
// ignore: avoid_web_libraries_in_flutter
import 'package:web/web.dart' as web;

import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

enum _ReportType { sales, salesByDay, onHand, supplyDemand, movements }

class ReportsScreen extends ConsumerStatefulWidget {
  const ReportsScreen({super.key});

  @override
  ConsumerState<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends ConsumerState<ReportsScreen> {
  _ReportType _selected = _ReportType.onHand;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, bc) {
      final wide = bc.maxWidth >= 800;

      if (wide) {
        // Side-by-side: report list on left, content on right
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 220,
              child: _ReportSidebar(
                  selected: _selected,
                  onSelect: (r) => setState(() => _selected = r)),
            ),
            const VerticalDivider(width: 1),
            Expanded(child: _ReportContent(type: _selected)),
          ],
        );
      }

      // Mobile: top tabs
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
            child: Text('Reports',
                style: Theme.of(context).textTheme.headlineMedium),
          ),
          const SizedBox(height: 16),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Row(
              children: _ReportType.values
                  .map((r) => Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          label: Text(_reportLabel(r)),
                          avatar: Icon(_reportIcon(r), size: 16),
                          selected: _selected == r,
                          onSelected: (_) =>
                              setState(() => _selected = r),
                        ),
                      ))
                  .toList(),
            ),
          ),
          const SizedBox(height: 16),
          Expanded(child: _ReportContent(type: _selected)),
        ],
      );
    });
  }
}

class _ReportSidebar extends StatelessWidget {
  final _ReportType selected;
  final ValueChanged<_ReportType> onSelect;

  const _ReportSidebar({required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 24, 16, 16),
          child: Text('Reports',
              style: Theme.of(context).textTheme.headlineMedium),
        ),
        ..._ReportType.values.map((r) {
          final active = selected == r;
          return Container(
            margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            decoration: BoxDecoration(
              color: active ? cs.secondaryContainer : null,
              borderRadius: BorderRadius.circular(8),
            ),
            child: ListTile(
              dense: true,
              leading: Icon(_reportIcon(r),
                  color: active ? cs.onSecondaryContainer : cs.onSurfaceVariant),
              title: Text(_reportLabel(r),
                  style: TextStyle(
                      fontWeight:
                          active ? FontWeight.bold : FontWeight.normal,
                      color: active
                          ? cs.onSecondaryContainer
                          : cs.onSurfaceVariant)),
              onTap: () => onSelect(r),
            ),
          );
        }),
      ],
    );
  }
}

class _ReportContent extends ConsumerWidget {
  final _ReportType type;
  const _ReportContent({required this.type});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    switch (type) {
      case _ReportType.sales:
        return _SalesReport();
      case _ReportType.salesByDay:
        return _SalesByDayReport();
      case _ReportType.onHand:
        return _OnHandReport();
      case _ReportType.supplyDemand:
        return _SupplyDemandReport();
      case _ReportType.movements:
        return _MovementStatsReport();
    }
  }
}

/// Shared header (title + subtitle + refresh + optional export) used by table reports.
class _ReportHeader extends StatelessWidget {
  final String title;
  final String subtitle;
  final VoidCallback onRefresh;
  final VoidCallback? onExportCsv;
  const _ReportHeader({
    required this.title,
    required this.subtitle,
    required this.onRefresh,
    this.onExportCsv,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: Theme.of(context)
                        .textTheme
                        .titleLarge
                        ?.copyWith(fontWeight: FontWeight.bold)),
                Text(subtitle,
                    style: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.copyWith(color: cs.outline)),
              ],
            ),
          ),
          if (onExportCsv != null)
            TextButton.icon(
              onPressed: onExportCsv,
              icon: const Icon(Icons.download_outlined, size: 18),
              label: const Text('Export CSV'),
            ),
          IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: 'Refresh',
              onPressed: onRefresh),
        ],
      ),
    );
  }
}

/// From/to date pickers bound to [reportDateRangeProvider].
class _DateRangeBar extends ConsumerWidget {
  const _DateRangeBar();

  Future<void> _pick(
      BuildContext context, WidgetRef ref, {required bool isFrom}) async {
    final range = ref.read(reportDateRangeProvider);
    final current = isFrom ? range.from : range.to;
    DateTime initial;
    try {
      initial = current != null ? DateTime.parse(current) : DateTime.now();
    } catch (_) {
      initial = DateTime.now();
    }
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(now.year - 5),
      lastDate: DateTime(now.year + 1),
    );
    if (picked == null) return;
    final s = yyyyMmDd(picked);
    ref.read(reportDateRangeProvider.notifier).state = isFrom
        ? range.copyWith(from: s)
        : range.copyWith(to: s);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final range = ref.watch(reportDateRangeProvider);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Wrap(
        spacing: 12,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          OutlinedButton.icon(
            onPressed: () => _pick(context, ref, isFrom: true),
            icon: const Icon(Icons.event_outlined, size: 18),
            label: Text('From: ${range.from ?? '—'}'),
          ),
          OutlinedButton.icon(
            onPressed: () => _pick(context, ref, isFrom: false),
            icon: const Icon(Icons.event_outlined, size: 18),
            label: Text('To: ${range.to ?? '—'}'),
          ),
          TextButton(
            onPressed: () {
              final now = DateTime.now();
              ref.read(reportDateRangeProvider.notifier).state = ReportDateRange(
                from: yyyyMmDd(now.subtract(const Duration(days: 30))),
                to: yyyyMmDd(now),
              );
            },
            child: const Text('Last 30 days'),
          ),
        ],
      ),
    );
  }
}

void _downloadCsv(String filename, String csv) {
  final blob = web.Blob(
    [csv.toJS].toJS,
    web.BlobPropertyBag(type: 'text/csv;charset=utf-8'),
  );
  final url = web.URL.createObjectURL(blob);
  final anchor = web.HTMLAnchorElement()
    ..href = url
    ..download = filename;
  web.document.body?.append(anchor);
  anchor.click();
  anchor.remove();
  web.URL.revokeObjectURL(url);
}

String _csvEscape(Object? v) {
  final s = v?.toString() ?? '';
  if (s.contains(',') || s.contains('"') || s.contains('\n')) {
    return '"${s.replaceAll('"', '""')}"';
  }
  return s;
}

String _short(String s, [int n = 8]) =>
    s.length > n ? '${s.substring(0, n)}…' : s;

const _idStyle = TextStyle(fontFamily: 'monospace', fontSize: 12);

class _SupplyDemandReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(supplyDemandReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading supply / demand…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e,
            fallback: 'Could not load supply / demand report.'),
        onRetry: () => ref.invalidate(supplyDemandReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Supply / Demand Netting',
            subtitle: 'On-hand + in-transit → net available',
            onRefresh: () => ref.invalidate(supplyDemandReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'storeId,variantId,onHand,supplyInTransit,netAvailable\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.storeId),
                        _csvEscape(r.variantId),
                        r.onHand,
                        r.supplyInTransit,
                        r.netAvailable,
                      ].join(','));
                    }
                    _downloadCsv('supply-demand.csv', buf.toString());
                  },
          ),
          if (rows.isEmpty)
            const Expanded(child: Center(child: Text('No netting data yet.')))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Store')),
                      DataColumn(label: Text('Variant')),
                      DataColumn(label: Text('On-Hand'), numeric: true),
                      DataColumn(label: Text('In-Transit'), numeric: true),
                      DataColumn(label: Text('Net Avail.'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(Text(_short(r.storeId), style: _idStyle)),
                              DataCell(
                                  Text(_short(r.variantId, 16), style: _idStyle)),
                              DataCell(Text(r.onHand.toStringAsFixed(0))),
                              DataCell(
                                  Text(r.supplyInTransit.toStringAsFixed(0))),
                              DataCell(Text(r.netAvailable.toStringAsFixed(0))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SalesReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(salesSummaryReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load sales report.'),
        onRetry: () => ref.invalidate(salesSummaryReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Sales Revenue',
            subtitle:
                'Gross / refunded / net revenue by currency${range.from != null ? ' · ${range.from} → ${range.to}' : ''}',
            onRefresh: () => ref.invalidate(salesSummaryReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf =
                        StringBuffer('currency,orders,gross,refunded,net\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.currency),
                        r.orders,
                        r.gross,
                        r.refunded,
                        r.net,
                      ].join(','));
                    }
                    _downloadCsv('sales-summary.csv', buf.toString());
                  },
          ),
          const _DateRangeBar(),
          const SizedBox(height: 12),
          if (rows.isEmpty)
            const Expanded(child: Center(child: Text('No sales yet.')))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Currency')),
                      DataColumn(label: Text('Orders'), numeric: true),
                      DataColumn(label: Text('Gross'), numeric: true),
                      DataColumn(label: Text('Refunded'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(Text(r.currency)),
                              DataCell(Text('${r.orders}')),
                              DataCell(Text(r.gross.toStringAsFixed(2))),
                              DataCell(Text(r.refunded.toStringAsFixed(2))),
                              DataCell(Text(r.net.toStringAsFixed(2),
                                  style: const TextStyle(
                                      fontWeight: FontWeight.bold))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SalesByDayReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(salesByDayReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales by day…'),
      error: (e, _) => ErrorView(
        message:
            friendlyError(e, fallback: 'Could not load sales-by-day report.'),
        onRetry: () => ref.invalidate(salesByDayReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Sales by Day',
            subtitle:
                'Daily revenue buckets${range.from != null ? ' · ${range.from} → ${range.to}' : ''}',
            onRefresh: () => ref.invalidate(salesByDayReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'day,currency,orders,gross,refunded,net\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.day),
                        _csvEscape(r.currency),
                        r.orders,
                        r.gross,
                        r.refunded,
                        r.net,
                      ].join(','));
                    }
                    _downloadCsv('sales-by-day.csv', buf.toString());
                  },
          ),
          const _DateRangeBar(),
          const SizedBox(height: 12),
          if (rows.isEmpty)
            const Expanded(
                child: Center(child: Text('No daily sales in this range.')))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Day')),
                      DataColumn(label: Text('Currency')),
                      DataColumn(label: Text('Orders'), numeric: true),
                      DataColumn(label: Text('Gross'), numeric: true),
                      DataColumn(label: Text('Refunded'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(Text(r.day)),
                              DataCell(Text(r.currency)),
                              DataCell(Text('${r.orders}')),
                              DataCell(Text(r.gross.toStringAsFixed(2))),
                              DataCell(Text(r.refunded.toStringAsFixed(2))),
                              DataCell(Text(r.net.toStringAsFixed(2),
                                  style: const TextStyle(
                                      fontWeight: FontWeight.bold))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _MovementStatsReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(movementStatsReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading movement stats…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load movement stats.'),
        onRetry: () => ref.invalidate(movementStatsReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Movement Statistics',
            subtitle: 'Stock in / out / net per period',
            onRefresh: () => ref.invalidate(movementStatsReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'storeId,variantId,bucket,totalIn,totalOut,net\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.storeId),
                        _csvEscape(r.variantId),
                        _csvEscape(r.bucket),
                        r.totalIn,
                        r.totalOut,
                        r.net,
                      ].join(','));
                    }
                    _downloadCsv('movement-stats.csv', buf.toString());
                  },
          ),
          if (rows.isEmpty)
            const Expanded(child: Center(child: Text('No movement data yet.')))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Store')),
                      DataColumn(label: Text('Variant')),
                      DataColumn(label: Text('Period')),
                      DataColumn(label: Text('In'), numeric: true),
                      DataColumn(label: Text('Out'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(Text(_short(r.storeId), style: _idStyle)),
                              DataCell(
                                  Text(_short(r.variantId, 16), style: _idStyle)),
                              DataCell(Text(r.bucket, style: _idStyle)),
                              DataCell(Text(r.totalIn.toStringAsFixed(0))),
                              DataCell(Text(r.totalOut.toStringAsFixed(0))),
                              DataCell(Text(r.net.toStringAsFixed(0))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _OnHandReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportAsync = ref.watch(onHandReportProvider);
    final cs = Theme.of(context).colorScheme;

    return reportAsync.when(
      loading: () => const LoadingView(label: 'Loading on-hand report…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load on-hand report.'),
        onRetry: () => ref.invalidate(onHandReportProvider),
      ),
      data: (rows) {
        final grandTotal = rows.fold<double>(0, (s, r) => s + r.onHand);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'On-Hand Inventory',
              subtitle: 'Total units across all stores',
              onRefresh: () => ref.invalidate(onHandReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf =
                          StringBuffer('storeId,variantId,onHand\n');
                      for (final r in rows) {
                        buf.writeln([
                          _csvEscape(r.storeId),
                          _csvEscape(r.variantId),
                          r.onHand,
                        ].join(','));
                      }
                      _downloadCsv('on-hand.csv', buf.toString());
                    },
            ),
            // Summary chip
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: Chip(
                avatar: const Icon(Icons.inventory_2_outlined, size: 16),
                label: Text(
                    '${rows.length} SKUs · ${grandTotal.toStringAsFixed(0)} units total'),
                backgroundColor: cs.primaryContainer,
              ),
            ),
            const SizedBox(height: 16),
            if (rows.isEmpty)
              Expanded(
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.bar_chart, size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      const Text('No inventory data yet.'),
                      const SizedBox(height: 8),
                      const Text('Receive stock to see the report.'),
                    ],
                  ),
                ),
              )
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Card(
                    child: DataTable(
                      headingRowColor:
                          WidgetStatePropertyAll(cs.surfaceContainerHigh),
                      columnSpacing: 24,
                      columns: const [
                        DataColumn(label: Text('Store')),
                        DataColumn(label: Text('Variant ID')),
                        DataColumn(label: Text('On-Hand'), numeric: true),
                      ],
                      rows: rows
                          .map((r) => DataRow(cells: [
                                DataCell(Text(
                                  r.storeId.length > 8
                                      ? r.storeId.substring(0, 8)
                                      : r.storeId,
                                  style: const TextStyle(
                                      fontFamily: 'monospace', fontSize: 12),
                                )),
                                DataCell(Text(
                                  r.variantId.length > 16
                                      ? '${r.variantId.substring(0, 16)}…'
                                      : r.variantId,
                                  style: const TextStyle(
                                      fontFamily: 'monospace', fontSize: 12),
                                )),
                                DataCell(Text(r.onHand.toStringAsFixed(0))),
                              ]))
                          .toList(),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

String _reportLabel(_ReportType r) {
  switch (r) {
    case _ReportType.sales:
      return 'Sales Revenue';
    case _ReportType.salesByDay:
      return 'Sales by Day';
    case _ReportType.onHand:
      return 'On-Hand Inventory';
    case _ReportType.supplyDemand:
      return 'Supply / Demand';
    case _ReportType.movements:
      return 'Movement Stats';
  }
}

IconData _reportIcon(_ReportType r) {
  switch (r) {
    case _ReportType.sales:
      return Icons.payments_outlined;
    case _ReportType.salesByDay:
      return Icons.calendar_view_day_outlined;
    case _ReportType.onHand:
      return Icons.inventory_2_outlined;
    case _ReportType.supplyDemand:
      return Icons.balance_outlined;
    case _ReportType.movements:
      return Icons.swap_horiz;
  }
}
