import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/theme.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Sale',
    icon: Icons.shopping_cart_outlined,
    selectedIcon: Icons.shopping_cart,
  ),
  AdaptiveNavDestination(
    label: 'Tender',
    icon: Icons.payments_outlined,
    selectedIcon: Icons.payments,
  ),
  AdaptiveNavDestination(
    label: 'Cash',
    icon: Icons.account_balance_wallet_outlined,
    selectedIcon: Icons.account_balance_wallet,
  ),
];

const _routes = ['/pos/cart', '/pos/tender', '/pos/cash'];

class PosShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const PosShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  int get _selectedIndex => currentLocation.startsWith('/pos/cash')
      ? 2
      : currentLocation.startsWith('/pos/tender')
          ? 1
          : 0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return AdaptiveNavShell(
      title: 'POS Terminal',
      leadingIcon: Icons.point_of_sale,
      appBarBackgroundColor: AppTheme.posAccent,
      appBarForegroundColor: Colors.white,
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
