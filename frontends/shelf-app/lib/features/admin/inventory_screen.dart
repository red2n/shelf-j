import 'package:flutter/material.dart';

class InventoryScreen extends StatelessWidget {
  const InventoryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            children: [
              Text('Inventory', style: Theme.of(context).textTheme.headlineMedium),
              const Spacer(),
              FilledButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.add),
                label: const Text('Receive Stock'),
              ),
            ],
          ),
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(24, 16, 24, 0),
          child: SearchBar(hintText: 'Search SKU or product name…'),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.inventory_2_outlined,
                  size: 64,
                  color: Theme.of(context).colorScheme.outlineVariant,
                ),
                const SizedBox(height: 16),
                const Text('Wires to inventory-svc — coming soon.'),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
