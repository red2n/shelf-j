import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'storefront_providers.dart';
import 'storefront_widgets.dart';
import 'survey_widgets.dart';

// Sentinel placed in the mixed display list to mark where the ad renders.
class _AdSlot {
  const _AdSlot();
}

class ProductListScreen extends ConsumerStatefulWidget {
  const ProductListScreen({super.key});

  @override
  ConsumerState<ProductListScreen> createState() => _ProductListScreenState();
}

class _ProductListScreenState extends ConsumerState<ProductListScreen> {
  String _query = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      // Auto-select the first store when no ?store= URL override is given.
      // This prevents a stale built-in default from making the availability
      // endpoint return an empty map (which would show everything as out of stock).
      if ((Uri.base.queryParameters['store'] ?? '').isEmpty) {
        try {
          final stores = await ref.read(storefrontStoresProvider.future);
          if (stores.isNotEmpty && mounted) {
            ref.read(storefrontStoreProvider.notifier).state = stores.first.id;
          }
        } catch (_) {}
      }
      await Future.delayed(const Duration(milliseconds: 500));
      if (!mounted) return;
      final asked = ref.read(customerPrefsProvider).genderAsked;
      if (!asked) showGenderPickerSheet(context);
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(storefrontTenantProvider);
    if (tenant == null) return const _NoStorefront();

    final selectedCategory = ref.watch(selectedStorefrontCategoryProvider);
    final inStockOnly = ref.watch(storefrontInStockOnlyProvider);
    final productsAsync = ref.watch(
        storefrontProductsProvider((query: _query, categoryId: selectedCategory)));
    final width = MediaQuery.sizeOf(context).width;
    final cols = width < 600
        ? 1
        : width < 900
            ? 2
            : width < 1280
                ? 3
                : (width ~/ 320);

    return CustomScrollView(
      slivers: [
        // Store switcher (only shown when the tenant has more than one store)
        const SliverToBoxAdapter(child: _StoreSwitcher()),

        // Offers hero carousel
        const SliverToBoxAdapter(child: _OffersCarousel()),

        // Search
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: SearchBar(
              hintText: 'Search products…',
              leading: const Icon(Icons.search),
              onSubmitted: (v) => setState(() => _query = v),
            ),
          ),
        ),

        // Browse by category + in-stock filter
        SliverToBoxAdapter(
          child: _FilterRow(inStockOnly: inStockOnly),
        ),

        // Products
        productsAsync.when(
          loading: () => const SliverFillRemaining(
            hasScrollBody: false,
            child: LoadingView(label: 'Loading products…'),
          ),
          error: (e, _) => SliverFillRemaining(
            hasScrollBody: false,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load products.'),
              onRetry: () => ref.invalidate(storefrontProductsProvider),
            ),
          ),
          data: (products) {
            // Apply in-stock filter client-side using the availability map and
            // first-variant lookup (both are lazy-cached per product).
            List<StoreProduct> displayProducts = products;
            if (inStockOnly) {
              final availMap =
                  ref.watch(storefrontAvailabilityProvider).value ?? {};
              if (availMap.isNotEmpty) {
                displayProducts = products.where((p) {
                  final variant =
                      ref.watch(productFirstVariantProvider(p.id)).value;
                  if (variant == null) return true; // include while loading
                  return availMap[variant.id] ?? true;
                }).toList();
              }
            }

            if (displayProducts.isEmpty) {
              return const SliverFillRemaining(
                hasScrollBody: false,
                child: _EmptyProducts(),
              );
            }
            // Inject one ad at a random position among the real products.
            final adIndex = math.Random().nextInt(displayProducts.length + 1);
            final items = <Object>[...displayProducts]
              ..insert(adIndex, const _AdSlot());

            if (cols == 1) {
              return SliverPadding(
                padding: const EdgeInsets.all(16),
                sliver: SliverList.separated(
                  itemCount: items.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 12),
                  itemBuilder: (_, i) {
                    final item = items[i];
                    return item is _AdSlot
                        ? const _AdRow()
                        : _ProductRow(product: item as StoreProduct);
                  },
                ),
              );
            }
            return SliverPadding(
              padding: const EdgeInsets.all(16),
              sliver: SliverGrid.builder(
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: cols,
                  crossAxisSpacing: 12,
                  mainAxisSpacing: 12,
                  childAspectRatio: 0.78,
                ),
                itemCount: items.length,
                itemBuilder: (_, i) {
                  final item = items[i];
                  return item is _AdSlot
                      ? const _AdCard()
                      : _ProductCard(product: item as StoreProduct);
                },
              ),
            );
          },
        ),
      ],
    );
  }
}

