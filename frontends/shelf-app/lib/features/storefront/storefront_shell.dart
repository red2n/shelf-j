import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Shop',
    icon: Icons.store_outlined,
    selectedIcon: Icons.store,
  ),
  AdaptiveNavDestination(
    label: 'Cart',
    icon: Icons.shopping_bag_outlined,
    selectedIcon: Icons.shopping_bag,
  ),
  AdaptiveNavDestination(
    label: 'My Orders',
    icon: Icons.list_alt_outlined,
    selectedIcon: Icons.list_alt,
  ),
];

const _routes = [
  '/store/products',
  '/store/cart',
  '/store/orders',
];

class StorefrontShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const StorefrontShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  int get _selectedIndex {
    if (currentLocation.startsWith('/store/cart')) return 1;
    if (currentLocation.startsWith('/store/orders')) return 2;
    return 0;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdaptiveNavShell(
      title: 'Shelf-J Shop',
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
