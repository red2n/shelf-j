import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';

// ---------------------------------------------------------------------------
// Container deposits (09.16).
//
// A deposit return scheme puts a deposit on a drink's container at the sale and
// pays it back when the empty comes back. order-svc keeps each deposit as its
// own line beside the item and each refund as its own record; this screen sums
// them over a period, by material — what the scheme administrator asks the
// business for, and, where the deposit is outside the scope of VAT, what the
// VAT return leaves out.
// ---------------------------------------------------------------------------

class DepositReportRow {
  final String material;
  final int chargedContainers;
  final num chargedAmount;
  final num chargedVat;
  final int refundedContainers;
  final num refundedAmount;
  const DepositReportRow({
    required this.material,
    required this.chargedContainers,
    required this.chargedAmount,
    required this.chargedVat,
    required this.refundedContainers,
    required this.refundedAmount,
  });
  factory DepositReportRow.fromJson(Map<String, dynamic> j) => DepositReportRow(
        material: j['material'] as String? ?? '',
        chargedContainers: (j['chargedContainers'] as num?)?.toInt() ?? 0,
        chargedAmount: j['chargedAmount'] as num? ?? 0,
        chargedVat: j['chargedVat'] as num? ?? 0,
        refundedContainers: (j['refundedContainers'] as num?)?.toInt() ?? 0,
        refundedAmount: j['refundedAmount'] as num? ?? 0,
      );
}

class DepositReport {
  final String from;
  final String to;
  final String currency;
  final int chargedContainers;
  final num chargedAmount;
  final num chargedVat;
  final int refundedContainers;
  final num refundedAmount;
  final num unredeemedAmount;
  final List<DepositReportRow> byMaterial;
  const DepositReport({
    required this.from,
    required this.to,
    required this.currency,
    required this.chargedContainers,
    required this.chargedAmount,
    required this.chargedVat,
    required this.refundedContainers,
    required this.refundedAmount,
    required this.unredeemedAmount,
    required this.byMaterial,
  });
  factory DepositReport.fromJson(Map<String, dynamic> j) => DepositReport(
        from: j['from'] as String? ?? '',
        to: j['to'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        chargedContainers: (j['chargedContainers'] as num?)?.toInt() ?? 0,
        chargedAmount: j['chargedAmount'] as num? ?? 0,
        chargedVat: j['chargedVat'] as num? ?? 0,
        refundedContainers: (j['refundedContainers'] as num?)?.toInt() ?? 0,
        refundedAmount: j['refundedAmount'] as num? ?? 0,
        unredeemedAmount: j['unredeemedAmount'] as num? ?? 0,
        byMaterial: [
          for (final r in (j['byMaterial'] as List?) ?? const [])
            DepositReportRow.fromJson(r as Map<String, dynamic>)
        ],
      );
}

/// The period the report covers: [from] inclusive to [to] exclusive, whole days
/// on the UTC clock the figures are stored on.
class DepositPeriod {
  final DateTime from;
  final DateTime to;
  const DepositPeriod(this.from, this.to);