/// Lets the shopper pick which store they're browsing (dev stand-in for per-store
/// subdomains). Hidden when the tenant has a single store. Switching re-filters the
/// catalog to that store's assortment and re-reads its price/availability config.
class _StoreSwitcher extends ConsumerWidget {
  const _StoreSwitcher();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storefrontStoresProvider);
    final current = ref.watch(storefrontStoreProvider);

    return storesAsync.maybeWhen(
      orElse: () => const SizedBox.shrink(),
      data: (stores) {
        if (stores.length < 2) return const SizedBox.shrink();
        final value = stores.any((s) => s.id == current) ? current : stores.first.id;
        return Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(
            children: [
              Icon(Icons.storefront_outlined, size: 18, color: cs.primary),
              const SizedBox(width: 8),
              Text('Shopping at', style: TextStyle(color: cs.outline)),
              const SizedBox(width: 10),
              Expanded(
                child: DropdownButton<String>(
                  isExpanded: true,
                  value: value,
                  underline: const SizedBox.shrink(),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(
                        value: s.id,
                        child: Text(s.name,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontWeight: FontWeight.w600)),
                      ),
                  ],
                  onChanged: (v) {
                    if (v != null) {
                      ref.read(storefrontStoreProvider.notifier).state = v;
                    }
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// Auto-rotating promotional banners. Driven by the tenant's active promotions
/// (pricing-svc); falls back to evergreen content when there are no live offers.
class _OffersCarousel extends ConsumerStatefulWidget {
  const _OffersCarousel();

  @override
  ConsumerState<_OffersCarousel> createState() => _OffersCarouselState();
}

class _Offer {
  final String title;
  final String subtitle;
  final IconData icon;
  final List<Color> colors;
  const _Offer(this.title, this.subtitle, this.icon, this.colors);
}

class _OffersCarouselState extends ConsumerState<_OffersCarousel> {
  // Evergreen content shown when the tenant has no live promotions configured.
  static const _fallbackOffers = [
    _Offer('Everyday Low Prices', 'Stock up and save on the essentials',
        Icons.local_offer_outlined, [Color(0xFF1A5276), Color(0xFF2E86C1)]),
    _Offer('Free Delivery over £25', 'On all online orders, no code needed',
        Icons.local_shipping_outlined, [Color(0xFF117A65), Color(0xFF45B39D)]),
    _Offer('Fresh New Arrivals', 'Just landed in store — shop the latest',
        Icons.auto_awesome_outlined, [Color(0xFF7D3C98), Color(0xFFAF7AC5)]),
  ];

  // Rotating palette for live promotions so each banner reads distinctly.
  static const _palette = [
    [Color(0xFFB9770E), Color(0xFFE67E22)],
    [Color(0xFF1A5276), Color(0xFF2E86C1)],
    [Color(0xFF117A65), Color(0xFF45B39D)],
    [Color(0xFF7D3C98), Color(0xFFAF7AC5)],
  ];

  // Current offers shown; updated each build from the promotions provider so the
  // rotation timer always reads a valid length.
  List<_Offer> _offers = _fallbackOffers;

  final _controller = CarouselController();
  int _page = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 4), (_) {
      if (!_controller.hasClients || _offers.length < 2) return;
      final next = (_page + 1) % _offers.length;
      _controller.animateToItem(next,
          duration: const Duration(milliseconds: 450), curve: Curves.easeInOut);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  List<_Offer> _offersFrom(List<StorePromotion> promos) {
    if (promos.isEmpty) return _fallbackOffers;
    return [
      for (var i = 0; i < promos.length; i++)
        _Offer(
          promos[i].headline,
          promos[i].minOrderAmount != null && promos[i].minOrderAmount! > 0
              ? '${promos[i].name} · spend ${promos[i].minOrderAmount!.toStringAsFixed(2)}+'
              : promos[i].name,
          Icons.local_offer_outlined,
          _palette[i % _palette.length],
        ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final promos = ref.watch(storefrontPromotionsProvider).value ?? const [];
    _offers = _offersFrom(promos);
    if (_page >= _offers.length) _page = 0;
    return Column(
      children: [
        const SizedBox(height: 12),
        SizedBox(
          height: 150,
          child: LayoutBuilder(
            builder: (context, constraints) => CarouselView(
              controller: _controller,
              itemExtent: constraints.maxWidth * 0.92,
              itemSnapping: true,
              enableSplash: false,
              padding: const EdgeInsets.symmetric(horizontal: 6),
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16)),
              onIndexChanged: (i) => setState(() => _page = i),
              children: [
                for (final o in _offers)
                  Container(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: o.colors,
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                    ),
                    padding: const EdgeInsets.all(20),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(o.title,
                                  style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 20,
                                      fontWeight: FontWeight.bold)),
                              const SizedBox(height: 6),
                              Text(o.subtitle,
                                  style: TextStyle(
                                      color: Colors.white.withAlpha(220),
                                      fontSize: 13)),
                            ],
                          ),
                        ),
                        Icon(o.icon, color: Colors.white.withAlpha(220), size: 48),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 10),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: List.generate(_offers.length, (i) {
            final active = i == _page;
            return AnimatedContainer(
              duration: const Duration(milliseconds: 250),
              margin: const EdgeInsets.symmetric(horizontal: 3),
              width: active ? 18 : 6,
              height: 6,
              decoration: BoxDecoration(
                color: active
                    ? Theme.of(context).colorScheme.primary
                    : Theme.of(context).colorScheme.outlineVariant,
                borderRadius: BorderRadius.circular(3),
              ),
            );
          }),
        ),
      ],
    );
  }
}

/// Horizontal filter row: category chips + "In stock only" toggle at the end.
class _FilterRow extends ConsumerWidget {
  final bool inStockOnly;
  const _FilterRow({required this.inStockOnly});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(storefrontCategoriesProvider);
    final selected = ref.watch(selectedStorefrontCategoryProvider);
    final cs = Theme.of(context).colorScheme;

    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        children: [
          // Category chips
          ...categoriesAsync.when(
            loading: () => const [],
            error: (_, _) => const [],
            data: (categories) => [
              _categoryChip(context, ref,
                  label: 'All', value: null, selected: selected == null),
              for (final c in categories)
                _categoryChip(context, ref,
                    label: c.name, value: c.id, selected: selected == c.id),
            ],
          ),
          // Divider spacer
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 10),
            child: VerticalDivider(width: 1, color: cs.outlineVariant),
          ),
          // In-stock toggle
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilterChip(
              avatar: Icon(
                Icons.inventory_2_outlined,
                size: 16,
                color: inStockOnly ? cs.onSecondaryContainer : cs.onSurfaceVariant,
              ),
              label: const Text('In stock'),
              selected: inStockOnly,
              onSelected: (v) =>
                  ref.read(storefrontInStockOnlyProvider.notifier).state = v,
            ),
          ),
        ],
      ),
    );
  }

  Widget _categoryChip(BuildContext context, WidgetRef ref,
      {required String label, required String? value, required bool selected}) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) =>
            ref.read(selectedStorefrontCategoryProvider.notifier).state = value,
      ),
    );
  }
}

