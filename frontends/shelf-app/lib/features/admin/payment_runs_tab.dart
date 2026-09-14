import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/file_download.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'procurement_providers.dart';

/// Whether the signed-in user may run supplier payments (17.10): a manager
/// holding finance.payments. The server refuses anyone else with 403, so the
/// tab does not offer controls that can only fail.
bool canRunPayments(AuthState? auth) =>
    auth is AuthAuthenticated &&
    auth.isManager &&
    auth.hasPermission('finance.payments');

/// What a warning on a supplier in a run means, in words.
String paymentWarningText(String code) => switch (code) {
  'BANK_DETAILS_CHANGED_RECENTLY' =>
    'Bank details changed in the last 14 days: confirm them with the supplier by phone',
  _ => code,
};

/// Why a supplier with something due is not paid by a run.
String paymentExcludedText(String code) => switch (code) {
  'NO_BANK_DETAILS' => 'No bank details on file',
  'NET_NOT_POSITIVE' => 'Credit notes cover the invoices',
  _ => code,
};

String _isoDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// Supplier payment runs: propose, approve, download the bank file, mark paid.
class PaymentRunsTab extends ConsumerWidget {
  const PaymentRunsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final cs = Theme.of(context).colorScheme;
    if (!canRunPayments(auth)) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.lock_outline, size: 48, color: cs.outlineVariant),
              const SizedBox(height: 12),
              const Text(
                'Supplier payments need the finance.payments permission.',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      );
    }
    final me = auth as AuthAuthenticated;
    return ref
        .watch(paymentRunsProvider)
        .when(
          loading: () => const LoadingView(label: 'Loading payment runs…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load payment runs.'),
            onRetry: () => ref.invalidate(paymentRunsProvider),
          ),
          data: (runs) {
            if (runs.isEmpty) {
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.payments_outlined,
                      size: 64,
                      color: cs.outlineVariant,
                    ),
                    const SizedBox(height: 12),
                    const Text('No payment runs yet'),
                    const SizedBox(height: 4),
                    Text(
                      'Propose one to pay the invoices falling due.',
                      style: TextStyle(color: cs.onSurfaceVariant),
                    ),
                  ],
                ),
              );
            }
            return ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: runs.length,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (_, i) => PaymentRunCard(run: runs[i], me: me),
            );
          },
        );
  }
}

/// One run: what it pays each supplier, and the next step it is waiting for.
class PaymentRunCard extends ConsumerStatefulWidget {
  const PaymentRunCard({super.key, required this.run, required this.me});
  final PaymentRun run;
  final AuthAuthenticated me;

  @override
  ConsumerState<PaymentRunCard> createState() => _PaymentRunCardState();
}

class _PaymentRunCardState extends ConsumerState<PaymentRunCard> {
  bool _busy = false;

  PaymentRun get run => widget.run;

  /// The proposer cannot approve their own run unless they own the business.
  bool get _ownRun =>
      run.proposedBy == widget.me.userId && !widget.me.roles.contains('OWNER');

