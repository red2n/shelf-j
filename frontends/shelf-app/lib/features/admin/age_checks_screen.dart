import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:intl/intl.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../pos/pos_age_check.dart' show AgeVerificationDialog, ageIdTypes, ageRefusalReasons;
import 'providers/admin_providers.dart';

// The age-check register: what a licensing officer asks to see. Every check
// the till made, by store and period, the refusals and their reasons, and the
// counts. Read-only by design — the record is append-only on the server and
// nothing here can change it.

class AgeCheckRecord {
  final String id;
  final String storeId;
  final String? cashierId;
  final String variantId;
  final String category;
  final int minimumAge;
  final String country;
  final bool storePolicy;
  final String outcome;
  final String? reason;
  final String? idType;
  final DateTime? checkedAt;

  const AgeCheckRecord({
    required this.id,
    required this.storeId,
    this.cashierId,
    required this.variantId,
    required this.category,
    required this.minimumAge,
    required this.country,
    required this.storePolicy,
    required this.outcome,
    this.reason,
    this.idType,
    this.checkedAt,
  });

  bool get refused => outcome == 'REFUSED';

  factory AgeCheckRecord.fromJson(Map<String, dynamic> j) => AgeCheckRecord(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        cashierId: j['cashierId'] as String?,
        variantId: j['variantId'] as String? ?? '',
        category: j['category'] as String? ?? '',
        minimumAge: (j['minimumAge'] as num?)?.toInt() ?? 0,
        country: j['country'] as String? ?? '',
        storePolicy: j['storePolicy'] as bool? ?? false,
        outcome: j['outcome'] as String? ?? '',
        reason: j['reason'] as String?,
        idType: j['idType'] as String?,
        checkedAt: DateTime.tryParse(j['checkedAt'] as String? ?? ''),
      );
}

class AgeCheckSummary {
  final int total;
  final int passed;
  final int refused;
  final Map<String, int> refusedByReason;
  final Map<String, int> byCategory;

  const AgeCheckSummary({
    required this.total,
    required this.passed,
    required this.refused,
    required this.refusedByReason,
    required this.byCategory,
  });

  factory AgeCheckSummary.fromJson(Map<String, dynamic> j) => AgeCheckSummary(
        total: (j['total'] as num?)?.toInt() ?? 0,
        passed: (j['passed'] as num?)?.toInt() ?? 0,
        refused: (j['refused'] as num?)?.toInt() ?? 0,
        refusedByReason: {
          for (final e in ((j['refusedByReason'] as Map?) ?? {}).entries)
            e.key as String: (e.value as num).toInt(),
        },
        byCategory: {
          for (final e in ((j['byCategory'] as Map?) ?? {}).entries)
            e.key as String: (e.value as num).toInt(),
        },
      );
}

/// The register's filters: one store or all, a period, and an outcome.
class AgeCheckFilter {
  final String? storeId;
  final DateTime from;
  final DateTime to;
  final String? outcome;

  const AgeCheckFilter({
    this.storeId,
    required this.from,
    required this.to,
    this.outcome,
  });

  AgeCheckFilter copyWith({
    Object? storeId = _unset,
    DateTime? from,
    DateTime? to,
    Object? outcome = _unset,
  }) =>
      AgeCheckFilter(
        storeId: storeId == _unset ? this.storeId : storeId as String?,
        from: from ?? this.from,
        to: to ?? this.to,
        outcome: outcome == _unset ? this.outcome : outcome as String?,
      );

  static const _unset = Object();

  Map<String, dynamic> get query => {
        if (storeId != null) 'store': storeId,
        'from': from.toUtc().toIso8601String(),
        // The API's upper bound is exclusive; the picker's is a day, inclusive.
        'to': to.add(const Duration(days: 1)).toUtc().toIso8601String(),
        if (outcome != null) 'outcome': outcome,
      };
}

final ageCheckFilterProvider = StateProvider<AgeCheckFilter>((ref) {
  final today = DateTime.now();
  final day = DateTime(today.year, today.month, today.day);
  return AgeCheckFilter(from: day.subtract(const Duration(days: 29)), to: day);
});

final ageCheckSummaryProvider =
    FutureProvider.autoDispose<AgeCheckSummary>((ref) async {
  final filter = ref.watch(ageCheckFilterProvider);
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/pos/age-checks/summary',
        queryParameters: {
          if (filter.storeId != null) 'store': filter.storeId,
          'from': filter.from.toUtc().toIso8601String(),
          'to': filter.to.add(const Duration(days: 1)).toUtc().toIso8601String(),
        },
      );
  return AgeCheckSummary.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final ageCheckRegisterProvider =
    FutureProvider.autoDispose<List<AgeCheckRecord>>((ref) async {
  final filter = ref.watch(ageCheckFilterProvider);
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/pos/age-checks',
        queryParameters: {...filter.query, 'limit': 100},
      );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => AgeCheckRecord.fromJson(e as Map<String, dynamic>))
      .toList();
});

