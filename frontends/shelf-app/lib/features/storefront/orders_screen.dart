import 'package:flutter/material.dart';

class StorefrontOrdersScreen extends StatelessWidget {
  const StorefrontOrdersScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.list_alt_outlined,
            size: 64,
            color: Theme.of(context).colorScheme.outlineVariant,
          ),
          const SizedBox(height: 16),
          const Text('Your orders load from order-svc.'),
        ],
      ),
    );
  }
}
