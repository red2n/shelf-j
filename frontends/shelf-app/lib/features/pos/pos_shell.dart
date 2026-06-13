import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/theme.dart';

class PosShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const PosShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final onTender = currentLocation.startsWith('/pos/tender');

    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppTheme.posAccent,
        foregroundColor: Colors.white,
        title: const Row(
          children: [
            Icon(Icons.point_of_sale),
            SizedBox(width: 8),
            Text('POS Terminal'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Sign out',
            onPressed: () => ref.read(authNotifierProvider.notifier).logout(),
          ),
        ],
      ),
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: onTender ? 1 : 0,
        onDestinationSelected: (i) =>
            context.go(i == 0 ? '/pos/cart' : '/pos/tender'),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.shopping_cart_outlined),
            selectedIcon: Icon(Icons.shopping_cart),
            label: 'Sale',
          ),
          NavigationDestination(
            icon: Icon(Icons.payments_outlined),
            selectedIcon: Icon(Icons.payments),
            label: 'Tender',
          ),
        ],
      ),
    );
  }
}
