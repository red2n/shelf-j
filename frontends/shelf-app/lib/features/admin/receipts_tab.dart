import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

// The legal receipt register: the series a store runs, the documents in it in
// order, and the inspector's question — is the sequence unbroken? — answered by
// the server from the table rather than by anyone's assertion. A manager sets
// what a series prints in front of its numbers; nothing here can renumber,
// delete or edit a receipt.

class ReceiptSeries {
  final String storeId;
  final String seriesCode;
  final String period;
  final int nextNumber;
  final String? prefix;
  const ReceiptSeries({
    required this.storeId,
    required this.seriesCode,
    required this.period,
    required this.nextNumber,
    this.prefix,
  });
  factory ReceiptSeries.fromJson(Map<String, dynamic> j) => ReceiptSeries(
        storeId: j['storeId'] as String? ?? '',
        seriesCode: j['seriesCode'] as String? ?? 'MAIN',
        period: j['period'] as String? ?? '',
        nextNumber: (j['nextNumber'] as num?)?.toInt() ?? 1,
        prefix: j['prefix'] as String?,
      );
}

class ReceiptAudit {
  final int firstNumber;
  final int lastNumber;
  final int issued;
  final int expected;
  final bool intact;
  final List<(int, int)> gaps;
  const ReceiptAudit({
    required this.firstNumber,
    required this.lastNumber,
    required this.issued,
    required this.expected,
    required this.intact,
    required this.gaps,
  });
  factory ReceiptAudit.fromJson(Map<String, dynamic> j) => ReceiptAudit(
        firstNumber: (j['firstNumber'] as num?)?.toInt() ?? 0,
        lastNumber: (j['lastNumber'] as num?)?.toInt() ?? 0,
        issued: (j['issued'] as num?)?.toInt() ?? 0,
        expected: (j['expected'] as num?)?.toInt() ?? 0,
        intact: j['intact'] as bool? ?? false,
        gaps: [
          for (final g in (j['gaps'] as List?) ?? const [])
            ((g['from'] as num).toInt(), (g['to'] as num).toInt()),
        ],
      );
}

class ReceiptRow {
  final String fullNumber;
  final int number;
  final String orderId;
  final String issuedAt;
  final double grossTotal;
  final String currency;
  final bool voided;
  const ReceiptRow({
    required this.fullNumber,
    required this.number,
    required this.orderId,
    required this.issuedAt,
    required this.grossTotal,
    required this.currency,
    required this.voided,
  });
  factory ReceiptRow.fromJson(Map<String, dynamic> j) => ReceiptRow(
        fullNumber: j['fullNumber'] as String? ?? '',
        number: (j['number'] as num?)?.toInt() ?? 0,
        orderId: j['orderId'] as String? ?? '',
        issuedAt: j['issuedAt'] as String? ?? '',
        grossTotal: (j['grossTotal'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        voided: j['voidedAt'] != null,
      );
}

class ReceiptFilter {
  final String? storeId;
  final String series;
  final String period;
  const ReceiptFilter({this.storeId, this.series = 'MAIN', required this.period});
  ReceiptFilter copyWith({String? storeId, String? series, String? period}) => ReceiptFilter(
        storeId: storeId ?? this.storeId,
        series: series ?? this.series,
        period: period ?? this.period,
      );
}

final receiptFilterProvider = StateProvider<ReceiptFilter>(
    (ref) => ReceiptFilter(period: DateTime.now().year.toString()));

final receiptSeriesProvider =
    FutureProvider.autoDispose.family<List<ReceiptSeries>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.order}/admin/fiscal-receipts/series',
      queryParameters: {'storeId': storeId});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => ReceiptSeries.fromJson(e as Map<String, dynamic>))
      .toList();
});

