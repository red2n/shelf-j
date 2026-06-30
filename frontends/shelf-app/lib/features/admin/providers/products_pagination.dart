import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import 'admin_providers.dart';

/// One accumulated view of the paginated admin products list.
class ProductsPage {
  final List<ProductInfo> products;
  final String? nextCursor;
  final bool isLoadingInitial;
  final bool isLoadingMore;
  final Object? error;

  const ProductsPage({
    this.products = const [],
    this.nextCursor,
    this.isLoadingInitial = true,
    this.isLoadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;

  ProductsPage copyWith({bool? isLoadingMore, Object? error}) => ProductsPage(
        products: products,
        nextCursor: nextCursor,
        isLoadingInitial: false,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
        error: error,
      );
}

/// Cursor-paginated admin products. Fetches one page at a time from the API's `?after=&limit=`
/// cursor (the backend now returns `meta.nextCursor`), accumulating rows for infinite scroll
/// instead of loading only the first 100 with no way to reach the rest of a tenant's catalog.
/// `categoryId` is pushed to the API (`?category=`) rather than filtered in Dart, and is the
/// family key so switching categories starts a fresh page-1 load — free-text search stays a
/// client-side filter over whatever's currently loaded (the backend has no name-search endpoint).
class ProductsPaginationNotifier extends StateNotifier<ProductsPage> {
  ProductsPaginationNotifier(this._ref, this._categoryId) : super(const ProductsPage()) {
    _loadInitial();
  }

  final Ref _ref;
  final String? _categoryId;
  static const _pageSize = 20;

  Future<void> _loadInitial() async {
    state = const ProductsPage(isLoadingInitial: true);
    try {
      final (products, next) = await _fetch(null);
      // The provider is `autoDispose`; navigating away mid-fetch disposes this notifier, and
      // writing to `state` afterwards throws.
      if (!mounted) return;
      state = ProductsPage(products: products, nextCursor: next, isLoadingInitial: false);
    } catch (e) {
      if (!mounted) return;
      state = ProductsPage(isLoadingInitial: false, error: e);
    }
  }

  Future<void> refresh() => _loadInitial();

  Future<void> loadMore() async {
    if (state.isLoadingInitial || state.isLoadingMore || !state.hasMore) return;
    state = state.copyWith(isLoadingMore: true);
    try {
      final (more, next) = await _fetch(state.nextCursor);
      if (!mounted) return;
      state = ProductsPage(
        products: [...state.products, ...more],
        nextCursor: next,
        isLoadingInitial: false,
        isLoadingMore: false,
      );
    } catch (e) {
      if (!mounted) return;
      state = state.copyWith(isLoadingMore: false, error: e);
    }
  }

  Future<(List<ProductInfo>, String?)> _fetch(String? after) async {
    final params = <String, dynamic>{'limit': _pageSize};
    if (_categoryId != null) params['category'] = _categoryId;
    if (after != null) params['after'] = after;
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.product}/admin/products',
          queryParameters: params,
        );
    final data = (resp.data['data'] as List?) ?? [];
    final next = (resp.data['meta'] as Map<String, dynamic>?)?['nextCursor'] as String?;
    final products =
        data.map((e) => ProductInfo.fromJson(e as Map<String, dynamic>)).toList();
    return (products, next);
  }
}

final productsPaginationProvider = StateNotifierProvider.autoDispose
    .family<ProductsPaginationNotifier, ProductsPage, String?>(
  (ref, categoryId) => ProductsPaginationNotifier(ref, categoryId),
);