  static DepositPeriod thisMonth() {
    final now = DateTime.now().toUtc();
    final start = DateTime.utc(now.year, now.month);
    return DepositPeriod(start, DateTime.utc(now.year, now.month + 1));
  }
}

final depositPeriodProvider =
    NotifierProvider<DepositPeriodNotifier, DepositPeriod>(DepositPeriodNotifier.new);

class DepositPeriodNotifier extends Notifier<DepositPeriod> {
  @override
  DepositPeriod build() => DepositPeriod.thisMonth();
  void set(DepositPeriod p) => state = p;
}

final depositReportProvider =
    FutureProvider.autoDispose<DepositReport>((ref) async {
  final p = ref.watch(depositPeriodProvider);
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/admin/reports/deposits', queryParameters: {
    'from': p.from.toIso8601String(),
    'to': p.to.toIso8601String(),
  });
  return DepositReport.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class DepositsReportScreen extends ConsumerWidget {
  const DepositsReportScreen({super.key});

  static String _day(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _pickPeriod(BuildContext context, WidgetRef ref) async {
    final current = ref.read(depositPeriodProvider);
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 1)),
      initialDateRange: DateTimeRange(
          start: current.from, end: current.to.subtract(const Duration(days: 1))),
    );
    if (range == null) return;
    ref.read(depositPeriodProvider.notifier).set(DepositPeriod(
          DateTime.utc(range.start.year, range.start.month, range.start.day),
          DateTime.utc(range.end.year, range.end.month, range.end.day + 1),
        ));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final period = ref.watch(depositPeriodProvider);
    final report = ref.watch(depositReportProvider);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Text('Container deposits', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 4),
        Text(
          'What the deposit return scheme put on drinks containers sold, less '
          'what the till paid back on empties. The difference is what the '
          'scheme holds unredeemed. Sales cancelled or voided do not count.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.md),
        Row(
          children: [
            OutlinedButton.icon(
              key: const Key('deposits-period'),
              onPressed: () => _pickPeriod(context, ref),
              icon: const Icon(Icons.date_range),
              label: Text(
                  '${_day(period.from)} to ${_day(period.to.subtract(const Duration(days: 1)))}'),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        report.when(
          loading: () => const LoadingView(label: 'Adding up deposits…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e,
                fallback: 'Could not load the deposit report.'),
            onRetry: () => ref.invalidate(depositReportProvider),
          ),
          data: (r) => _ReportBody(report: r),
        ),
      ],
    );
  }
}

class _ReportBody extends StatelessWidget {
  const _ReportBody({required this.report});
  final DepositReport report;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final r = report;
    String money(num v) => '${r.currency} ${v.toStringAsFixed(2)}';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.md,
          children: [
            _Figure(
                key: const Key('deposits-charged'),
                label: 'Charged',
                value: money(r.chargedAmount),
                note: '${r.chargedContainers} containers'),
            _Figure(
                key: const Key('deposits-refunded'),
                label: 'Refunded',
                value: money(r.refundedAmount),
                note: '${r.refundedContainers} containers'),
            _Figure(
                key: const Key('deposits-unredeemed'),
                label: 'Unredeemed',
                value: money(r.unredeemedAmount),
                note: 'held by the scheme'),
            if (r.chargedVat > 0)
              _Figure(
                  key: const Key('deposits-vat'),
                  label: 'VAT inside deposits',
                  value: money(r.chargedVat),
                  note: 'where the scheme taxes the deposit'),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (r.byMaterial.isEmpty)
          const Text('No deposit was charged or refunded in this period.')
        else
          Card(
            child: Column(
              children: [
                for (final m in r.byMaterial)
                  ListTile(
                    key: Key('deposits-material-${m.material}'),
                    title: Text(_materialName(m.material)),
                    subtitle: Text('${m.chargedContainers} sold · '
                        '${m.refundedContainers} returned'),
                    trailing: Text(
                      '${money(m.chargedAmount)} − ${money(m.refundedAmount)}',
                      style: theme.textTheme.bodyMedium,
                    ),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

String _materialName(String code) => switch (code) {
      'PET' => 'PET plastic',
      'ALUMINIUM' => 'Aluminium',
      'STEEL' => 'Steel',
      'GLASS' => 'Glass',
      _ => code,
    };

class _Figure extends StatelessWidget {
  const _Figure(
      {super.key, required this.label, required this.value, required this.note});
  final String label;
  final String value;
  final String note;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SizedBox(
      width: 200,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: theme.textTheme.labelMedium),
              const SizedBox(height: 4),
              Text(value, style: theme.textTheme.titleLarge),
              Text(note, style: theme.textTheme.bodySmall),
            ],
          ),
        ),
      ),
    );
  }
}
