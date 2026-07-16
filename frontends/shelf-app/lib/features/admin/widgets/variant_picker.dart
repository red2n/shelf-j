import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/api_error.dart';
import '../providers/admin_providers.dart';

/// Cascading product → variant picker, reusing the admin catalog providers.
/// Shared across admin screens (procurement, pricing, …).
class VariantPicker extends ConsumerWidget {
  final String? productId;
  final String? variantId;
  final ValueChanged<String?> onProduct;
  final ValueChanged<String?> onVariant;
  const VariantPicker({
    super.key,
    required this.productId,
    required this.variantId,
    required this.onProduct,
    required this.onVariant,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final productsAsync = ref.watch(productsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        productsAsync.when(
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text(
              friendlyError(e, fallback: 'Could not load products.'),
              style: TextStyle(color: cs.error)),
          data: (products) => DropdownButtonFormField<String>(
            initialValue: productId,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Product *'),
            items: [
              for (final p in products)
                DropdownMenuItem(value: p.id, child: Text(p.name)),
            ],
            onChanged: onProduct,
          ),
        ),
        if (productId != null) ...[
          const SizedBox(height: 12),
          Consumer(
            builder: (context, ref, _) {
              final variantsAsync = ref.watch(productVariantsProvider(productId!));
              return variantsAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load variants.'),
                    style: TextStyle(color: cs.error)),
                data: (variants) => DropdownButtonFormField<String>(
                  initialValue: variantId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Variant *'),
                  items: [
                    for (final v in variants)
                      DropdownMenuItem(value: v.id, child: Text(v.sku)),
                  ],
                  onChanged: onVariant,
                ),
              );
            },
          ),
        ],
      ],
    );
  }
}
