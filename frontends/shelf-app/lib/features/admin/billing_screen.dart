import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';

// ---------------------------------------------------------------------------
// What this business pays the platform (21.9).
//
// The subscription it is on, the period it has been billed for, and its own
// invoices. Two things an owner needs to see before they matter: whether the
// subscription ends at the period end, and whether its VAT number has been
// checked — because an unchecked number means it is charged VAT it could
// otherwise account for itself, and nobody thinks to ask.
// ---------------------------------------------------------------------------

class BillingBuyer {
  final String? name;
  final String? country;
  final String? vatNumber;
  final bool vatChecked;
  final String? vatCheckSource;

  const BillingBuyer({
    required this.name,
    required this.country,
    required this.vatNumber,
    required this.vatChecked,
    required this.vatCheckSource,
  });

  factory BillingBuyer.fromJson(Map<String, dynamic> j) => BillingBuyer(
        name: j['name'] as String?,
        country: j['country'] as String?,
        vatNumber: j['vatNumber'] as String?,
        vatChecked: j['vatChecked'] == true,
        vatCheckSource: j['vatCheckSource'] as String?,
      );

  /// Said plainly, because the consequence is money.
  String get vatSays {
    if (vatNumber == null || vatNumber!.isEmpty) return 'No VAT number given';
    if (vatChecked) return '$vatNumber · checked${vatCheckSource == null ? '' : ' ($vatCheckSource)'}';
    return '$vatNumber · not checked yet, so VAT is charged';
  }
}

class Subscription {
  final String status;
  final String? planName;
  final String? planCode;
  final num? priceAmount;
  final String? currency;
  final String? billingInterval;
  final String? periodStart;
  final String? periodEnd;
  final String? trialEnd;
  final bool cancelAtPeriodEnd;
  final bool changePending;
  final BillingBuyer? buyer;

  const Subscription({
    required this.status,
    required this.planName,
    required this.planCode,
    required this.priceAmount,
    required this.currency,
    required this.billingInterval,
    required this.periodStart,
    required this.periodEnd,
    required this.trialEnd,
    required this.cancelAtPeriodEnd,
    required this.changePending,
    required this.buyer,
  });

  factory Subscription.fromJson(Map<String, dynamic> j) => Subscription(
        status: j['status'] as String? ?? '',
        planName: j['planName'] as String?,
        planCode: j['planCode'] as String?,
        priceAmount: j['priceAmount'] as num?,
        currency: j['currency'] as String?,
        billingInterval: j['billingInterval'] as String?,
        periodStart: j['periodStart'] as String?,
        periodEnd: j['periodEnd'] as String?,
        trialEnd: j['trialEnd'] as String?,
        cancelAtPeriodEnd: j['cancelAtPeriodEnd'] == true,
        changePending: j['pendingPlanId'] != null,
        buyer: j['buyer'] == null ? null : BillingBuyer.fromJson(Map<String, dynamic>.from(j['buyer'] as Map)),
      );

  bool get trialing => status == 'TRIALING';
  bool get behind => status == 'PAST_DUE';
}

class Invoice {
  final String id;
  final String number;
  final String status;
  final String? issueDate;
  final String? dueDate;
  final num? totalAmount;
  final num? outstanding;
  final String? currency;
  final String? taxTreatment;

  const Invoice({
    required this.id,
    required this.number,
    required this.status,
    required this.issueDate,
    required this.dueDate,
    required this.totalAmount,
    required this.outstanding,
    required this.currency,
    required this.taxTreatment,
  });

  factory Invoice.fromJson(Map<String, dynamic> j) => Invoice(
        id: j['id'] as String? ?? '',
        number: j['number'] as String? ?? '',
        status: j['status'] as String? ?? '',
        issueDate: j['issueDate'] as String?,
        dueDate: j['dueDate'] as String?,
        totalAmount: j['totalAmount'] as num?,
        outstanding: j['outstanding'] as num?,
        currency: j['currency'] as String?,
        taxTreatment: j['taxTreatment'] as String?,
      );

  bool get owed => status == 'OPEN';

  /// The reverse charge is worth naming: it is why an invoice carries no VAT.
  String? get treatmentSays => switch (taxTreatment) {
        'REVERSE_CHARGE' => 'Reverse charge — no VAT',
        'DESTINATION' => 'Taxed where the business is',
        'OUT_OF_SCOPE' => 'Outside VAT',
        _ => null,
      };
}

class BillingFile {
  final Subscription? subscription;
  final List<Invoice> invoices;

  const BillingFile({required this.subscription, required this.invoices});

  bool get subscribed => subscription != null;
}

final billingProvider = FutureProvider.autoDispose<BillingFile>((ref) async {
  final dio = ref.watch(apiClientProvider).dio;
  final sub = await dio.get('/${ApiConstants.tenant}/admin/tenant/billing');
  final subJson = Map<String, dynamic>.from(sub.data['data'] as Map);
  final invoices = await dio.get('/${ApiConstants.tenant}/admin/tenant/billing/invoices?limit=50');
  return BillingFile(
    subscription: subJson['subscription'] == null
        ? null
        : Subscription.fromJson(Map<String, dynamic>.from(subJson['subscription'] as Map)),
    invoices: [
      for (final i in invoices.data['data'] as List<dynamic>? ?? const [])
        Invoice.fromJson(Map<String, dynamic>.from(i as Map)),
    ],
  );
});