  Future<void> _act(
    String action, {
    Map<String, dynamic>? data,
    required String done,
  }) async {
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/payment-runs/${run.id}/$action',
            data: data ?? const <String, dynamic>{},
          );
      ref.invalidate(paymentRunsProvider);
      if (action == 'pay') ref.invalidate(supplierInvoicesProvider);
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(done)));
    } catch (e) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(
              friendlyError(e, fallback: 'Could not $action the payment run.'),
            ),
          ),
        );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pay() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Mark ${run.reference} paid?'),
        content: Text(
          'Pays ${AppFormat.money(run.total, currencyCode: run.currency)} to '
          '${run.suppliers.length} supplier(s) on ${run.paymentDate}. The '
          'invoices are settled, the ledger is posted and each supplier is '
          'sent a remittance advice. Upload the bank file to the bank first.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Not yet'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Mark paid'),
          ),
        ],
      ),
    );
    if (ok == true) {
      await _act('pay', done: 'Payment run ${run.reference} paid.');
    }
  }

  Future<void> _cancel() async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const CancelPaymentRunDialog(),
    );
    if (reason != null) {
      await _act(
        'cancel',
        data: {'reason': reason},
        done: 'Payment run ${run.reference} cancelled.',
      );
    }
  }

  Future<void> _bankFile() async {
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get<String>(
            '/${ApiConstants.purchase}/payment-runs/${run.id}/bank-file',
            options: Options(responseType: ResponseType.plain),
          );
      downloadTextFile(
        '${run.reference.toLowerCase()}.csv',
        resp.data ?? '',
        mimeType: 'text/csv;charset=utf-8',
      );
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(content: Text('Bank file for ${run.reference} downloaded.')),
        );
    } catch (e) {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            content: Text(
              friendlyError(e, fallback: 'Could not download the bank file.'),
            ),
          ),
        );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final toCheck = run.suppliers.where((s) => s.warnings.isNotEmpty).length;
    final approve = FilledButton(
      onPressed: _busy || _ownRun
          ? null
          : () =>
                _act('approve', done: 'Payment run ${run.reference} approved.'),
      child: const Text('Approve'),
    );
    return Card(
      child: ExpansionTile(
        initiallyExpanded: run.proposed || run.approved,
        leading: Icon(
          run.paid
              ? Icons.check_circle_outline
              : run.cancelled
              ? Icons.block_outlined
              : Icons.payments_outlined,
          color: run.cancelled ? cs.outline : cs.primary,
        ),
        title: Text(
          run.reference,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Text(
          [
            AppFormat.money(run.total, currencyCode: run.currency),
            run.status,
            'pay on ${run.paymentDate}',
            'due by ${run.payUpTo}',
            '${run.suppliers.length} supplier(s)',
            if (toCheck > 0) '$toCheck to check',
          ].join(' · '),
        ),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        children: [
          for (final s in run.suppliers)
            _SupplierBlock(supplier: s, currency: run.currency),
          if (run.excluded.isNotEmpty) ...[
            const Divider(),
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Not paid',
                style: Theme.of(context).textTheme.titleSmall,
              ),
            ),
            for (final e in run.excluded)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                leading: Icon(Icons.block, color: cs.error),
                title: Text(e.name),
                subtitle: Text(paymentExcludedText(e.reason)),
                trailing: Text(
                  AppFormat.money(e.net, currencyCode: run.currency),
                ),
              ),
          ],
          if (run.cancelled && run.cancelReason != null)
            Align(
              alignment: Alignment.centerLeft,
              child: Text('Cancelled: ${run.cancelReason}'),
            ),
          const SizedBox(height: 8),
          Wrap(
            alignment: WrapAlignment.end,
            spacing: 8,
            runSpacing: 8,
            children: [
              if (run.proposed || run.approved)
                TextButton(
                  onPressed: _busy ? null : _cancel,
                  child: const Text('Cancel run'),
                ),
              if (run.approved || run.paid)
                OutlinedButton.icon(
                  onPressed: _busy ? null : _bankFile,
                  icon: const Icon(Icons.download_outlined),
                  label: const Text('Bank file'),
                ),
              if (run.proposed)
                _ownRun
                    ? Tooltip(
                        message: 'Another manager approves a run you proposed',
                        child: approve,
                      )
                    : approve,
              if (run.approved)
                FilledButton.icon(
                  onPressed: _busy ? null : _pay,
                  icon: const Icon(Icons.payments_outlined),
                  label: const Text('Mark paid'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SupplierBlock extends StatelessWidget {
  const _SupplierBlock({required this.supplier, required this.currency});
  final PaymentRunSupplier supplier;
  final String currency;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    const bold = TextStyle(fontWeight: FontWeight.bold);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(supplier.name, style: bold)),
              Text(
                AppFormat.money(supplier.net, currencyCode: currency),
                style: bold,
              ),
            ],
          ),
          if (!supplier.remittanceEmailOnFile)
            Text(
              'No remittance email: the advice will not be sent',
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
            ),
          for (final w in supplier.warnings)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Icon(Icons.warning_amber_rounded, color: cs.error, size: 18),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      paymentWarningText(w),
                      style: TextStyle(color: cs.error),
                    ),
                  ),
                ],
              ),
            ),
          for (final d in supplier.documents)
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${d.isCredit ? 'Credit note' : 'Invoice'} ${d.reference}'
                    '${d.dueDate != null ? ' · due ${d.dueDate}' : ''}',
                  ),
                ),
                Text(
                  '${d.isCredit ? '-' : ''}${AppFormat.money(d.amount, currencyCode: currency)}',
                ),
              ],
            ),
        ],
      ),
    );
  }
}