class AgeChecksScreen extends ConsumerWidget {
  const AgeChecksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider);
    final filter = ref.watch(ageCheckFilterProvider);
    final summary = ref.watch(ageCheckSummaryProvider);
    final register = ref.watch(ageCheckRegisterProvider);
    final theme = Theme.of(context);
    final dateFmt = DateFormat.yMMMd();

    Future<void> pickRange() async {
      final picked = await showDateRangePicker(
        context: context,
        firstDate: DateTime(2020),
        lastDate: DateTime.now().add(const Duration(days: 1)),
        initialDateRange: DateTimeRange(start: filter.from, end: filter.to),
      );
      if (picked != null) {
        ref.read(ageCheckFilterProvider.notifier).state = filter.copyWith(
          from: DateTime(picked.start.year, picked.start.month, picked.start.day),
          to: DateTime(picked.end.year, picked.end.month, picked.end.day),
        );
      }
    }

    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Text('Age checks', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 4),
        Text(
          'Every age check the till made, pass or refusal — the record that '
          'shows the shop was checking. Nothing here can be edited.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 260,
              child: DropdownButtonFormField<String?>(
                key: const Key('age-checks-store'),
                initialValue: filter.storeId,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  const DropdownMenuItem<String?>(
                      value: null, child: Text('All stores')),
                  for (final s in stores.value ?? const [])
                    DropdownMenuItem<String?>(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => ref.read(ageCheckFilterProvider.notifier).state =
                    filter.copyWith(storeId: v),
              ),
            ),
            OutlinedButton.icon(
              onPressed: pickRange,
              icon: const Icon(Icons.date_range),
              label: Text(
                  '${dateFmt.format(filter.from)} – ${dateFmt.format(filter.to)}'),
            ),
            SegmentedButton<String?>(
              segments: const [
                ButtonSegment(value: null, label: Text('All')),
                ButtonSegment(value: 'REFUSED', label: Text('Refusals')),
                ButtonSegment(value: 'PASSED', label: Text('Passed')),
              ],
              selected: {filter.outcome},
              onSelectionChanged: (s) =>
                  ref.read(ageCheckFilterProvider.notifier).state =
                      filter.copyWith(outcome: s.first),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        summary.when(
          loading: () => const LoadingView(label: 'Counting…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the counts.'),
            onRetry: () => ref.invalidate(ageCheckSummaryProvider),
          ),
          data: (s) => _SummaryCards(summary: s),
        ),
        const SizedBox(height: AppSpacing.lg),
        Text('Register', style: theme.textTheme.titleLarge),
        const SizedBox(height: 8),
        register.when(
          loading: () => const LoadingView(label: 'Loading the register…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the register.'),
            onRetry: () => ref.invalidate(ageCheckRegisterProvider),
          ),
          data: (rows) => rows.isEmpty
              ? Padding(
                  padding: const EdgeInsets.symmetric(vertical: 24),
                  child: Text(
                    'No checks in this period. A shop that sells restricted '
                    'items and has never refused anyone has not been checking.',
                    style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant),
                  ),
                )
              : Card(
                  child: Column(
                    children: [
                      for (final r in rows)
                        ListTile(
                          leading: Icon(
                            r.refused
                                ? Icons.block
                                : Icons.check_circle_outline,
                            color: r.refused
                                ? theme.colorScheme.error
                                : theme.colorScheme.primary,
                          ),
                          title: Text(
                            '${AgeVerificationDialog.categoryLabel(r.category)} · '
                            '${r.minimumAge}+ in ${r.country}'
                            '${r.storePolicy ? ' (store policy)' : ''}',
                          ),
                          subtitle: Text([
                            if (r.checkedAt != null)
                              DateFormat.yMMMd().add_Hm().format(r.checkedAt!.toLocal()),
                            r.refused
                                ? 'Refused — ${ageRefusalReasons[r.reason] ?? r.reason ?? ''}'
                                : 'Sale went ahead'
                                    '${r.idType != null ? ' — ${ageIdTypes[r.idType] ?? r.idType}' : ''}',
                          ].join(' · ')),
                          trailing: Text(
                            r.refused ? 'REFUSED' : 'PASSED',
                            style: TextStyle(
                              fontWeight: FontWeight.w600,
                              color: r.refused
                                  ? theme.colorScheme.error
                                  : theme.colorScheme.primary,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
        ),
      ],
    );
  }
}

class _SummaryCards extends StatelessWidget {
  const _SummaryCards({required this.summary});

  final AgeCheckSummary summary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    Widget stat(String label, String value, {Color? color}) => Expanded(
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(value,
                      style: theme.textTheme.headlineSmall
                          ?.copyWith(color: color, fontWeight: FontWeight.w600)),
                  Text(label, style: theme.textTheme.labelMedium),
                ],
              ),
            ),
          ),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          stat('Checks', '${summary.total}'),
          stat('Sales went ahead', '${summary.passed}'),
          stat('Refused', '${summary.refused}', color: theme.colorScheme.error),
        ]),
        if (summary.refusedByReason.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              for (final e in summary.refusedByReason.entries)
                Chip(
                    label: Text(
                        '${ageRefusalReasons[e.key] ?? e.key}: ${e.value}')),
            ],
          ),
        ],
      ],
    );
  }
}
