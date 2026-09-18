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
  AdaptiveNavDestination(
    label: 'Plans',
    icon: Icons.sell_outlined,
    selectedIcon: Icons.sell,
  ),
  AdaptiveNavDestination(
    label: 'Billing',
    icon: Icons.receipt_long_outlined,
    selectedIcon: Icons.receipt_long,
  ),
  AdaptiveNavDestination(
    label: 'Security incidents',
    icon: Icons.shield_outlined,
    selectedIcon: Icons.shield,
  ),
];

// The order here must match _destinations above, or the wrong tab is highlighted.
const _routes = [
  '/platform/overview',
  '/platform/tenants',
  '/platform/plans',
  '/platform/billing',
  '/platform/security',
];

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
      title: 'storeql.com',
      destinations: _destinations,
      selectedIndex: _selectedIndex,
      onDestinationSelected: (i) => context.go(_routes[i]),
      actions: [
        IconButton(
          key: const Key('platform-security'),
          icon: const Icon(Icons.verified_user_outlined),
          tooltip: 'Sign-in security',
          onPressed: () => context.push('/account/security'),
        ),
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