final receiptAuditProvider = FutureProvider.autoDispose<ReceiptAudit?>((ref) async {
  final f = ref.watch(receiptFilterProvider);
  if (f.storeId == null) return null;
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.order}/admin/fiscal-receipts/audit',
      queryParameters: {'storeId': f.storeId, 'series': f.series, 'period': f.period});
  return ReceiptAudit.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final receiptListProvider = FutureProvider.autoDispose<List<ReceiptRow>>((ref) async {
  final f = ref.watch(receiptFilterProvider);
  if (f.storeId == null) return const [];
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.order}/admin/fiscal-receipts',
      queryParameters: {'storeId': f.storeId, 'series': f.series, 'period': f.period, 'limit': 200});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => ReceiptRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

class ReceiptsTab extends ConsumerWidget {
  const ReceiptsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider);
    final filter = ref.watch(receiptFilterProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final theme = Theme.of(context);

    // Default to the first store once they load.
    final storeList = stores.value ?? const <StoreInfo>[];
    final storeId = filter.storeId ?? (storeList.isNotEmpty ? storeList.first.id : null);
    if (filter.storeId == null && storeId != null) {
      Future.microtask(() =>
          ref.read(receiptFilterProvider.notifier).state = filter.copyWith(storeId: storeId));
    }

    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Text('Legal receipts', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'Every completed sale is numbered consecutively per store, series and fiscal year. '
          'A voided sale keeps its number; nothing here can renumber or remove one. The audit '
          'answers the inspector\'s question from the table itself.',
          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String>(
                key: const Key('receipts-store'),
                initialValue: storeId,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  for (final s in storeList) DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => ref.read(receiptFilterProvider.notifier).state =
                    filter.copyWith(storeId: v),
              ),
            ),
            SizedBox(
              width: 120,
              child: TextFormField(
                key: const Key('receipts-series'),
                initialValue: filter.series,
                decoration: const InputDecoration(labelText: 'Series'),
                onFieldSubmitted: (v) => ref.read(receiptFilterProvider.notifier).state =
                    filter.copyWith(series: v.trim().toUpperCase()),
              ),
            ),
            SizedBox(
              width: 120,
              child: TextFormField(
                key: const Key('receipts-period'),
                initialValue: filter.period,
                decoration: const InputDecoration(labelText: 'Fiscal year'),
                onFieldSubmitted: (v) => ref.read(receiptFilterProvider.notifier).state =
                    filter.copyWith(period: v.trim()),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (storeId != null) ...[
          _SeriesCard(storeId: storeId, isManager: isManager),
          const SizedBox(height: AppSpacing.lg),
          ref.watch(receiptAuditProvider).when(
                loading: () => const LoadingView(label: 'Auditing…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e, fallback: 'Could not audit the series.'),
                  onRetry: () => ref.invalidate(receiptAuditProvider),
                ),
                data: (a) => a == null ? const SizedBox.shrink() : _AuditCard(audit: a),
              ),
          const SizedBox(height: AppSpacing.lg),
          Text('Receipts, in order', style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          ref.watch(receiptListProvider).when(
                loading: () => const LoadingView(label: 'Loading receipts…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e, fallback: 'Could not load the receipts.'),
                  onRetry: () => ref.invalidate(receiptListProvider),
                ),
                data: (rows) => rows.isEmpty
                    ? Text('No receipts in this series yet.',
                        style: TextStyle(color: theme.colorScheme.onSurfaceVariant))
                    : Card(
                        child: Column(
                          children: [
                            for (final r in rows)
                              ListTile(
                                dense: true,
                                leading: Text('${r.number}',
                                    style: const TextStyle(fontFeatures: [FontFeature.tabularFigures()])),
                                title: Text(r.fullNumber),
                                subtitle: Text('${r.issuedAt} · order ${shortRef(r.orderId)}'),
                                trailing: Text(
                                  '${r.voided ? 'VOID · ' : ''}${r.currency} ${r.grossTotal.toStringAsFixed(2)}',
                                  style: r.voided
                                      ? TextStyle(color: theme.colorScheme.error)
                                      : null,
                                ),
                              ),
                          ],
                        ),
                      ),
              ),
        ],
      ],
    );
  }
}

