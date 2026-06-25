import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'storefront_providers.dart';

/// A lively deterministic product placeholder (colored tile + initials) so the
/// catalog looks alive without real product images. Same product → same colour.
class ProductThumb extends StatelessWidget {
  final String seed; // product id — stable colour per product
  final String label; // product name — drives the initials
  final double fontSize;
  final BorderRadius borderRadius;

  const ProductThumb({
    super.key,
    required this.seed,
    required this.label,
    this.fontSize = 28,
    this.borderRadius = BorderRadius.zero,
  });

  static const _bg = [
    Color(0xFFEAF2F8),
    Color(0xFFE8F8F5),
    Color(0xFFFEF9E7),
    Color(0xFFFDEDEC),
    Color(0xFFF4ECF7),
    Color(0xFFEAFAF1),
    Color(0xFFFBEEE6),
    Color(0xFFEBF5FB),
  ];
  static const _fg = [
    Color(0xFF2E86C1),
    Color(0xFF17A589),
    Color(0xFFB7950B),
    Color(0xFFCB4335),
    Color(0xFF8E44AD),
    Color(0xFF229954),
    Color(0xFFCA6F1E),
    Color(0xFF2874A6),
  ];

  @override
  Widget build(BuildContext context) {
    final h = seed.hashCode.abs();
    final i = h % _bg.length;
    return Container(
      decoration: BoxDecoration(color: _bg[i], borderRadius: borderRadius),
      alignment: Alignment.center,
      child: Text(
        _initials(label),
        style: TextStyle(
            color: _fg[i], fontWeight: FontWeight.bold, fontSize: fontSize),
      ),
    );
  }

  static String _initials(String s) {
    final parts =
        s.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
    if (parts.isEmpty) return '?';
    if (parts.length == 1) {
      final w = parts.first;
      return w.substring(0, w.length >= 2 ? 2 : 1).toUpperCase();
    }
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
}

/// Price + inline add-to-cart for a listing card. Shows the resolved price and
/// either an "add" button or a − qty + stepper once the item is in the cart, so
/// shoppers can build an order without opening every product.
class OfferPriceAdd extends ConsumerWidget {
  final StoreProduct product;
  const OfferPriceAdd({super.key, required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final configAsync = ref.watch(storefrontConfigProvider);

    // While config is still loading, show a neutral placeholder rather than
    // resolving a price or showing add-to-cart — prevents adding items with the
    // wrong price mode before showPrices is known.
    if (configAsync.isLoading) {
      return Text('…', style: TextStyle(color: cs.outline));
    }

    // Catalog mode (store hides prices): never resolve a price — show stock only.
    if (!(configAsync.value?.showPrices ?? false)) {
      return _CatalogAdd(product: product);
    }

    final offerAsync = ref.watch(productCardOfferProvider(product.id));

    return offerAsync.when(
      loading: () => Row(
        children: [
          Text('…', style: TextStyle(color: cs.outline)),
          const Spacer(),
          const SizedBox(
            height: 18,
            width: 18,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ],
      ),
      error: (_, __) => Text('—', style: TextStyle(color: cs.outline)),
      data: (offer) {
        if (offer == null) {
          return Text('Unpriced', style: TextStyle(color: cs.outline));
        }
        final availMap = ref.watch(storefrontAvailabilityProvider).valueOrNull;
        final inStock = availMap == null ? true : (availMap[offer.variant.id] ?? false);

        final cart = ref.watch(cartProvider);
        final notifier = ref.read(cartProvider.notifier);
        int qty = 0;
        for (final l in cart) {
          if (l.variantId == offer.variant.id) {
            qty = l.qty;
            break;
          }
        }

        final Widget info = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '${offer.price.currency} ${offer.price.totalWithVat.toStringAsFixed(2)}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                  color: cs.primary, fontWeight: FontWeight.bold, fontSize: 15),
            ),
            if (availMap != null) StockBadge(inStock: inStock),
          ],
        );

        Widget control;
        if (!inStock) {
          control = const SizedBox.shrink();
        } else if (qty == 0) {
          control = IconButton.filledTonal(
            visualDensity: VisualDensity.compact,
            tooltip: 'Add to cart',
            icon: const Icon(Icons.add_shopping_cart, size: 18),
            onPressed: () {
              notifier.add(CartLine(
                variantId: offer.variant.id,
                productName: product.name,
                sku: offer.variant.sku,
                unitPrice: offer.price.totalWithVat,
                currency: offer.price.currency,
              ));
              ScaffoldMessenger.of(context)
                ..clearSnackBars()
                ..showSnackBar(SnackBar(
                  content: Text('Added ${product.name}'),
                  duration: const Duration(milliseconds: 900),
                ));
            },
          );
        } else {
          control = _Stepper(
            qty: qty,
            onDec: () => notifier.setQty(offer.variant.id, qty - 1),
            onInc: () => notifier.setQty(offer.variant.id, qty + 1),
          );
        }

        return Row(children: [Expanded(child: info), control]);
      },
    );
  }
}

