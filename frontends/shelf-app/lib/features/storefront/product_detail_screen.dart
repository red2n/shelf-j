import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'storefront_providers.dart';
import 'storefront_widgets.dart';

class ProductDetailScreen extends ConsumerWidget {
  final String productId;

  const ProductDetailScreen({super.key, required this.productId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final productAsync = ref.watch(storefrontProductProvider(productId));
    final variantsAsync = ref.watch(storefrontVariantsProvider(productId));
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/store/products'),
        ),
        title: const Text('Product'),
      ),
      body: productAsync.when(
        loading: () => const LoadingView(label: 'Loading…'),
        error: (e, _) => ErrorView(
          message: friendlyError(e, fallback: 'Could not load product.'),
          onRetry: () => ref.invalidate(storefrontProductProvider(productId)),
        ),
        data: (product) => ListView(
          padding: const EdgeInsets.all(24),
          children: [
            SizedBox(
              height: 220,
              child: ProductImageThumb(
                productId: product.id,
                label: product.name,
                fontSize: 72,
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            const SizedBox(height: 20),
            Text(product.name,
                style: Theme.of(context).textTheme.headlineSmall),
            if (product.description != null) ...[
              const SizedBox(height: 8),
              Text(product.description!,
                  style: TextStyle(color: cs.onSurfaceVariant)),
            ],
            const SizedBox(height: 24),
            Text('Options',
                style: Theme.of(context)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            variantsAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.all(16),
                child: Center(child: CircularProgressIndicator()),
              ),
              error: (e, _) => Text(
                  friendlyError(e, fallback: 'Could not load options.'),
                  style: TextStyle(color: cs.error)),
              data: (variants) {
                if (variants.isEmpty) {
                  return Text('No purchasable options.',
                      style: TextStyle(color: cs.outline));
                }
                return Column(
                  children: variants
                      .map((v) => _VariantRow(product: product, variant: v))
                      .toList(),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _VariantRow extends ConsumerWidget {
  final StoreProduct product;
  final StoreVariant variant;
  const _VariantRow({required this.product, required this.variant});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final showPrices = ref.watch(storefrontShowPricesProvider);
    // .select() so this row only rebuilds when *its own* variant's availability changes,
    // not on every store switch's whole-map refetch.
    final inStock = ref.watch(storefrontAvailabilityProvider.select((async) {
      final map = async.value;
      return map == null ? true : (map[variant.id] ?? false);
    }));

    void addLine(double unitPrice, String currency) {
      ref.read(cartProvider.notifier).add(CartLine(
            variantId: variant.id,
            productName: product.name,
            sku: variant.sku,
            unitPrice: unitPrice,
            currency: currency,
          ));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Added ${product.name} to cart'),
          duration: const Duration(seconds: 1),
        ),
      );
    }

    final subtitle = Text([
      if (variant.unit != null) variant.unit!,
      if (variant.barcode != null) 'EAN: ${variant.barcode}',
    ].join('  ·  '));
    final title =
        Text(variant.sku, style: const TextStyle(fontFamily: 'monospace'));

    // Catalog mode: no price, no price-resolve call — stock + add only.
    if (!showPrices) {
      return Card(
        child: ListTile(
          title: title,
          subtitle: subtitle,
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              StockBadge(inStock: inStock),
              const SizedBox(width: 8),
              if (inStock)
                FilledButton(
                  onPressed: () => addLine(0, ''),
                  child: const Text('Add'),
                ),
            ],
          ),
        ),
      );
    }

    final priceAsync = ref.watch(variantPriceProvider(variant.id));
    return Card(
      child: ListTile(
        title: title,
        subtitle: subtitle,
        trailing: priceAsync.when(
          loading: () => const SizedBox(
              height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)),
          error: (_, _) => Text('Unavailable',
              style: TextStyle(color: cs.outline, fontSize: 12)),
          data: (p) {
            return Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('${p.currency} ${p.totalWithVat.toStringAsFixed(2)}',
                    style:
                        TextStyle(color: cs.primary, fontWeight: FontWeight.bold)),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: () => addLine(p.totalWithVat, p.currency),
                  child: const Text('Add'),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
