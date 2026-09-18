import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';

// ---------------------------------------------------------------------------
// The platform's own billing (21.9).
//
// Two things an operator comes here for: what is owed, oldest first, and
// whether the platform can invoice at all. The second is worth its own line
// because nothing is billed until the platform has said who it is, and a
// platform that has not filled that in looks like a platform with no customers.
// ---------------------------------------------------------------------------

class PlatformProfile {
  final String legalName;
  final String country;
  final String invoicePrefix;
  final int paymentTermsDays;
  final num taxRate;
  final String? vatNumber;

  const PlatformProfile({
    required this.legalName,
    required this.country,
    required this.invoicePrefix,
    required this.paymentTermsDays,
    required this.taxRate,
    required this.vatNumber,
  });

  factory PlatformProfile.fromJson(Map<String, dynamic> j) => PlatformProfile(
        legalName: j['legalName'] as String? ?? '',
        country: j['country'] as String? ?? '',
        invoicePrefix: j['invoicePrefix'] as String? ?? '',
        paymentTermsDays: (j['paymentTermsDays'] as num?)?.toInt() ?? 0,
        taxRate: j['taxRate'] as num? ?? 0,
        vatNumber: j['vatNumber'] as String?,
      );

  String get says =>
      '$legalName · $country · $invoicePrefix-… · $paymentTermsDays days · '
      '${(taxRate * 100).toStringAsFixed(2)}%';
}

/// What the platform has done about one overdue invoice, and what it will do next (21.12).
class DunningStage {
  final String? stage;
  final String? nextStep;
  final int daysOverdue;

  const DunningStage({required this.stage, required this.nextStep, required this.daysOverdue});

  factory DunningStage.fromJson(Map<String, dynamic> j) => DunningStage(
        stage: j['stage'] as String?,
        nextStep: j['nextStep'] as String?,
        daysOverdue: (j['daysOverdue'] as num?)?.toInt() ?? 0,
      );

  /// A reminder reads as one; the two that matter read as themselves.
  static String? _words(String? step) {
    if (step == null) return null;
    if (step.startsWith('REMINDER_')) return 'reminder ${step.substring(9)}';
    return switch (step) {
      'SUSPENDED' => 'suspended',
      'UNCOLLECTIBLE' => 'written off',
      'DUE_DATE_EXTENDED' => 'date extended',
      'RESOLVED' => 'paid',
      _ => step.toLowerCase(),
    };
  }

  String? get stageSays => _words(stage);

  /// Said plainly, because an operator seeing "suspended next" can act before a customer calls.
  String? get nextSays => _words(nextStep) == null ? null : '${_words(nextStep)} next';

  bool get suspended => stage == 'SUSPENDED';
}

class Receivable {
  final String id;
  final String number;
  final String? issueDate;
  final String? dueDate;
  final String? currency;
  final num? totalAmount;
  final num? outstanding;
  final String? taxTreatment;

  const Receivable({
    required this.id,
    required this.number,
    required this.issueDate,
    required this.dueDate,
    required this.currency,
    required this.totalAmount,
    required this.outstanding,
    required this.taxTreatment,
  });

  factory Receivable.fromJson(Map<String, dynamic> j) => Receivable(
        id: j['id'] as String? ?? '',
        number: j['number'] as String? ?? '',
        issueDate: j['issueDate'] as String?,
        dueDate: j['dueDate'] as String?,
        currency: j['currency'] as String?,
        totalAmount: j['totalAmount'] as num?,
        outstanding: j['outstanding'] as num?,
        taxTreatment: j['taxTreatment'] as String?,
      );

  /// Overdue against today, which is the only question a receivables list answers.
  bool overdueOn(DateTime day) {
    if (dueDate == null) return false;
    final due = DateTime.tryParse(dueDate!);
    return due != null && day.isAfter(due);
  }
}

/// The profile may legitimately not be set yet, and that is information, not an error.
class PlatformBilling {
  final PlatformProfile? profile;
  final List<Receivable> owed;

  /// The dunning stage per invoice id. Absent for an invoice nothing has been done about yet.
  final Map<String, DunningStage> stages;

  const PlatformBilling({required this.profile, required this.owed, required this.stages});

  num get totalOwed => owed.fold<num>(0, (sum, r) => sum + (r.outstanding ?? 0));

  int get suspendedCount => stages.values.where((s) => s.suspended).length;
}