class _AuditCard extends StatelessWidget {
  const _AuditCard({required this.audit});
  final ReceiptAudit audit;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: audit.intact ? null : cs.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(audit.intact ? Icons.verified_outlined : Icons.report_problem_outlined,
                  color: audit.intact ? cs.primary : cs.error),
              const SizedBox(width: 8),
              Text(
                audit.intact ? 'Sequence intact' : 'Sequence has gaps',
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ]),
            const SizedBox(height: 8),
            Text('First ${audit.firstNumber} · last ${audit.lastNumber} · issued ${audit.issued} '
                '· expected ${audit.expected}'),
            if (audit.gaps.isNotEmpty) ...[
              const SizedBox(height: 6),
              for (final g in audit.gaps)
                Text(g.$1 == g.$2 ? 'Missing ${g.$1}' : 'Missing ${g.$1}–${g.$2}'),
              const SizedBox(height: 6),
              const Text('A gap is not necessarily fraud; it is the thing that has to be explained.'),
            ],
          ],
        ),
      ),
    );
  }
}

class _SeriesCard extends ConsumerWidget {
  const _SeriesCard({required this.storeId, required this.isManager});
  final String storeId;
  final bool isManager;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final series = ref.watch(receiptSeriesProvider(storeId));
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(child: Text('Series', style: Theme.of(context).textTheme.titleMedium)),
              if (isManager)
                TextButton.icon(
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => _SeriesPrefixDialog(storeId: storeId),
                  ),
                  icon: const Icon(Icons.edit_outlined, size: 18),
                  label: const Text('Set prefix'),
                ),
            ]),
            series.when(
              loading: () => const LoadingView(label: 'Loading series…'),
              error: (e, _) => ErrorView(
                message: friendlyError(e, fallback: 'Could not load the series.'),
                onRetry: () => ref.invalidate(receiptSeriesProvider(storeId)),
              ),
              data: (rows) => rows.isEmpty
                  ? const Text('No series opened yet — the first completed sale opens MAIN.')
                  : Column(
                      children: [
                        for (final s in rows)
                          ListTile(
                            dense: true,
                            title: Text('${s.seriesCode} · ${s.period}'),
                            subtitle: Text('prefix ${s.prefix ?? '—'} · next number ${s.nextNumber}'),
                          ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SeriesPrefixDialog extends ConsumerStatefulWidget {
  const _SeriesPrefixDialog({required this.storeId});
  final String storeId;
  @override
  ConsumerState<_SeriesPrefixDialog> createState() => _SeriesPrefixDialogState();
}

class _SeriesPrefixDialogState extends ConsumerState<_SeriesPrefixDialog> {
  final _series = TextEditingController(text: 'MAIN');
  final _period = TextEditingController(text: DateTime.now().year.toString());
  final _prefix = TextEditingController();
  String? _error;
  bool _saving = false;

  @override
  void dispose() {
    _series.dispose();
    _period.dispose();
    _prefix.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.order}/admin/fiscal-receipts/series',
        data: {
          'storeId': widget.storeId,
          'seriesCode': _series.text.trim(),
          'period': _period.text.trim(),
          'prefix': _prefix.text.trim(),
        },
      );
      ref.invalidate(receiptSeriesProvider(widget.storeId));
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not set the prefix.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Series prefix'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: _series, decoration: const InputDecoration(labelText: 'Series')),
            TextField(controller: _period, decoration: const InputDecoration(labelText: 'Fiscal year')),
            TextField(
                controller: _prefix,
                decoration: const InputDecoration(
                    labelText: 'Prefix', helperText: 'Printed in front of the number, e.g. GB-LDN-01')),
            const SizedBox(height: 8),
            const Text(
                'Changes the documents issued from now on. Every receipt already issued keeps the number it was printed with.',
                style: TextStyle(fontSize: 12)),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _saving ? null : _save, child: const Text('Save')),
      ],
    );
  }
}
