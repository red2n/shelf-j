import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import 'admin_providers.dart';

/// Server-side filter for the orders list. Both fields are pushed to the API
/// (`?channel=&status=`) rather than filtered in Dart, and the pair is the
/// family key so switching either filter starts a fresh page-1 load.
class OrdersFilter {
  final String channel; // ALL | ONLINE | POS
  final String status; // ALL | PENDING | CONFIRMED | FULFILLED | CANCELLED
  const OrdersFilter(this.channel, this.status);

  @override
  bool operator ==(Object other) =>
      other is OrdersFilter && other.channel == channel && other.status == status;

  @override
  int get hashCode => Object.hash(channel, status);
}

/// One accumulated view of the paginated orders list.
class OrdersPage {
  final List<OrderSummary> orders;
  final String? nextCursor;
  final bool isLoadingInitial;
  final bool isLoadingMore;
  final Object? error;

  const OrdersPage({
    this.orders = const [],
    this.nextCursor,
    this.isLoadingInitial = true,
    this.isLoadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;

  OrdersPage copyWith({bool? isLoadingMore, Object? error}) => OrdersPage(
        orders: orders,
        nextCursor: nextCursor,
        isLoadingInitial: false,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
        error: error,
      );
}

/// Cursor-paginated orders. Fetches one page at a time from the API's
/// `?after=&limit=` cursor (the backend already returns `meta.nextCursor`),
/// accumulating rows for infinite scroll instead of loading the whole table.
class OrdersPaginationNotifier extends StateNotifier<OrdersPage> {
  OrdersPaginationNotifier(this._ref, this._filter) : super(const OrdersPage()) {
    _loadInitial();
  }

  final Ref _ref;
  final OrdersFilter _filter;
  static const _pageSize = 20;

  Future<void> _loadInitial() async {
    state = const OrdersPage(isLoadingInitial: true);
    try {
      final (orders, next) = await _fetch(null);
      // The provider is `autoDispose`; navigating away from the orders screen mid-fetch disposes
      // this notifier, and writing to `state` afterwards throws.
      if (!mounted) return;
      state = OrdersPage(orders: orders, nextCursor: next, isLoadingInitial: false);
    } catch (e) {
      if (!mounted) return;
      state = OrdersPage(isLoadingInitial: false, error: e);
    }
  }

  Future<void> refresh() => _loadInitial();

  Future<void> loadMore() async {
    if (state.isLoadingInitial || state.isLoadingMore || !state.hasMore) return;
    state = state.copyWith(isLoadingMore: true);
    try {
      final (more, next) = await _fetch(state.nextCursor);
      if (!mounted) return;
      state = OrdersPage(
        orders: [...state.orders, ...more],
        nextCursor: next,
        isLoadingInitial: false,
        isLoadingMore: false,
      );
    } catch (e) {
      if (!mounted) return;
      state = state.copyWith(isLoadingMore: false, error: e);
    }
  }

  Future<(List<OrderSummary>, String?)> _fetch(String? after) async {
    final params = <String, dynamic>{'limit': _pageSize};
    if (_filter.channel != 'ALL') params['channel'] = _filter.channel;
    if (_filter.status != 'ALL') params['status'] = _filter.status;
    if (after != null) params['after'] = after;
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.order}/orders',
          queryParameters: params,
        );
    final data = (resp.data['data'] as List?) ?? [];
    final next = (resp.data['meta'] as Map<String, dynamic>?)?['nextCursor'] as String?;
    final orders =
        data.map((e) => OrderSummary.fromJson(e as Map<String, dynamic>)).toList();
    return (orders, next);
  }
}

final ordersPaginationProvider = StateNotifierProvider.autoDispose
    .family<OrdersPaginationNotifier, OrdersPage, OrdersFilter>(
  (ref, filter) => OrdersPaginationNotifier(ref, filter),
);
