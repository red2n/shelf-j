import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class ProductListScreen extends StatelessWidget {
  const ProductListScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
          sliver: SliverToBoxAdapter(
            child: SearchBar(
              hintText: 'Search products…',
              leading: const Icon(Icons.search),
              onSubmitted: (_) {},
            ),
          ),
        ),
        SliverFillRemaining(
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.store_outlined,
                  size: 64,
                  color: Theme.of(context).colorScheme.outlineVariant,
                ),
                const SizedBox(height: 16),
                const Text('Products load from product-svc + pricing-svc.'),
                const SizedBox(height: 8),
                // demo tap to product detail
                TextButton(
                  onPressed: () => context.go('/store/products/demo-001'),
                  child: const Text('View demo product →'),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}
