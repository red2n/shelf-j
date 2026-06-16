import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Overview',
    icon: Icons.dashboard_outlined,
    selectedIcon: Icons.dashboard,
  ),
  AdaptiveNavDestination(
    label: 'Tenants',
    icon: Icons.business_outlined,
    selectedIcon: Icons.business,
  ),
];

const _routes = ['/platform/overview', '/platform/tenants'];

class PlatformShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const PlatformShell({super.key, required this.currentLocation, required this.child});

  int get _selectedIndex {
    final idx = _routes.indexWhere((r) => currentLocation.startsWith(r));
    return idx < 0 ? 0 : idx;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdaptiveNavShell(
      title: 'outwhale.com',
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
