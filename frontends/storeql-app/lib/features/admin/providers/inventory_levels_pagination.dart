import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import 'admin_providers.dart';

/// One accumulated view of the paginated inventory-levels list.
class LevelsPage {
  final List<InventoryLevel> levels;
  final String? nextCursor;
  final bool isLoadingInitial;
  final bool isLoadingMore;
  final Object? error;

  const LevelsPage({
    this.levels = const [],
    this.nextCursor,
    this.isLoadingInitial = true,
    this.isLoadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;

  LevelsPage copyWith({bool? isLoadingMore, Object? error}) => LevelsPage(
        levels: levels,
        nextCursor: nextCursor,
        isLoadingInitial: false,
        isLoadingMore: isLoadingMore ?? this.isLoadingMore,
        error: error,
      );
}

/// Cursor-paginated inventory levels. Fetches one page at a time from the API's
/// `?after=&limit=` cursor (the backend returns `meta.nextCursor`), accumulating
/// rows for load-more instead of pulling the whole levels table at scale. Free-text
/// search / low-stock filtering stays a client-side filter over whatever's loaded.
class InventoryLevelsPaginationNotifier extends StateNotifier<LevelsPage> {
  InventoryLevelsPaginationNotifier(this._ref) : super(const LevelsPage()) {
    _loadInitial();
  }

  final Ref _ref;
  static const _pageSize = 50;

  Future<void> _loadInitial() async {
    state = const LevelsPage(isLoadingInitial: true);
    try {
      final (levels, next) = await _fetch(null);
      // Provider is autoDispose; navigating away mid-fetch disposes this notifier
      // and writing to `state` afterwards throws.
      if (!mounted) return;
      state = LevelsPage(levels: levels, nextCursor: next, isLoadingInitial: false);
    } catch (e) {
      if (!mounted) return;
      state = LevelsPage(isLoadingInitial: false, error: e);
    }
  }

  Future<void> refresh() => _loadInitial();

  Future<void> loadMore() async {
    if (state.isLoadingInitial || state.isLoadingMore || !state.hasMore) return;
    state = state.copyWith(isLoadingMore: true);
    try {
      final (more, next) = await _fetch(state.nextCursor);
      if (!mounted) return;
      state = LevelsPage(
        levels: [...state.levels, ...more],
        nextCursor: next,
        isLoadingInitial: false,
        isLoadingMore: false,
      );
    } catch (e) {
      if (!mounted) return;
      state = state.copyWith(isLoadingMore: false, error: e);
    }
  }

  Future<(List<InventoryLevel>, String?)> _fetch(String? after) async {
    final params = <String, dynamic>{'limit': _pageSize};
    if (after != null) params['after'] = after;
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.inventory}/admin/inventory/levels',
          queryParameters: params,
        );
    final data = (resp.data['data'] as List?) ?? [];
    final next =
        (resp.data['meta'] as Map<String, dynamic>?)?['nextCursor'] as String?;
    final levels =
        data.map((e) => InventoryLevel.fromJson(e as Map<String, dynamic>)).toList();
    return (levels, next);
  }
}

final inventoryLevelsPaginationProvider = StateNotifierProvider.autoDispose<
    InventoryLevelsPaginationNotifier, LevelsPage>(
  (ref) => InventoryLevelsPaginationNotifier(ref),
);

/// Tenant-wide aggregate KPIs (total SKUs + low-stock count) computed server-side,
/// so the dashboard tiles never pull the full levels list.
class InventoryLevelsSummary {
  final int skuCount;
  final int lowStockCount;
  const InventoryLevelsSummary({required this.skuCount, required this.lowStockCount});

  factory InventoryLevelsSummary.fromJson(Map<String, dynamic> j) =>
      InventoryLevelsSummary(
        skuCount: (j['skuCount'] as num?)?.toInt() ?? 0,
        lowStockCount: (j['lowStockCount'] as num?)?.toInt() ?? 0,
      );
}

final inventoryLevelsSummaryProvider =
    FutureProvider.autoDispose<InventoryLevelsSummary>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.inventory}/admin/inventory/levels/summary');
  return InventoryLevelsSummary.fromJson(
      resp.data['data'] as Map<String, dynamic>);
});

/// Variant labels for the variants on the loaded inventory-levels pages — resolves
/// product name + SKU so the table shows names instead of raw variant UUIDs. Re-runs
/// as more pages load; [variantLabelsProvider] caches by id set so it only fetches new ids.
final inventoryVariantLabelsProvider =
    FutureProvider.autoDispose<Map<String, VariantLabel>>((ref) async {
  final levels = ref.watch(inventoryLevelsPaginationProvider).levels;
  return ref.watch(
      variantLabelsProvider(variantIdsKey(levels.map((l) => l.variantId))).future);
});