class _EmptyProducts extends ConsumerWidget {
  const _EmptyProducts();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final filtered = ref.watch(selectedStorefrontCategoryProvider) != null;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.storefront_outlined, size: 64, color: cs.outlineVariant),
          const SizedBox(height: 16),
          Text(filtered
              ? 'No products in this category'
              : 'No products available yet'),
        ],
      ),
    );
  }
}

class _ProductCard extends ConsumerWidget {
  final StoreProduct product;
  const _ProductCard({required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => context.go('/store/products/${product.id}'),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: ProductImageThumb(
                  productId: product.id, label: product.name, fontSize: 36),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 6, 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(product.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  OfferPriceAdd(product: product),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Compact horizontal-thumbnail card used for the single-column phone list, so
/// stacked products read as a tidy vertical list instead of giant full-width tiles.
class _ProductRow extends ConsumerWidget {
  final StoreProduct product;
  const _ProductRow({required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      clipBehavior: Clip.antiAlias,
      margin: EdgeInsets.zero,
      child: InkWell(
        onTap: () => context.go('/store/products/${product.id}'),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            children: [
              SizedBox(
                width: 72,
                height: 72,
                child: ProductImageThumb(
                  productId: product.id,
                  label: product.name,
                  fontSize: 24,
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(product.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontWeight: FontWeight.w600, fontSize: 15)),
                    const SizedBox(height: 4),
                    OfferPriceAdd(product: product),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Sponsored ad widgets ──────────────────────────────────────────────────────

const _kAdUrl = 'https://storeql.com';
const _kAdGradient = LinearGradient(
  colors: [Color(0xFF1A237E), Color(0xFF3F51B5)],
  begin: Alignment.topLeft,
  end: Alignment.bottomRight,
);

Future<void> _openAd() =>
    launchUrl(Uri.parse(_kAdUrl), mode: LaunchMode.externalApplication);

/// Grid-card variant of the StoreQL ad tile.
class _AdCard extends StatelessWidget {
  const _AdCard();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: _openAd,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                children: [
                  Container(
                    decoration: const BoxDecoration(gradient: _kAdGradient),
                    alignment: Alignment.center,
                    child: Icon(Icons.inventory_2_outlined,
                        size: 48, color: Colors.white.withAlpha(180)),
                  ),
                  Positioned(
                    top: 8,
                    right: 8,
                    child: _AdBadge(cs: cs),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 6, 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('StoreQL',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  Text('Smart stock & storefront platform',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 11, color: cs.outline)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// List-row variant of the StoreQL ad tile.
class _AdRow extends StatelessWidget {
  const _AdRow();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      margin: EdgeInsets.zero,
      child: InkWell(
        onTap: _openAd,
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Row(
            children: [
              SizedBox(
                width: 72,
                height: 72,
                child: Container(
                  decoration: BoxDecoration(
                    gradient: _kAdGradient,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  alignment: Alignment.center,
                  child: const Icon(Icons.inventory_2_outlined,
                      size: 32, color: Colors.white),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Expanded(
                          child: Text('StoreQL',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                  fontWeight: FontWeight.w600, fontSize: 15)),
                        ),
                        _AdBadge(cs: cs),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text('Smart stock & storefront platform',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 12, color: cs.outline)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AdBadge extends StatelessWidget {
  const _AdBadge({required this.cs});
  final ColorScheme cs;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: cs.secondaryContainer,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text('AD',
          style: TextStyle(
              color: cs.onSecondaryContainer,
              fontSize: 9,
              fontWeight: FontWeight.bold,
              letterSpacing: 0.5)),
    );
  }
}

class _NoStorefront extends StatelessWidget {
  const _NoStorefront();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.store_mall_directory_outlined,
                size: 64, color: cs.outlineVariant),
            const SizedBox(height: 16),
            Text('No store selected',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(
              'Open this storefront with a tenant in the URL, e.g.\n'
              '/?tenant=<tenantId>#/store/products',
              textAlign: TextAlign.center,
              style: TextStyle(color: cs.outline),
            ),
          ],
        ),
      ),
    );
  }
}
