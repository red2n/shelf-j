import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Dashboard',
    icon: Icons.dashboard_outlined,
    selectedIcon: Icons.dashboard,
  ),
  AdaptiveNavDestination(
    label: 'Inventory',
    icon: Icons.inventory_2_outlined,
    selectedIcon: Icons.inventory_2,
  ),
  AdaptiveNavDestination(
    label: 'Orders',
    icon: Icons.receipt_long_outlined,
    selectedIcon: Icons.receipt_long,
  ),
  AdaptiveNavDestination(
    label: 'Reports',
    icon: Icons.bar_chart_outlined,
    selectedIcon: Icons.bar_chart,
  ),
  AdaptiveNavDestination(
    label: 'Staff',
    icon: Icons.people_outline,
    selectedIcon: Icons.people,
  ),
];

const _routes = [
  '/admin/dashboard',
  '/admin/inventory',
  '/admin/orders',
  '/admin/reports',
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
    return AdaptiveNavShell(
      title: 'Admin',
      destinations: _destinations,
      selectedIndex: _selectedIndex,
      onDestinationSelected: (i) => context.go(_routes[i]),
      actions: [
        IconButton(
          icon: const Icon(Icons.logout),
          tooltip: 'Sign out',
          onPressed: () => ref.read(authNotifierProvider.notifier).logout(),
        ),
      ],
      child: child,
    );
  }
}
