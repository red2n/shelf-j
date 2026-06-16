import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;

class StorefrontOrdersScreen extends ConsumerWidget {
  const StorefrontOrdersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(storefrontAuthProvider);

    // Signed-in customers get their real, cross-device order history from the
    // server. Guests see only orders placed on this device.
    if (auth.isSignedIn) {
      final async = ref.watch(serverOrdersProvider);
      return RefreshIndicator(
        onRefresh: () async => ref.invalidate(serverOrdersProvider),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => _ErrorState(
            message: 'Could not load your orders.',
            onRetry: () => ref.invalidate(serverOrdersProvider),
          ),
          data: (orders) {
            final list = orders ?? const [];
            if (list.isEmpty) return const _EmptyState();
            return ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: list.length,
              separatorBuilder: (_, __) => const SizedBox(height: 4),
              itemBuilder: (_, i) => _ServerOrderTile(order: list[i]),
            );
          },
        ),
      );
    }

    // Guest: device-local fallback + a nudge to sign in for synced history.
    final orders = ref.watch(storefrontOrdersProvider);
    return Column(
      children: [
        const _SignInBanner(),
        Expanded(
          child: orders.isEmpty
              ? const _EmptyState()
              : ListView.separated(
                  padding: const EdgeInsets.all(16),
                  itemCount: orders.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 4),
                  itemBuilder: (_, i) => _LocalOrderTile(order: orders[i]),
                ),
        ),
      ],
    );
  }
}

class _ServerOrderTile extends StatelessWidget {
  final ServerOrderSummary order;
  const _ServerOrderTile({required this.order});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final shortId = order.id.length >= 8 ? order.id.substring(0, 8) : order.id;
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: cs.primaryContainer,
          child: Icon(Icons.receipt_long_outlined, color: cs.onPrimaryContainer),
        ),
        title: Text('Order #$shortId',
            style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(_fmtDate(order.placedAt)),
        trailing: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text('${order.currency} ${order.total.toStringAsFixed(2)}',
                style: const TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 2),
            _StatusChip(status: order.status),
          ],
        ),
      ),
    );
  }
}

class _LocalOrderTile extends StatelessWidget {
  final StorefrontOrderRecord order;
  const _LocalOrderTile({required this.order});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final shortId =
        order.orderId.length >= 8 ? order.orderId.substring(0, 8) : order.orderId;
    return Card(
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: cs.primaryContainer,
          child: Icon(Icons.receipt_long_outlined, color: cs.onPrimaryContainer),
        ),
        title: Text('Order #$shortId',
            style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(
            '${order.itemCount} item${order.itemCount == 1 ? '' : 's'} · ${_fmtDate(order.placedAt)}'),
        trailing: Text('${order.currency} ${order.total.toStringAsFixed(2)}',
            style: const TextStyle(fontWeight: FontWeight.bold)),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String status;
  const _StatusChip({required this.status});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final (Color bg, Color fg) = switch (status.toUpperCase()) {
      'FULFILLED' => (cs.tertiaryContainer, cs.onTertiaryContainer),
      'CONFIRMED' => (cs.primaryContainer, cs.onPrimaryContainer),
      'CANCELLED' || 'VOIDED' => (cs.errorContainer, cs.onErrorContainer),
      _ => (cs.surfaceContainerHighest, cs.onSurfaceVariant),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Text(status,
          style: TextStyle(color: fg, fontSize: 11, fontWeight: FontWeight.w600)),
    );
  }
}

/// Shown to guests: their history is device-only until they sign in.
class _SignInBanner extends ConsumerWidget {
  const _SignInBanner();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: cs.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
        child: Row(
          children: [
            Icon(Icons.info_outline, size: 18, color: cs.onSecondaryContainer),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Showing orders from this device. Sign in to see your full order history.',
                style: TextStyle(color: cs.onSecondaryContainer, fontSize: 13),
              ),
            ),
            TextButton(
              onPressed: () => showDialog(
                  context: context,
                  builder: (_) => const StorefrontAuthDialog()),
              child: const Text('Sign in'),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
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
}

class _ErrorState extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _ErrorState({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.error_outline, size: 64, color: cs.error),
          const SizedBox(height: 16),
          Text(message),
          const SizedBox(height: 16),
          OutlinedButton.icon(
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
            label: const Text('Retry'),
          ),
        ],
      ),
    );
  }
}

String _fmtDate(DateTime d) {
  final l = d.toLocal();
  String two(int n) => n.toString().padLeft(2, '0');
  return '${l.year}-${two(l.month)}-${two(l.day)} ${two(l.hour)}:${two(l.minute)}';
}