class BillingScreen extends ConsumerWidget {
  const BillingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final billing = ref.watch(billingProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    void refresh() => ref.invalidate(billingProvider);

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
            'What this business pays the platform, and the invoices it has been sent.',
            style: text.bodyMedium?.copyWith(color: cs.outline),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: billing.when(
              loading: () => const LoadingView(label: 'Loading the subscription…'),
              error: (e, _) => ErrorView(
                message: friendlyError(e, fallback: 'Could not load the billing details.'),
                onRetry: refresh,
              ),
              data: (f) => !f.subscribed
                  ? Text(
                      key: const Key('billing-none'),
                      'This business is not subscribed to anything, so nothing is being billed.',
                      style: text.bodyLarge,
                    )
                  : ListView(
                      children: [
                        _SubscriptionCard(subscription: f.subscription!),
                        const SizedBox(height: 16),
                        Text('Invoices', style: text.titleMedium),
                        const SizedBox(height: 8),
                        if (f.invoices.isEmpty)
                          Text('None yet.', style: text.bodyMedium?.copyWith(color: cs.outline))
                        else
                          for (final invoice in f.invoices) _InvoiceRow(invoice: invoice),
                      ],
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SubscriptionCard extends StatelessWidget {
  final Subscription subscription;

  const _SubscriptionCard({required this.subscription});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final s = subscription;
    final price = s.priceAmount == null ? '—' : '${s.currency ?? ''} ${s.priceAmount}';
    return Card(
      key: const Key('subscription'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text(s.planName ?? s.planCode ?? 'Plan', style: text.titleLarge)),
                Chip(label: Text(s.status), visualDensity: VisualDensity.compact),
              ],
            ),
            const SizedBox(height: 8),
            Text('$price per ${s.billingInterval?.toLowerCase() ?? 'period'}', style: text.bodyLarge),
            if (s.periodStart != null && s.periodEnd != null) ...[
              const SizedBox(height: 4),
              Text(
                'Billed ${s.periodStart} to ${s.periodEnd}',
                style: text.bodyMedium?.copyWith(color: cs.outline),
              ),
            ],
            if (s.trialing && s.trialEnd != null) ...[
              const SizedBox(height: 8),
              _Note(key: const Key('note-trial'), text: 'Free until ${s.trialEnd}. The first invoice comes then.', tone: cs.primary),
            ],
            if (s.behind) ...[
              const SizedBox(height: 8),
              _Note(key: const Key('note-behind'), text: 'An invoice is past its date.', tone: cs.error),
            ],
            if (s.cancelAtPeriodEnd) ...[
              const SizedBox(height: 8),
              _Note(key: const Key('note-ending'), text: 'Ends on ${s.periodEnd ?? 'the period end'}.', tone: cs.error),
            ],
            if (s.changePending) ...[
              const SizedBox(height: 8),
              _Note(key: const Key('note-pending'), text: 'A plan change takes effect on ${s.periodEnd ?? 'the period end'}.', tone: cs.primary),
            ],
            if (s.buyer != null) ...[
              const Divider(height: 24),
              Text('Invoiced to', style: text.titleSmall),
              const SizedBox(height: 4),
              Text('${s.buyer!.name ?? '—'} · ${s.buyer!.country ?? '—'}', style: text.bodyMedium),
              const SizedBox(height: 4),
              Text(
                s.buyer!.vatSays,
                style: text.bodySmall?.copyWith(
                  color: s.buyer!.vatChecked ? cs.outline : cs.error,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _InvoiceRow extends StatelessWidget {
  final Invoice invoice;

  const _InvoiceRow({required this.invoice});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final i = invoice;
    final says = i.treatmentSays;
    return ListTile(
      key: Key('invoice-${i.number}'),
      dense: true,
      title: Text(i.number, style: text.bodyLarge),
      subtitle: Text(
        [
          if (i.issueDate != null) 'Issued ${i.issueDate}',
          if (i.owed && i.dueDate != null) 'due ${i.dueDate}',
          ?says,
        ].join(' · '),
        style: text.bodySmall?.copyWith(color: cs.outline),
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text('${i.currency ?? ''} ${i.totalAmount ?? '—'}', style: text.bodyLarge),
          Text(
            i.owed ? '${i.currency ?? ''} ${i.outstanding ?? 0} owed' : i.status.toLowerCase(),
            style: text.bodySmall?.copyWith(color: i.owed ? cs.error : cs.outline),
          ),
        ],
      ),
    );
  }
}

class _Note extends StatelessWidget {
  final String text;
  final Color tone;

  const _Note({required this.text, required this.tone, super.key});

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Icon(Icons.info_outline, size: 16, color: tone),
          const SizedBox(width: 6),
          Expanded(
            child: Text(text, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: tone)),
          ),
        ],
      );
}
