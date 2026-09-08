import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/offline/offline_queue.dart';
import '../../core/offline/offline_sale.dart';

/// Sales the till took but the server has not accepted yet.
///
/// This screen exists because an invisible queue is worse than no queue: the
/// cashier has taken real money, and needs to be able to see that it is still
/// owed to the server, retry it, and — for a sale the server has permanently
/// refused — decide what to do about it.
class OfflineQueueScreen extends ConsumerWidget {
  const OfflineQueueScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sales = ref.watch(offlineQueueProvider);
    final cs = Theme.of(context).colorScheme;

    if (sales.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.cloud_done_outlined, size: 48, color: cs.outline),
            const SizedBox(height: 12),
            Text('Everything is synced',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text('Sales taken while offline appear here until the server has them.',
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.outline)),
          ],
        ),
      );
    }

    final waiting = sales.where((s) => s.status == OfflineSaleStatus.pending).length;
    return Column(
      children: [
        Material(
          color: cs.surfaceContainerHighest,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    '${sales.length} sale${sales.length == 1 ? '' : 's'} not yet on the server'
                    '${waiting < sales.length ? ' · ${sales.length - waiting} need attention' : ''}',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                FilledButton.tonalIcon(
                  onPressed: () => ref.read(offlineQueueProvider.notifier).sync(),
                  icon: const Icon(Icons.sync, size: 18),
                  label: const Text('Sync now'),
                ),
              ],
            ),
          ),
        ),
        Expanded(
          child: ListView.separated(
            itemCount: sales.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (_, i) => _SaleTile(sale: sales[i]),
          ),
        ),
      ],
    );
  }
}

class _SaleTile extends ConsumerWidget {
  final OfflineSale sale;

  const _SaleTile({required this.sale});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final failed = sale.status == OfflineSaleStatus.failed;
    final at = sale.capturedAt.toLocal();
    String two(int n) => n.toString().padLeft(2, '0');

    return ListTile(
      leading: Icon(
        failed ? Icons.error_outline : Icons.schedule,
        color: failed ? cs.error : cs.outline,
      ),
      title: Text('Sale #${sale.reference}  ·  '
          '${sale.currency} ${sale.total.toStringAsFixed(2)}'),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${sale.itemCount} item${sale.itemCount == 1 ? '' : 's'} · '
              '${at.year}-${two(at.month)}-${two(at.day)} ${two(at.hour)}:${two(at.minute)}'
              '${sale.attempts > 0 ? ' · ${sale.attempts} attempt${sale.attempts == 1 ? '' : 's'}' : ''}'),
          if (sale.lastError != null)
            Text(sale.lastError!,
                style: TextStyle(color: failed ? cs.error : cs.outline)),
        ],
      ),
      isThreeLine: sale.lastError != null,
      trailing: failed
          ? Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  tooltip: 'Try again',
                  icon: const Icon(Icons.refresh),
                  onPressed: () => ref.read(offlineQueueProvider.notifier).retry(sale.id),
                ),
                IconButton(
                  tooltip: 'Discard',
                  icon: Icon(Icons.delete_outline, color: cs.error),
                  onPressed: () => _confirmDiscard(context, ref),
                ),
              ],
            )
          : const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2)),
    );
  }

  /// Discarding tells the server nothing ever happened, while the customer has
  /// paid — so it is confirmed explicitly and only offered once the server has
  /// refused the sale outright.
  Future<void> _confirmDiscard(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Discard sale #${sale.reference}?'),
        content: const Text(
            'The server will never be told about this sale. The customer has '
            'already paid, so only discard it once it has been settled another way.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Keep')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(ctx).colorScheme.error),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Discard'),
          ),
        ],
      ),
    );
    if (ok == true) await ref.read(offlineQueueProvider.notifier).discard(sale.id);
  }
}
