import 'unit_price.dart';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/theme.dart';
import '../../shared/util/image_budget.dart';
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
          color: _fg[i],
          fontWeight: FontWeight.bold,
          fontSize: fontSize,
        ),
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

/// Slack applied to the decode target so [BoxFit.cover] has pixels to crop.
///
/// The decode preserves aspect ratio, so asking for the box's longest edge alone leaves
/// a landscape photo short on the other axis in a square tile — 4:3 into a square box
/// lands 25% under, and cover then upscales it back, which reads as soft. 1.35 covers
/// 4:3 either way round with a little to spare, and costs ~1.8x the ideal decode against
/// the ~59x it is saving.
const double _coverAspectAllowance = 1.35;

/// Physical-pixel width to decode a product image at, for a box of [constraints] on a
/// screen of [devicePixelRatio].
///
/// Returns null when there is no bounded edge to size against, in which case the caller
/// should decode at native size rather than guess. Clamped to [kProductImageMaxEdge] —
/// the widest we ever store — so a legacy oversized row cannot pull a huge decode back
/// in through the cache.
///
/// Decoding is what dominates image memory on the device: bytes on the wire are capped
/// at [kProductImageMaxBytes], but a 1280x960 photo is ~4.9 MB of RGBA once decoded,
/// regardless of how well it compressed. Flutter's default ImageCache is 100 MB, so
/// roughly twenty full-size product photos would fill it and start thrashing.
int? productImageDecodeWidth(
  BoxConstraints constraints,
  double devicePixelRatio,
) {
  final bounded = <double>[
    if (constraints.hasBoundedWidth) constraints.maxWidth,
    if (constraints.hasBoundedHeight) constraints.maxHeight,
  ].where((edge) => edge.isFinite && edge > 0);
  if (bounded.isEmpty) return null;

  final longestEdge = bounded.reduce(math.max);
  final target = (longestEdge * devicePixelRatio * _coverAspectAllowance).ceil();
  return math.min(math.max(target, 1), kProductImageMaxEdge);
}

/// The product's real image when the owner uploaded one, falling back to the
/// [ProductThumb] colour tile while loading or when there is none.
class ProductImageThumb extends ConsumerWidget {
  final String productId;
  final String label;
  final double fontSize;
  final BorderRadius borderRadius;

  const ProductImageThumb({
    super.key,
    required this.productId,
    required this.label,
    this.fontSize = 28,
    this.borderRadius = BorderRadius.zero,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Decorative wherever it appears: the product's name is always beside it, and the fallback's
    // initials would otherwise be read out as a word (12.11).
    final fallback = ExcludeSemantics(
      child: ProductThumb(
        seed: productId,
        label: label,
        fontSize: fontSize,
        borderRadius: borderRadius,
      ),
    );
    final bytes = ref.watch(productImageProvider(productId)).value;
    if (bytes == null) return fallback;
    // LayoutBuilder rather than a fixed size: this widget is used at 72x72 in the phone
    // list, at whatever the grid card gives it, and at 220-high on the detail screen, so
    // the decode target has to come from the box it actually lands in.
    return ClipRRect(
      borderRadius: borderRadius,
      child: LayoutBuilder(
        builder: (context, constraints) => Image.memory(
          bytes,
          fit: BoxFit.cover,
          excludeFromSemantics: true,
          width: double.infinity,
          height: double.infinity,
          cacheWidth: productImageDecodeWidth(
            constraints,
            MediaQuery.devicePixelRatioOf(context),
          ),
          errorBuilder: (_, _, _) => fallback,
        ),
      ),
    );
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
      error: (_, _) => Text('—', style: TextStyle(color: cs.outline)),
      data: (offer) {
        if (offer == null) {
          return Text('Unpriced', style: TextStyle(color: cs.outline));
        }
        // .select() so this tile only rebuilds when *its own* variant's availability changes,
        // not on every store switch's whole-map refetch.
        final (inStock, hasAvailData) = ref.watch(
          storefrontAvailabilityProvider.select((async) {
            final map = async.value;
            return (
              map == null ? true : (map[offer.variant.id] ?? false),
              map != null,
            );
          }),
        );

        final Widget info = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              '${offer.price.currency} ${offer.price.totalWithVat.toStringAsFixed(2)}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: cs.primary,
                fontWeight: FontWeight.bold,
                fontSize: 15,
              ),
            ),
            WasPriceText(price: offer.price),
            UnitPriceText(price: offer.price),
            if (hasAvailData) StockBadge(inStock: inStock),
          ],
        );

        return Row(
          children: [
            Expanded(child: info),
            _CartControl(
              productName: product.name,
              inStock: inStock,
              line: CartLine(
                variantId: offer.variant.id,
                productName: product.name,
                sku: offer.variant.sku,
                unitPrice: offer.price.totalWithVat,
                currency: offer.price.currency,
              ),
            ),
          ],
        );
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
      error: (_, _) => Text('—', style: TextStyle(color: cs.outline)),
      data: (variant) {
        if (variant == null) {
          return Text('Unavailable', style: TextStyle(color: cs.outline));
        }
        // .select() so this tile only rebuilds when *its own* variant's availability changes,
        // not on every store switch's whole-map refetch.
        final inStock = ref.watch(
          storefrontAvailabilityProvider.select((async) {
            final map = async.value;
            return map == null ? true : (map[variant.id] ?? false);
          }),
        );
        // Catalog mode: no price — the server prices the order (when pricing enforcement is on) or
        // it's a quote.
        return Row(
          children: [
            Expanded(child: StockBadge(inStock: inStock)),
            _CartControl(
              productName: product.name,
              inStock: inStock,
              line: CartLine(
                variantId: variant.id,
                productName: product.name,
                sku: variant.sku,
                unitPrice: 0,
                currency: '',
              ),
            ),
          ],
        );
      },
    );
  }
}