/// Catalog-mode add control: shows only stock status (no price, no price call).
class _CatalogAdd extends ConsumerWidget {
  final StoreProduct product;
  const _CatalogAdd({required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final variantAsync = ref.watch(productFirstVariantProvider(product.id));
    return variantAsync.when(
      loading: () => Text('…', style: TextStyle(color: cs.outline)),
      error: (_, __) => Text('—', style: TextStyle(color: cs.outline)),
      data: (variant) {
        if (variant == null) {
          return Text('Unavailable', style: TextStyle(color: cs.outline));
        }
        final availMap = ref.watch(storefrontAvailabilityProvider).valueOrNull;
        final inStock = availMap == null ? true : (availMap[variant.id] ?? false);
        final cart = ref.watch(cartProvider);
        final notifier = ref.read(cartProvider.notifier);
        int qty = 0;
        for (final l in cart) {
          if (l.variantId == variant.id) {
            qty = l.qty;
            break;
          }
        }

        Widget control;
        if (!inStock) {
          control = const SizedBox.shrink();
        } else if (qty == 0) {
          control = IconButton.filledTonal(
            visualDensity: VisualDensity.compact,
            tooltip: 'Add to cart',
            icon: const Icon(Icons.add_shopping_cart, size: 18),
            onPressed: () {
              // Catalog mode: no price — the server prices the order (when
              // pricing enforcement is on) or it's a quote.
              notifier.add(CartLine(
                variantId: variant.id,
                productName: product.name,
                sku: variant.sku,
                unitPrice: 0,
                currency: '',
              ));
              ScaffoldMessenger.of(context)
                ..clearSnackBars()
                ..showSnackBar(SnackBar(
                  content: Text('Added ${product.name}'),
                  duration: const Duration(milliseconds: 900),
                ));
            },
          );
        } else {
          control = _Stepper(
            qty: qty,
            onDec: () => notifier.setQty(variant.id, qty - 1),
            onInc: () => notifier.setQty(variant.id, qty + 1),
          );
        }
        return Row(
            children: [Expanded(child: StockBadge(inStock: inStock)), control]);
      },
    );
  }
}

/// In-stock / out-of-stock pill used when a store hides prices (catalog mode).
class StockBadge extends StatelessWidget {
  final bool inStock;
  const StockBadge({super.key, required this.inStock});

  @override
  Widget build(BuildContext context) {
    final color = inStock ? Colors.green : Colors.grey;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(inStock ? Icons.check_circle : Icons.remove_circle_outline,
            size: 15, color: color),
        const SizedBox(width: 4),
        Text(inStock ? 'In stock' : 'Out of stock',
            style: TextStyle(
                color: color.shade700, fontWeight: FontWeight.w600, fontSize: 13)),
      ],
    );
  }
}

class _Stepper extends StatelessWidget {
  final int qty;
  final VoidCallback onDec;
  final VoidCallback onInc;
  const _Stepper({required this.qty, required this.onDec, required this.onInc});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: cs.primaryContainer,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _btn(context, Icons.remove, onDec),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text('$qty',
                style: TextStyle(
                    color: cs.onPrimaryContainer, fontWeight: FontWeight.bold)),
          ),
          _btn(context, Icons.add, onInc),
        ],
      ),
    );
  }

  Widget _btn(BuildContext context, IconData icon, VoidCallback onTap) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      customBorder: const CircleBorder(),
      child: Padding(
        padding: const EdgeInsets.all(6),
        child: Icon(icon, size: 18, color: cs.onPrimaryContainer),
      ),
    );
  }
}
