import 'package:flutter/material.dart';

class StorefrontCartScreen extends StatelessWidget {
  const StorefrontCartScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.shopping_bag_outlined,
              size: 64,
              color: Theme.of(context).colorScheme.outlineVariant,
            ),
            const SizedBox(height: 16),
            const Text('Cart loads from cart-svc.'),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: FilledButton(
            onPressed: () {
              // TODO: POST to order-svc to place order
            },
            child: const Text('Proceed to Checkout'),
          ),
        ),
      ),
    );
  }
}