/// A listing card's add-to-cart control, priced or catalogue: nothing while the item is out of
/// stock, an add button, or a quantity stepper once it is in the cart. Watches only its own line of
/// the cart, so a card rebuilds when its own quantity changes and no other.
class _CartControl extends ConsumerWidget {
  final String productName;
  final bool inStock;

  /// What the add button puts in the cart.
  final CartLine line;

  const _CartControl({
    required this.productName,
    required this.inStock,
    required this.line,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!inStock) return const SizedBox.shrink();
    final qty = ref.watch(
      cartProvider.select((cart) {
        for (final l in cart) {
          if (l.variantId == line.variantId) return l.qty;
        }
        return 0;
      }),
    );
    final notifier = ref.read(cartProvider.notifier);
    if (qty == 0) {
      return IconButton.filledTonal(
        visualDensity: VisualDensity.compact,
        tooltip: 'Add to cart',
        icon: const Icon(Icons.add_shopping_cart, size: 18),
        onPressed: () {
          notifier.add(line);
          ScaffoldMessenger.of(context)
            ..clearSnackBars()
            ..showSnackBar(
              SnackBar(
                content: Text('Added $productName'),
                duration: const Duration(milliseconds: 900),
              ),
            );
        },
      );
    }
    return _Stepper(
      qty: qty,
      onDec: () => notifier.setQty(line.variantId, qty - 1),
      onInc: () => notifier.setQty(line.variantId, qty + 1),
    );
  }
}

/// In-stock / out-of-stock pill used when a store hides prices (catalog mode).
class StockBadge extends StatelessWidget {
  final bool inStock;
  const StockBadge({super.key, required this.inStock});

  @override
  Widget build(BuildContext context) {
    final color = inStock
        ? context.status.success
        : Theme.of(context).colorScheme.onSurfaceVariant;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          inStock ? Icons.check_circle : Icons.remove_circle_outline,
          size: 15,
          color: color,
        ),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            inStock ? 'In stock' : 'Out of stock',
            style: TextStyle(
              color: color,
              fontWeight: FontWeight.w600,
              fontSize: 13,
            ),
          ),
        ),
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
          _btn(context, Icons.remove, onDec, 'Remove one'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            // Its own node: a live region merged into the card would re-read the whole card on every step.
            child: Semantics(
              container: true,
              label: '$qty in cart',
              liveRegion: true,
              excludeSemantics: true,
              child: Text(
                '$qty',
                style: TextStyle(
                  color: cs.onPrimaryContainer,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
          _btn(context, Icons.add, onInc, 'Add one'),
        ],
      ),
    );
  }

  // An icon alone names nothing to a screen reader; the label does. 30 px across, past WCAG 2.2's
  // 24 px minimum target (2.5.8).
  Widget _btn(
    BuildContext context,
    IconData icon,
    VoidCallback onTap,
    String label,
  ) {
    final cs = Theme.of(context).colorScheme;
    return Semantics(
      button: true,
      label: label,
      child: InkWell(
        onTap: onTap,
        customBorder: const CircleBorder(),
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: Icon(icon, size: 18, color: cs.onPrimaryContainer),
        ),
      ),
    );
  }
}
