import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'providers/admin_providers.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Dashboard',
    icon: Icons.dashboard_outlined,
    selectedIcon: Icons.dashboard,
  ),
  AdaptiveNavDestination(
    label: 'Catalog',
    icon: Icons.inventory_2_outlined,
    selectedIcon: Icons.inventory_2,
  ),
  AdaptiveNavDestination(
    label: 'Inventory',
    icon: Icons.warehouse_outlined,
    selectedIcon: Icons.warehouse,
  ),
  AdaptiveNavDestination(
    label: 'Stores',
    icon: Icons.store_outlined,
    selectedIcon: Icons.store,
  ),
  AdaptiveNavDestination(
    label: 'Orders',
    icon: Icons.receipt_long_outlined,
    selectedIcon: Icons.receipt_long,
  ),
  AdaptiveNavDestination(
    label: 'Procurement',
    icon: Icons.local_shipping_outlined,
    selectedIcon: Icons.local_shipping,
  ),
  AdaptiveNavDestination(
    label: 'Pricing',
    icon: Icons.sell_outlined,
    selectedIcon: Icons.sell,
  ),
  AdaptiveNavDestination(
    label: 'Reports',
    icon: Icons.bar_chart_outlined,
    selectedIcon: Icons.bar_chart,
  ),
  AdaptiveNavDestination(
    label: 'Customers',
    icon: Icons.groups_outlined,
    selectedIcon: Icons.groups,
  ),
  AdaptiveNavDestination(
    label: 'Sales',
    icon: Icons.card_giftcard_outlined,
    selectedIcon: Icons.card_giftcard,
  ),
  AdaptiveNavDestination(
    label: 'Staff',
    icon: Icons.people_outline,
    selectedIcon: Icons.people,
  ),
];

const _routes = [
  '/admin/dashboard',
  '/admin/catalog',
  '/admin/inventory',
  '/admin/stores',
  '/admin/orders',
  '/admin/procurement',
  '/admin/pricing',
  '/admin/reports',
  '/admin/customers',
  '/admin/sales',
  '/admin/staff',
];

class AdminShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const AdminShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  int get _selectedIndex {
    final idx = _routes.indexWhere((r) => currentLocation.startsWith(r));
    return idx < 0 ? 0 : idx;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenantAsync = ref.watch(tenantInfoProvider);
    final tenantName = tenantAsync.valueOrNull?.name ?? '';

    return AdaptiveNavShell(
      title: tenantName,
      destinations: _destinations,
      selectedIndex: _selectedIndex,
      onDestinationSelected: (i) => context.go(_routes[i]),
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