final platformBillingProvider = FutureProvider.autoDispose<PlatformBilling>((ref) async {
  final dio = ref.watch(apiClientProvider).dio;
  PlatformProfile? profile;
  try {
    final resp = await dio.get('/${ApiConstants.tenant}/platform/billing/profile');
    profile = PlatformProfile.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
  } on Object {
    // 409 BILLING_PROFILE_NOT_SET: nothing has been filled in. Shown as such below.
    profile = null;
  }
  final owed = await dio.get('/${ApiConstants.tenant}/platform/billing/receivables?limit=100');
  // The stages come from the dunning side, keyed on the invoice. Read separately and joined here
  // rather than folded into the receivables response: what is owed and what has been done about it
  // are two questions, and an operator may want the first even when dunning is switched off.
  final stages = <String, DunningStage>{};
  try {
    final chased =
        await dio.get('/${ApiConstants.tenant}/platform/billing/dunning/overdue?limit=100');
    for (final o in chased.data['data'] as List<dynamic>? ?? const []) {
      final row = Map<String, dynamic>.from(o as Map);
      stages[row['invoiceId'] as String] = DunningStage.fromJson(row);
    }
  } on Object {
    // Dunning is the platform's own and may legitimately be off; the arrears still show.
  }
  return PlatformBilling(
    profile: profile,
    owed: [
      for (final r in owed.data['data'] as List<dynamic>? ?? const [])
        Receivable.fromJson(Map<String, dynamic>.from(r as Map)),
    ],
    stages: stages,
  );
});

class PlatformBillingScreen extends ConsumerWidget {
  const PlatformBillingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final billing = ref.watch(platformBillingProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    void refresh() => ref.invalidate(platformBillingProvider);

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text('Billing', style: text.headlineSmall)),
              IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            'What the platform invoices as, and what is still owed.',
            style: text.bodyMedium?.copyWith(color: cs.outline),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: billing.when(
              loading: () => const LoadingView(label: 'Loading the books…'),
              error: (e, _) => ErrorView(
                message: friendlyError(e, fallback: 'Could not load the billing details.'),
                onRetry: refresh,
              ),
              data: (b) => ListView(
                children: [
                  if (b.profile == null)
                    Card(
                      key: const Key('profile-unset'),
                      color: cs.errorContainer,
                      child: Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          'The platform has not said who it invoices as, so nothing can be billed. '
                          'An invoice with no seller is not an invoice anywhere it trades.',
                          style: text.bodyMedium?.copyWith(color: cs.onErrorContainer),
                        ),
                      ),
                    )
                  else
                    Card(
                      key: const Key('profile'),
                      child: ListTile(
                        title: Text(b.profile!.legalName, style: text.titleMedium),
                        subtitle: Text(b.profile!.says, style: text.bodySmall),
                        trailing: b.profile!.vatNumber == null
                            ? null
                            : Text('VAT ${b.profile!.vatNumber}', style: text.bodySmall),
                      ),
                    ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(child: Text('Owed', style: text.titleMedium)),
                      if (b.suspendedCount > 0)
                        Padding(
                          padding: const EdgeInsets.only(right: 12),
                          child: Text(
                            key: const Key('suspended-count'),
                            '${b.suspendedCount} suspended',
                            style: text.bodyMedium?.copyWith(color: cs.error),
                          ),
                        ),
                      Text(
                        key: const Key('total-owed'),
                        b.owed.isEmpty ? 'nothing' : b.totalOwed.toStringAsFixed(2),
                        style: text.titleMedium,
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  if (b.owed.isEmpty)
                    Text(
                      key: const Key('owed-none'),
                      'Every invoice is settled.',
                      style: text.bodyMedium?.copyWith(color: cs.outline),
                    )
                  else
                    for (final r in b.owed)
                      _ReceivableRow(receivable: r, stage: b.stages[r.id]),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ReceivableRow extends StatelessWidget {
  final Receivable receivable;
  final DunningStage? stage;

  const _ReceivableRow({required this.receivable, this.stage});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final r = receivable;
    final overdue = r.overdueOn(DateTime.now());
    final suspended = stage?.suspended == true;
    return ListTile(
      key: Key('owed-${r.number}'),
      dense: true,
      leading: Icon(
        suspended
            ? Icons.block
            : overdue
                ? Icons.warning_amber
                : Icons.schedule,
        color: suspended || overdue ? cs.error : cs.outline,
        size: 20,
      ),
      title: Text(r.number, style: text.bodyLarge),
      subtitle: Text(
        [
          if (overdue) 'Overdue since ${r.dueDate}' else 'Due ${r.dueDate ?? '—'}',
          // What has been done, and what is coming. An operator who can see "suspended next" can act
          // before a customer telephones to say the till has stopped working.
          ?stage?.stageSays,
          ?stage?.nextSays,
        ].join(' · '),
        style: text.bodySmall?.copyWith(color: suspended || overdue ? cs.error : cs.outline),
      ),
      trailing: Text('${r.currency ?? ''} ${r.outstanding ?? 0}', style: text.bodyLarge),
    );
  }
}
