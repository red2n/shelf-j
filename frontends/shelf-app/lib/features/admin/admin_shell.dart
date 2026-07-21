import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'providers/admin_providers.dart';

class _AdminNavItem {
  final AdaptiveNavDestination destination;
  final String route;
  /// When true, shown to storekeeper-only users (warehouse-focused shell).
  final bool storekeeperVisible;

  const _AdminNavItem({
    required this.destination,
    required this.route,
    this.storekeeperVisible = false,
  });
}

const _navItems = [
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Dashboard',
      icon: Icons.dashboard_outlined,
      selectedIcon: Icons.dashboard,
    ),
    route: '/admin/dashboard',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Catalog',
      icon: Icons.inventory_2_outlined,
      selectedIcon: Icons.inventory_2,
    ),
    route: '/admin/catalog',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Inventory',
      icon: Icons.warehouse_outlined,
      selectedIcon: Icons.warehouse,
    ),
    route: '/admin/inventory',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Stores',
      icon: Icons.store_outlined,
      selectedIcon: Icons.store,
    ),
    route: '/admin/stores',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Orders',
      icon: Icons.receipt_long_outlined,
      selectedIcon: Icons.receipt_long,
    ),
    route: '/admin/orders',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Procurement',
      icon: Icons.local_shipping_outlined,
      selectedIcon: Icons.local_shipping,
    ),
    route: '/admin/procurement',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Pricing',
      icon: Icons.sell_outlined,
      selectedIcon: Icons.sell,
    ),
    route: '/admin/pricing',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Reports',
      icon: Icons.bar_chart_outlined,
      selectedIcon: Icons.bar_chart,
    ),
    route: '/admin/reports',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Customers',
      icon: Icons.groups_outlined,
      selectedIcon: Icons.groups,
    ),
    route: '/admin/customers',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Sales',
      icon: Icons.card_giftcard_outlined,
      selectedIcon: Icons.card_giftcard,
    ),
    route: '/admin/sales',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Staff',
      icon: Icons.people_outline,
      selectedIcon: Icons.people,
    ),
    route: '/admin/staff',
  ),
];

class AdminShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const AdminShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenantAsync = ref.watch(tenantInfoProvider);
    final tenantName = tenantAsync.value?.name ?? '';
    final auth = ref.watch(authNotifierProvider).value;
    final storekeeperOnly = auth is AuthAuthenticated &&
        auth.isStorekeeper &&
        !auth.isManager;

    final items = storekeeperOnly
        ? _navItems.where((i) => i.storekeeperVisible).toList()
        : _navItems;
    final routes = items.map((i) => i.route).toList();
    final destinations = items.map((i) => i.destination).toList();

    var selectedIndex =
        routes.indexWhere((r) => currentLocation.startsWith(r));
    if (selectedIndex < 0) selectedIndex = 0;

    return AdaptiveNavShell(
      title: tenantName,
      destinations: destinations,
      selectedIndex: selectedIndex,
      onDestinationSelected: (i) => context.go(routes[i]),
      actions: [
        PopupMenuButton<String>(
          icon: const Icon(Icons.account_circle_outlined),
          tooltip: 'Account',
          onSelected: (v) {
            if (v == 'password') {
              showDialog(
                  context: context, builder: (_) => const ChangePasswordDialog());
            } else if (v == 'logout') {
              ref.read(authNotifierProvider.notifier).logout();
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(
                value: 'password',
                child: Row(children: [
                  Icon(Icons.lock_outline, size: 18),
                  SizedBox(width: 8),
                  Text('Change password'),
                ])),
            PopupMenuItem(
                value: 'logout',
                child: Row(children: [
                  Icon(Icons.logout, size: 18),
                  SizedBox(width: 8),
                  Text('Sign out'),
                ])),
          ],
        ),
      ],
      child: child,
    );
  }
}

/// Lets the signed-in user change their own password (iam-svc).
class ChangePasswordDialog extends ConsumerStatefulWidget {
  const ChangePasswordDialog({super.key});

  @override
  ConsumerState<ChangePasswordDialog> createState() =>
      _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends ConsumerState<ChangePasswordDialog> {
  final _formKey = GlobalKey<FormState>();
  final _currentCtrl = TextEditingController();
  final _newCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _currentCtrl.dispose();
    _newCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.iam}/auth/change-password',
        data: {
          'currentPassword': _currentCtrl.text,
          'newPassword': _newCtrl.text,
        },
      );
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Password changed.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = (status == 401 || status == 400)
            ? 'Current password is incorrect.'
            : friendlyError(e, fallback: 'Could not change password.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Change password'),
      content: SizedBox(
        width: 360,
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
                      borderRadius: BorderRadius.circular(8)),
                  child: Text(_error!,
                      style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _currentCtrl,
                obscureText: true,
                decoration:
                    const InputDecoration(labelText: 'Current password'),
                validator: (v) =>
                    v == null || v.isEmpty ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _newCtrl,
                obscureText: true,
                decoration: const InputDecoration(
                    labelText: 'New password (min 8 chars)'),
                validator: (v) =>
                    v == null || v.length < 8 ? 'At least 8 characters' : null,
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
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white))
              : const Text('Change'),
        ),
      ],
    );
  }
}
