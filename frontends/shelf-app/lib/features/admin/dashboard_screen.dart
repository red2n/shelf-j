import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../shared/widgets/error_view.dart';
import 'providers/admin_providers.dart';
import 'providers/inventory_levels_pagination.dart';

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ordersAsync = ref.watch(recentOrdersProvider);
    final inventoryAsync = ref.watch(inventoryLevelsSummaryProvider);
    final tenantAsync = ref.watch(tenantInfoProvider);
    final alertsAsync = ref.watch(shortageAlertsProvider);

    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(recentOrdersProvider);
        ref.invalidate(inventoryLevelsSummaryProvider);
        ref.invalidate(tenantInfoProvider);
        ref.invalidate(shortageAlertsProvider);
      },
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Tenant header
            tenantAsync.when(
              loading: () => Text('Dashboard',
                  style: Theme.of(context).textTheme.headlineMedium),
              error: (_, __) => Text('Dashboard',
                  style: Theme.of(context).textTheme.headlineMedium),
              data: (t) => Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Dashboard',
                            style: Theme.of(context).textTheme.headlineMedium),
                        Text(t.name,
                            style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                                  color: Theme.of(context).colorScheme.outline,
                                )),
                      ],
                    ),
                  ),
                  Chip(
                    avatar: Icon(Icons.currency_exchange,
                        size: 14,
                        color: Theme.of(context).colorScheme.onSecondaryContainer),
                    label: Text(t.currency),
                    backgroundColor:
                        Theme.of(context).colorScheme.secondaryContainer,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            // Low-stock shortage alerts (notification-svc)
            alertsAsync.maybeWhen(
              data: (alerts) => alerts.isEmpty
                  ? const SizedBox.shrink()
                  : _ShortageAlertsBanner(alerts: alerts),
              orElse: () => const SizedBox.shrink(),
            ),

            // Stat cards — responsive 2-col on mobile, 4-col on wide
            LayoutBuilder(builder: (context, bc) {
              final cols = bc.maxWidth >= 720 ? 4 : 2;
              final orders = ordersAsync.valueOrNull ?? [];
              final summary = inventoryAsync.valueOrNull;
              final revenue = orders.fold<double>(0, (s, o) => s + o.total);
              final lowStock = summary?.lowStockCount ?? 0;
              final skuCount = summary?.skuCount ?? 0;

              return GridView.count(
                crossAxisCount: cols,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                crossAxisSpacing: 12,
                mainAxisSpacing: 12,
                childAspectRatio: 1.5,
                children: [
                  _StatCard(
                    label: 'Revenue',
                    value: ordersAsync.isLoading
                        ? '…'
                        : '${_currencySymbol(tenantAsync.valueOrNull?.currency)}${revenue.toStringAsFixed(0)}',
                    icon: Icons.attach_money,
                    loading: ordersAsync.isLoading,
                  ),
                  _StatCard(
                    label: 'Orders',
                    value: ordersAsync.isLoading ? '…' : '${orders.length}',
                    icon: Icons.receipt_long_outlined,
                    loading: ordersAsync.isLoading,
                  ),
                  _StatCard(
                    label: 'Low Stock',
                    value: inventoryAsync.isLoading ? '…' : '$lowStock',
                    icon: Icons.warning_amber_outlined,
                    loading: inventoryAsync.isLoading,
                    alert: lowStock > 0,
                  ),
                  _StatCard(
                    label: 'SKUs',
                    value: inventoryAsync.isLoading ? '…' : '$skuCount',
                    icon: Icons.inventory_2_outlined,
                    loading: inventoryAsync.isLoading,
                  ),
                ],
              );
            }),
            const SizedBox(height: 32),

            // Stores strip
            storesSection(context, ref),
            const SizedBox(height: 32),

            // Recent orders
            Row(
              children: [
                Text('Recent Orders',
                    style: Theme.of(context).textTheme.titleLarge),
                const Spacer(),
                TextButton(
                  onPressed: () => context.go('/admin/orders'),
                  child: const Text('See all →'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            ordersAsync.when(
              loading: () => const Center(
                child: Padding(
                    padding: EdgeInsets.all(32),
                    child: CircularProgressIndicator()),
              ),
              error: (e, _) => ErrorView(
                message: 'Could not load orders',
                onRetry: () => ref.invalidate(recentOrdersProvider),
              ),
              data: (orders) => orders.isEmpty
                  ? _emptyState(context, Icons.receipt_long_outlined,
                      'No orders yet', 'Orders will appear here once customers start buying.')
                  : Card(
                      child: ListView.separated(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: orders.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (context, i) {
                          final o = orders[i];
                          return ListTile(
                            leading: CircleAvatar(
                              backgroundColor: o.channel == 'POS'
                                  ? Colors.orange.shade100
                                  : Colors.blue.shade100,
                              child: Icon(
                                o.channel == 'POS'
                                    ? Icons.point_of_sale
                                    : Icons.shopping_bag_outlined,
                                size: 18,
                                color: o.channel == 'POS'
                                    ? Colors.orange.shade700
                                    : Colors.blue.shade700,
                              ),
                            ),
                            title: Text('#${o.id.length >= 8 ? o.id.substring(0, 8) : o.id}…',
                                style: const TextStyle(fontFamily: 'monospace')),
                            subtitle: Text(
                                o.createdAt.length >= 10
                                    ? o.createdAt.substring(0, 10)
                                    : o.createdAt),
                            trailing: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                _StatusBadge(o.status),
                                const SizedBox(width: 12),
                                Text(
                                  '${o.currency} ${o.total.toStringAsFixed(2)}',
                                  style: Theme.of(context).textTheme.titleSmall,
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget storesSection(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storesProvider);
    return storesAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
      data: (stores) {
        if (stores.isEmpty) return const SizedBox.shrink();
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Your Stores', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            SizedBox(
              height: 100,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: stores.length,
                separatorBuilder: (_, __) => const SizedBox(width: 12),
                itemBuilder: (context, i) {
                  final s = stores[i];
                  final cs = Theme.of(context).colorScheme;
                  return SizedBox(
                    width: 200,
                    child: Card(
                      color: cs.surfaceContainerHighest,
                      child: Padding(
                        padding: const EdgeInsets.all(14),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Row(
                              children: [
                                Icon(
                                  s.type == 'WAREHOUSE'
                                      ? Icons.warehouse_outlined
                                      : Icons.store_outlined,
                                  size: 16,
                                  color: cs.primary,
                                ),
                                const SizedBox(width: 6),
                                Expanded(
                                  child: Text(s.name,
                                      style: const TextStyle(
                                          fontWeight: FontWeight.bold,
                                          fontSize: 13),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis),
                                ),
                              ],
                            ),
                            Text(s.code,
                                style: TextStyle(
                                    fontFamily: 'monospace',
                                    fontSize: 11,
                                    color: cs.outline)),
                            _StoreStatusDot(s.status),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _emptyState(
      BuildContext context, IconData icon, String title, String subtitle) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: cs.outlineVariant),
            const SizedBox(height: 12),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            Text(subtitle,
                style: Theme.of(context)
                    .textTheme
                    .bodyMedium
                    ?.copyWith(color: cs.outline),
                textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }

  String _currencySymbol(String? currency) {
    switch (currency) {
      case 'INR':
        return '₹';
      case 'USD':
        return '\$';
      case 'GBP':
        return '£';
      default:
        return '';
    }
  }
}

class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  final IconData icon;
  final bool loading;
  final bool alert;

  const _StatCard({
    required this.label,
    required this.value,
    required this.icon,
    this.loading = false,
    this.alert = false,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bg = alert ? cs.errorContainer : cs.primaryContainer;
    final fg = alert ? cs.onErrorContainer : cs.onPrimaryContainer;
    return Card(
      color: bg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Icon(icon, color: fg, size: 22),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (loading)
                  SizedBox(
                    height: 24,
                    child: LinearProgressIndicator(
                      backgroundColor: fg.withAlpha(40),
                      valueColor: AlwaysStoppedAnimation(fg),
                    ),
                  )
                else
                  Text(value,
                      style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                            color: fg,
                            fontWeight: FontWeight.bold,
                          )),
                const SizedBox(height: 2),
                Text(label,
                    style: TextStyle(color: fg.withAlpha(180), fontSize: 11)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  final String status;
  const _StatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    Color bg;
    Color fg;
    switch (status.toUpperCase()) {
      case 'PLACED':
        bg = Colors.blue.shade100;
        fg = Colors.blue.shade800;
        break;
      case 'CONFIRMED':
        bg = Colors.green.shade100;
        fg = Colors.green.shade800;
        break;
      case 'FULFILLED':
        bg = Colors.teal.shade100;
        fg = Colors.teal.shade800;
        break;
      case 'CANCELLED':
      case 'VOIDED':
        bg = Colors.red.shade100;
        fg = Colors.red.shade800;
        break;
      default:
        bg = Colors.grey.shade200;
        fg = Colors.grey.shade700;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Text(status,
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg)),
    );
  }
}

class _StoreStatusDot extends StatelessWidget {
  final String status;
  const _StoreStatusDot(this.status);

  @override
  Widget build(BuildContext context) {
    final active = status.toUpperCase() == 'ACTIVE';
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 6,
          height: 6,
          decoration: BoxDecoration(
            color: active ? Colors.green : Colors.orange,
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 4),
        Text(status,
            style: TextStyle(
                fontSize: 11,
                color: active ? Colors.green.shade700 : Colors.orange.shade700)),
      ],
    );
  }
}

/// Low-stock alert banner shown at the top of the dashboard when stock has
/// fallen below threshold (sourced from notification-svc shortage alerts).
class _ShortageAlertsBanner extends StatelessWidget {
  final List<ShortageAlert> alerts;
  const _ShortageAlertsBanner({required this.alerts});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 24),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.warning_amber_rounded, color: cs.onErrorContainer),
              const SizedBox(width: 8),
              Text('${alerts.length} low-stock alert${alerts.length == 1 ? '' : 's'}',
                  style: TextStyle(
                      color: cs.onErrorContainer, fontWeight: FontWeight.bold)),
            ],
          ),
          const SizedBox(height: 8),
          for (final a in alerts.take(5))
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                'Variant ${a.variantId.length > 8 ? a.variantId.substring(0, 8) : a.variantId}… · '
                'available ${a.available.toStringAsFixed(0)} ≤ threshold ${a.threshold.toStringAsFixed(0)}',
                style: TextStyle(color: cs.onErrorContainer, fontSize: 12),
              ),
            ),
          if (alerts.length > 5)
            Text('…and ${alerts.length - 5} more',
                style: TextStyle(
                    color: cs.onErrorContainer,
                    fontSize: 12,
                    fontStyle: FontStyle.italic)),
        ],
      ),
    );
  }
}
