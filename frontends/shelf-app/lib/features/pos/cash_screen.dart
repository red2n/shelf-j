import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'cash_providers.dart';
import 'pos_providers.dart';

class CashScreen extends ConsumerWidget {
  const CashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(activeTillProvider);
    return session == null
        ? const _OpenTillView()
        : _OpenSessionView(sessionId: session);
  }
}

// ── Open a till ──────────────────────────────────────────────────────────────

class _OpenTillView extends ConsumerStatefulWidget {
  const _OpenTillView();

  @override
  ConsumerState<_OpenTillView> createState() => _OpenTillViewState();
}

class _OpenTillViewState extends ConsumerState<_OpenTillView> {
  final _floatCtrl = TextEditingController(text: '0');
  bool _opening = false;
  String? _error;

  @override
  void dispose() {
    _floatCtrl.dispose();
    super.dispose();
  }

  Future<void> _open() async {
    final storeId = ref.read(posStoreProvider);
    if (storeId == null) {
      setState(() => _error = 'Pick a store on the Sale tab first.');
      return;
    }
    setState(() {
      _opening = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/till-sessions',
        data: {
          'storeId': storeId,
          'floatAmount': double.tryParse(_floatCtrl.text.trim()) ?? 0,
        },
      );
      final session = resp.data['data'] as Map<String, dynamic>;
      ref.read(activeTillProvider.notifier).state = session['id'] as String?;
    } catch (e) {
      setState(() {
        _opening = false;
        _error = friendlyError(e, fallback: 'Could not open till.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(posStoresProvider);
    final storeId = ref.watch(posStoreProvider);
    final storeName = storesAsync.maybeWhen(
      data: (stores) =>
          stores.where((s) => s.id == storeId).map((s) => s.name).firstOrNull,
      orElse: () => null,
    );
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 360),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Icon(Icons.point_of_sale, size: 56, color: cs.primary),
              const SizedBox(height: 12),
              Text('Open till', style: Theme.of(context).textTheme.headlineSmall,
                  textAlign: TextAlign.center),
              const SizedBox(height: 4),
              Text(
                storeName == null
                    ? 'Select a store on the Sale tab first'
                    : 'Store: $storeName',
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.outline),
              ),
              const SizedBox(height: 20),
              if (_error != null) ...[
                Text(_error!, style: TextStyle(color: cs.error)),
                const SizedBox(height: 12),
              ],
              TextField(
                controller: _floatCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(
                  labelText: 'Opening float',
                  prefixIcon: Icon(Icons.attach_money),
                ),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: _opening ? null : _open,
                icon: _opening
                    ?  SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                    : const Icon(Icons.lock_open),
                label: Text(_opening ? 'Opening…' : 'Open till'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Operate an open till ─────────────────────────────────────────────────────

class _OpenSessionView extends ConsumerWidget {
  final String sessionId;
  const _OpenSessionView({required this.sessionId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final reportAsync = ref.watch(xReportProvider(sessionId));
    return reportAsync.when(
      loading: () => const LoadingView(label: 'Loading till…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load till report.'),
        onRetry: () => ref.invalidate(xReportProvider(sessionId)),
      ),
      data: (r) {
        const currency = '';
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Row(
              children: [
                Text('Till session',
                    style: Theme.of(context).textTheme.titleLarge),
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.refresh),
                  tooltip: 'Refresh till session',
                  onPressed: () => ref.invalidate(xReportProvider(sessionId)),
                ),
              ],
            ),
            Text('#${sessionId.length >= 8 ? sessionId.substring(0, 8) : sessionId}',
                style: TextStyle(fontFamily: 'monospace', color: cs.outline)),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    _row('Opening float', r.floatAmount, currency),
                    _row('Gross sales', r.grossSales, currency),
                    _row('Refunds', -r.totalRefunds, currency),
                    _row('Net sales', r.netSales, currency, bold: true),
                    const Divider(),
                    _row('Cash drops', r.cashDropsTotal, currency),
                    _row('Expected cash in till', r.expectedCashInTill, currency,
                        bold: true),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: () => _cashDrop(context, ref),
                  icon: const Icon(Icons.move_down),
                  label: const Text('Cash drop'),
                ),
                OutlinedButton.icon(
                  onPressed: () => _movement(context, ref, 'PAY_IN'),
                  icon: const Icon(Icons.add),
                  label: const Text('Pay in'),
                ),
                OutlinedButton.icon(
                  onPressed: () => _movement(context, ref, 'PAY_OUT'),
                  icon: const Icon(Icons.remove),
                  label: const Text('Pay out'),
                ),
              ],
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              style: FilledButton.styleFrom(backgroundColor: cs.error),
              onPressed: () => _closeTill(context, ref, r.expectedCashInTill),
              icon: const Icon(Icons.lock_outline),
              label: const Text('Close till (Z-report)'),
            ),
          ],
        );
      },
    );
  }

  Widget _row(String label, double value, String currency, {bool bold = false}) {
    final style = TextStyle(fontWeight: bold ? FontWeight.bold : FontWeight.normal);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Text(label, style: style),
          const Spacer(),
          Text('$currency ${value.toStringAsFixed(2)}', style: style),
        ],
      ),
    );
  }

  Future<void> _cashDrop(BuildContext context, WidgetRef ref) async {
    final result = await _amountReasonDialog(context, 'Cash drop', reasonLabel: 'Notes');
    if (result == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/drops',
        data: {'amount': result.amount, 'notes': result.reason},
      );
      ref.invalidate(xReportProvider(sessionId));
      if (!context.mounted) return;
      _toast(context, 'Cash drop recorded.');
    } catch (e) {
      if (!context.mounted) return;
      _toast(context, friendlyError(e, fallback: 'Could not record cash drop.'),
          error: true);
    }
  }

  Future<void> _movement(
      BuildContext context, WidgetRef ref, String direction) async {
    final storeId = ref.read(posStoreProvider);
    final label = direction == 'PAY_IN' ? 'Pay in' : 'Pay out';
    final result = await _amountReasonDialog(context, label, reasonLabel: 'Reason');
    if (result == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/movements',
        data: {
          'tillSessionId': sessionId,
          'storeId': storeId,
          'direction': direction,
          'amount': result.amount,
          'reason': result.reason.isEmpty ? label : result.reason,
        },
      );
      ref.invalidate(xReportProvider(sessionId));
      if (!context.mounted) return;
      _toast(context, '$label recorded.');
    } catch (e) {
      if (!context.mounted) return;
      _toast(
          context,
          friendlyError(e,
              fallback: 'Could not record ${label.toLowerCase()}.'),
          error: true);
    }
  }

  Future<void> _closeTill(
      BuildContext context, WidgetRef ref, double expected) async {
    final countedCtrl = TextEditingController(text: expected.toStringAsFixed(2));
    final counted = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Close till'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Expected cash: ${expected.toStringAsFixed(2)}'),
            const SizedBox(height: 12),
            TextField(
              controller: countedCtrl,
              autofocus: true,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              decoration: const InputDecoration(labelText: 'Counted cash'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () =>
                Navigator.pop(ctx, double.tryParse(countedCtrl.text.trim()) ?? 0),
            child: const Text('Close till'),
          ),
        ],
      ),
    );
    if (counted == null) return;
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/close',
        data: {'countedCash': counted},
      );
      final z = resp.data['data'] as Map<String, dynamic>;
      final overShort = (z['overShort'] as num?)?.toDouble() ?? 0;
      ref.read(activeTillProvider.notifier).state = null;
      if (!context.mounted) return;
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Till closed'),
          content: Text(overShort == 0
              ? 'Balanced — no discrepancy.'
              : '${overShort > 0 ? 'Over' : 'Short'} by ${overShort.abs().toStringAsFixed(2)}'),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
          ],
        ),
      );
    } catch (e) {
      if (!context.mounted) return;
      _toast(context, friendlyError(e, fallback: 'Could not close till.'),
          error: true);
    }
  }

  void _toast(BuildContext context, String msg, {bool error = false}) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    ));
  }
}

class _AmountReason {
  final double amount;
  final String reason;
  const _AmountReason(this.amount, this.reason);
}

Future<_AmountReason?> _amountReasonDialog(BuildContext context, String title,
    {required String reasonLabel}) {
  final amountCtrl = TextEditingController();
  final reasonCtrl = TextEditingController();
  return showDialog<_AmountReason>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: amountCtrl,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Amount'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: reasonCtrl,
            decoration: InputDecoration(labelText: reasonLabel),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            final amt = double.tryParse(amountCtrl.text.trim());
            if (amt == null || amt <= 0) return;
            Navigator.pop(ctx, _AmountReason(amt, reasonCtrl.text.trim()));
          },
          child: const Text('Record'),
        ),
      ],
    ),
  );
}
