import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'storefront_providers.dart';
import 'storefront_widgets.dart';
import 'survey_widgets.dart';

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

        // Browse by category
        const SliverToBoxAdapter(child: _CategoryChips()),

        // Products
        productsAsync.when(
          loading: () => const SliverFillRemaining(
            hasScrollBody: false,
            child: LoadingView(label: 'Loading products…'),
          ),
          error: (e, _) => SliverFillRemaining(
            hasScrollBody: false,
            child: ErrorView(
              message: 'Could not load products.\n$e',
              onRetry: () => ref.invalidate(storefrontProductsProvider),
            ),
          ),
          data: (products) {
            if (products.isEmpty) {
              return const SliverFillRemaining(
                hasScrollBody: false,
                child: _EmptyProducts(),
              );
            }
            if (cols == 1) {
              return SliverPadding(
                padding: const EdgeInsets.all(16),
                sliver: SliverList.separated(
                  itemCount: products.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (_, i) => _ProductRow(product: products[i]),
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
                itemCount: products.length,
                itemBuilder: (_, i) => _ProductCard(product: products[i]),
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

  final _controller = PageController(viewportFraction: 0.92);
  int _page = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 4), (_) {
      if (!_controller.hasClients || _offers.length < 2) return;
      final next = (_page + 1) % _offers.length;
      _controller.animateToPage(next,
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
    final promos = ref.watch(storefrontPromotionsProvider).valueOrNull ?? const [];
    _offers = _offersFrom(promos);
    if (_page >= _offers.length) _page = 0;
    return Column(
      children: [
        const SizedBox(height: 12),
        SizedBox(
          height: 150,
          child: PageView.builder(
            controller: _controller,
            itemCount: _offers.length,
            onPageChanged: (i) => setState(() => _page = i),
            itemBuilder: (_, i) {
              final o = _offers[i];
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 6),
                child: Container(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(16),
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
              );
            },
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

/// Horizontal "All + categories" filter chips.
class _CategoryChips extends ConsumerWidget {
  const _CategoryChips();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(storefrontCategoriesProvider);
    final selected = ref.watch(selectedStorefrontCategoryProvider);

    return categoriesAsync.when(
      loading: () => const SizedBox(height: 8),
      error: (_, __) => const SizedBox(height: 8),
      data: (categories) {
        if (categories.isEmpty) return const SizedBox(height: 8);
        return SizedBox(
          height: 48,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            children: [
              _chip(context, ref, label: 'All', value: null, selected: selected == null),
              for (final c in categories)
                _chip(context, ref,
                    label: c.name, value: c.id, selected: selected == c.id),
            ],
          ),
        );
      },
    );
  }

  Widget _chip(BuildContext context, WidgetRef ref,
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
              child: ProductThumb(
                  seed: product.id, label: product.name, fontSize: 36),
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
                child: ProductThumb(
                  seed: product.id,
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
