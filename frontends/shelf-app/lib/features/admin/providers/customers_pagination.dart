import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import '../customer_providers.dart';

/// One accumulated view of the paginated customers list.
class CustomersPage {
  final List<Customer> customers;
  final String? nextCursor;
  final bool isLoadingInitial;
  final bool isLoadingMore;
  final Object? error;

  const CustomersPage({
    this.customers = const [],
    this.nextCursor,
    this.isLoadingInitial = true,
    this.isLoadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;

  CustomersPage copyWith({bool? isLoadingMore, Object? error}) => CustomersPage(
        customers: customers,
        nextCursor: nextCursor,
        isLoadingInitial: false,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
        error: error,
      );
}

/// Cursor-paginated customers. Fetches one page at a time from the API's `?after=&limit=`
/// cursor (the backend already returns `data.nextCursor`), accumulating rows for infinite
/// scroll instead of loading only the first 100 with no way to reach the rest.
class CustomersPaginationNotifier extends StateNotifier<CustomersPage> {
  CustomersPaginationNotifier(this._ref) : super(const CustomersPage()) {
    _loadInitial();
  }

  final Ref _ref;
  static const _pageSize = 20;

  Future<void> _loadInitial() async {
    state = const CustomersPage(isLoadingInitial: true);
    try {
      final (customers, next) = await _fetch(null);
      // The provider is `autoDispose`; navigating away mid-fetch disposes this notifier, and
      // writing to `state` afterwards throws.
      if (!mounted) return;
      state = CustomersPage(customers: customers, nextCursor: next, isLoadingInitial: false);
    } catch (e) {
      if (!mounted) return;
      state = CustomersPage(isLoadingInitial: false, error: e);
    }
  }

  Future<void> refresh() => _loadInitial();

  Future<void> loadMore() async {
    if (state.isLoadingInitial || state.isLoadingMore || !state.hasMore) return;
    state = state.copyWith(isLoadingMore: true);
    try {
      final (more, next) = await _fetch(state.nextCursor);
      if (!mounted) return;
      state = CustomersPage(
        customers: [...state.customers, ...more],
        nextCursor: next,
        isLoadingInitial: false,
        isLoadingMore: false,
      );
    } catch (e) {
      if (!mounted) return;
      state = state.copyWith(isLoadingMore: false, error: e);
    }
  }

  Future<(List<Customer>, String?)> _fetch(String? after) async {
    final params = <String, dynamic>{'limit': _pageSize};
    if (after != null) params['after'] = after;
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.customer}/customers',
          queryParameters: params,
        );
    final data = resp.data['data'] as Map<String, dynamic>?;
    final items = (data?['items'] as List?) ?? [];
    final next = data?['nextCursor'] as String?;
    final customers =
        items.map((e) => Customer.fromJson(e as Map<String, dynamic>)).toList();
    return (customers, next);
  }
}

final customersPaginationProvider =
    StateNotifierProvider.autoDispose<CustomersPaginationNotifier, CustomersPage>(
  (ref) => CustomersPaginationNotifier(ref),
);
