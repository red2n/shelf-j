import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'storefront_providers.dart';

class StorefrontOrdersScreen extends ConsumerWidget {
  const StorefrontOrdersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final orders = ref.watch(storefrontOrdersProvider);
    final cs = Theme.of(context).colorScheme;

    if (orders.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.list_alt_outlined, size: 64, color: cs.outlineVariant),
            const SizedBox(height: 16),
            const Text('No orders yet'),
            const SizedBox(height: 8),
            Text('Orders you place will appear here.',
                style: TextStyle(color: cs.outline)),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: () => context.go('/store/products'),
              icon: const Icon(Icons.storefront),
              label: const Text('Browse products'),
            ),
          ],
        ),
      );
    }

    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: orders.length,
      separatorBuilder: (_, __) => const SizedBox(height: 4),
      itemBuilder: (_, i) {
        final o = orders[i];
        final shortId =
            o.orderId.length >= 8 ? o.orderId.substring(0, 8) : o.orderId;
        return Card(
          child: ListTile(
            leading: CircleAvatar(
              backgroundColor: cs.primaryContainer,
              child: Icon(Icons.receipt_long_outlined,
                  color: cs.onPrimaryContainer),
            ),
            title: Text('Order #$shortId',
                style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: Text(
                '${o.itemCount} item${o.itemCount == 1 ? '' : 's'} · ${_fmtDate(o.placedAt)}'),
            trailing: Text('${o.currency} ${o.total.toStringAsFixed(2)}',
                style: const TextStyle(fontWeight: FontWeight.bold)),
          ),
        );
      },
    );
  }

  String _fmtDate(DateTime d) {
    final l = d.toLocal();
    String two(int n) => n.toString().padLeft(2, '0');
    return '${l.year}-${two(l.month)}-${two(l.day)} ${two(l.hour)}:${two(l.minute)}';
  }
}