/// Proposes a run from the invoices due by a date.
class ProposePaymentRunDialog extends ConsumerStatefulWidget {
  const ProposePaymentRunDialog({super.key});

  @override
  ConsumerState<ProposePaymentRunDialog> createState() =>
      _ProposePaymentRunDialogState();
}

class _ProposePaymentRunDialogState
    extends ConsumerState<ProposePaymentRunDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _payUpTo;
  late final TextEditingController _paymentDate;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _payUpTo = TextEditingController(
      text: _isoDate(now.add(const Duration(days: 7))),
    );
    _paymentDate = TextEditingController(text: _isoDate(now));
  }

  @override
  void dispose() {
    _payUpTo.dispose();
    _paymentDate.dispose();
    super.dispose();
  }

  /// A real calendar date as YYYY-MM-DD: 2026-02-30 is refused, not rolled
  /// into March.
  static String? validDate(String? v) {
    final t = v?.trim() ?? '';
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(t)) return 'Use YYYY-MM-DD';
    final parsed = DateTime.tryParse(t);
    if (parsed == null || _isoDate(parsed) != t) return 'Not a date';
    return null;
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/payment-runs',
            data: {
              'payUpTo': _payUpTo.text.trim(),
              'paymentDate': _paymentDate.text.trim(),
            },
          );
      final run = PaymentRun.fromJson(
        (resp.data['data'] as Map).cast<String, dynamic>(),
      );
      if (!mounted) return;
      ref.invalidate(paymentRunsProvider);
      Navigator.pop(context);
      final left = run.excluded.length;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Proposed ${run.reference}: '
            '${AppFormat.money(run.total, currencyCode: run.currency)} to '
            '${run.suppliers.length} supplier(s)'
            '${left > 0 ? ', $left left out' : ''}.',
          ),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not propose a payment run.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Propose payment run'),
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
              const Text(
                'Every matched or approved invoice due by the date, less each '
                "supplier's credit notes. A second manager approves it.",
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _payUpTo,
                decoration: const InputDecoration(
                  labelText: 'Pay invoices due by',
                  helperText: 'YYYY-MM-DD',
                  prefixIcon: Icon(Icons.event_outlined),
                ),
                validator: validDate,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _paymentDate,
                decoration: const InputDecoration(
                  labelText: 'Payment date',
                  helperText: 'Today or later',
                  prefixIcon: Icon(Icons.today_outlined),
                ),
                validator: validDate,
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
          child: const Text('Propose'),
        ),
      ],
    );
  }
}

/// Asks why a run is abandoned; returns the reason, or null when dismissed.
class CancelPaymentRunDialog extends StatefulWidget {
  const CancelPaymentRunDialog({super.key});

  @override
  State<CancelPaymentRunDialog> createState() => _CancelPaymentRunDialogState();
}

class _CancelPaymentRunDialogState extends State<CancelPaymentRunDialog> {
  final _formKey = GlobalKey<FormState>();
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Cancel payment run'),
      content: Form(
        key: _formKey,
        child: TextFormField(
          controller: _reason,
          maxLength: 500,
          decoration: const InputDecoration(labelText: 'Reason *'),
          validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Keep it'),
        ),
        FilledButton(
          onPressed: () {
            if (_formKey.currentState!.validate()) {
              Navigator.pop(context, _reason.text.trim());
            }
          },
          child: const Text('Cancel run'),
        ),
      ],
    );
  }
}
