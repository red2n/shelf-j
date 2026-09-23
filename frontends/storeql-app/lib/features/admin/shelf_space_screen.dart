import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import '../../shared/widgets/empty_state.dart';

// ---------------------------------------------------------------------------
// Shelf space and range (07.17, 07.18).
//
// Two decisions about a shelf, on one screen because a buyer makes them
// together: how much of the shelf a line gets, and which shops carry the line
// at all.
//
// The gaps tab is the one a shop opens every morning. A reorder level answers
// whether the business will run out, which is the warehouse's question; this
// answers whether the bay looks full, which is the shop floor's — and the two
// differ by exactly the shelf. Twenty units is plenty for a bay holding twelve
// and a gap in one holding sixty.
//
// The range tab shows what is DUE rather than what is set. A range change is
// recorded weeks ahead with a date and a reason, and applying it is a separate
// step, so the useful view is the one that says what has not happened yet.
// ---------------------------------------------------------------------------

class FixtureRow {
  final String id;
  final String code;
  final String name;
  final String kind;
  final int shelfCount;
  final int shelfWidthMm;
  final int totalWidthMm;
  final String status;

  const FixtureRow({
    required this.id,
    required this.code,
    required this.name,
    required this.kind,
    required this.shelfCount,
    required this.shelfWidthMm,
    required this.totalWidthMm,
    required this.status,
  });

  bool get active => status == 'ACTIVE';

  factory FixtureRow.fromJson(Map<String, dynamic> j) => FixtureRow(
        id: j['id'] as String? ?? '',
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        shelfCount: (j['shelfCount'] as num?)?.toInt() ?? 0,
        shelfWidthMm: (j['shelfWidthMm'] as num?)?.toInt() ?? 0,
        totalWidthMm: (j['totalWidthMm'] as num?)?.toInt() ?? 0,
        status: j['status'] as String? ?? 'ACTIVE',
      );
}

class ShelfGapRow {
  final String variantId;
  final int capacity;
  final int minPresentation;
  final String available;
  final String gap;
  final bool belowMinimum;

  const ShelfGapRow({
    required this.variantId,
    required this.capacity,
    required this.minPresentation,
    required this.available,
    required this.gap,
    required this.belowMinimum,
  });

  factory ShelfGapRow.fromJson(Map<String, dynamic> j) => ShelfGapRow(
        variantId: j['variantId'] as String? ?? '',
        capacity: (j['capacity'] as num?)?.toInt() ?? 0,
        minPresentation: (j['minPresentation'] as num?)?.toInt() ?? 0,
        available: j['available'] as String? ?? '0',
        gap: j['gap'] as String? ?? '0',
        belowMinimum: j['belowMinimum'] as bool? ?? false,
      );
}

class RangeChangeRow {
  final String id;
  final String productId;
  final String action;
  final String effectiveFrom;
  final String reason;
  final String? clusterId;
  final String? storeId;

  const RangeChangeRow({
    required this.id,
    required this.productId,
    required this.action,
    required this.effectiveFrom,
    required this.reason,
    this.clusterId,
    this.storeId,
  });

  bool get delisting => action == 'DELIST';

  factory RangeChangeRow.fromJson(Map<String, dynamic> j) => RangeChangeRow(
        id: j['id'] as String? ?? '',
        productId: j['productId'] as String? ?? '',
        action: j['action'] as String? ?? '',
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
        reason: j['reason'] as String? ?? '',
        clusterId: j['clusterId'] as String?,
        storeId: j['storeId'] as String?,
      );
}

const _merch = '/${ApiConstants.product}/admin/merchandising';
const _range = '/${ApiConstants.product}/admin/assortment';
const _gaps = '/${ApiConstants.inventory}/admin/inventory/reports/shelf-gaps';

final shelfSpaceStoreProvider = StateProvider<String?>((_) => null);

final fixturesProvider =
    FutureProvider.autoDispose.family<List<FixtureRow>, String>(
        (ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('$_merch/fixtures', queryParameters: {'store': storeId});
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic>) FixtureRow.fromJson(e),
  ];
});

final shelfGapsProvider =
    FutureProvider.autoDispose.family<List<ShelfGapRow>, String>(
        (ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get(_gaps, queryParameters: {'storeId': storeId, 'limit': 50});
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic>) ShelfGapRow.fromJson(e),
  ];
});

final dueChangesProvider =
    FutureProvider.autoDispose<List<RangeChangeRow>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_range/changes/due');
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic>) RangeChangeRow.fromJson(e),
  ];
});

/// Shelf space and range for one store.
class ShelfSpaceScreen extends ConsumerWidget {
  const ShelfSpaceScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final storesAsync = ref.watch(storesProvider);

    if (storesAsync.hasError) {
      return ErrorView(
        message: friendlyError(storesAsync.error!),
        onRetry: () => ref.invalidate(storesProvider),
      );
    }
    if (!storesAsync.hasValue) {
      return const LoadingView(label: 'Loading stores…');
    }
    final stores = storesAsync.value!;
    if (stores.isEmpty) {
      return const EmptyState(title: 'Add a store before planning its shelves.');
    }
    final chosen = ref.watch(shelfSpaceStoreProvider);
    final storeId = stores.any((s) => s.id == chosen) ? chosen! : stores.first.id;

    return DefaultTabController(
      length: 3,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.xl, AppSpacing.xl, AppSpacing.xl, 0),
            child: Wrap(
              spacing: AppSpacing.lg,
              runSpacing: AppSpacing.sm,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text('Shelf space', style: theme.textTheme.headlineMedium),
                if (stores.length > 1)
                  DropdownButton<String>(
                    key: const Key('shelf-store'),
                    isExpanded: false,
                    value: storeId,
                    items: [
                      for (final s in stores)
                        DropdownMenuItem(value: s.id, child: Text(s.name)),
                    ],
                    onChanged: (v) =>
                        ref.read(shelfSpaceStoreProvider.notifier).state = v,
                  )
                else
                  Text(stores.first.name, style: theme.textTheme.titleMedium),
              ],
            ),
          ),
          const TabBar(
            isScrollable: true,
            tabs: [
              Tab(text: 'Gaps to fill'),
              Tab(text: 'Shelving'),
              Tab(text: 'Range'),
            ],
          ),
          Expanded(
            child: TabBarView(children: [
              _GapsTab(storeId: storeId),
              _FixturesTab(storeId: storeId),
              const _RangeTab(),
            ]),
          ),
        ],
      ),
    );
  }
}

class _GapsTab extends ConsumerWidget {
  final String storeId;
  const _GapsTab({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(shelfGapsProvider(storeId));
    return async.when(
      loading: () => const LoadingView(label: 'Reading the shelves…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not read the shelf gaps.'),
        onRetry: () => ref.invalidate(shelfGapsProvider(storeId)),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return const Padding(
            padding: EdgeInsets.all(AppSpacing.xl),
            child: Text(
              'No shelf plans for this store yet. Until a layout is published, '
              'replenishment is driven from reorder levels alone — which say '
              'whether stock will run out, not whether the bay looks full.',
              key: Key('gaps-empty'),
            ),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(AppSpacing.xl),
          children: [
            Text(
              'What it would take to fill each bay: the shelf\'s capacity against '
              'stock that is not already held for somebody\'s order. A line below '
              'its presentation minimum looks picked over now, whatever the '
              'reorder level says.',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: AppSpacing.lg),
            for (final r in rows)
              Card(
                child: ListTile(
                  key: Key('gap-${r.variantId}'),
                  leading: Icon(
                    r.belowMinimum ? Icons.warning_amber : Icons.shelves,
                    color: r.belowMinimum ? theme.colorScheme.error : null,
                  ),
                  title: Text('${r.gap} to fill  ·  shelf holds ${r.capacity}'),
                  subtitle: Text(
                    '${r.available} available  ·  looks picked over below '
                    '${r.minPresentation}',
                  ),
                  trailing: r.belowMinimum
                      ? Chip(
                          label: const Text('Below minimum'),
                          backgroundColor: theme.colorScheme.errorContainer,
                        )
                      : null,
                ),
              ),
          ],
        );
      },
    );
  }
}

class _FixturesTab extends ConsumerWidget {
  final String storeId;
  const _FixturesTab({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(fixturesProvider(storeId));
    return async.when(
      loading: () => const LoadingView(label: 'Loading shelving…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the shelving.'),
        onRetry: () => ref.invalidate(fixturesProvider(storeId)),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return const Padding(
            padding: EdgeInsets.all(AppSpacing.xl),
            child: Text(
              'No shelving recorded for this store yet.',
              key: Key('fixtures-empty'),
            ),
          );
        }
        return ListView(
          padding: const EdgeInsets.all(AppSpacing.xl),
          children: [
            Text(
              'The furniture a layout is drawn for. Its shelves and their width '
              'are what make a plan checkable: facings times a line\'s width '
              'either fits or does not.',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: AppSpacing.lg),
            for (final f in rows)
              Card(
                child: ListTile(
                  key: Key('fixture-${f.id}'),
                  title: Text('${f.name}  ·  ${f.code}'),
                  subtitle: Text(
                    '${f.kind.toLowerCase().replaceAll('_', ' ')}  ·  '
                    '${f.shelfCount} shelves of ${f.shelfWidthMm}mm  ·  '
                    '${f.totalWidthMm}mm in all',
                  ),
                  trailing: f.active
                      ? null
                      : const Chip(label: Text('Retired')),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _RangeTab extends ConsumerStatefulWidget {
  const _RangeTab();

  @override
  ConsumerState<_RangeTab> createState() => _RangeTabState();
}

class _RangeTabState extends ConsumerState<_RangeTab> {
  bool _applying = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final async = ref.watch(dueChangesProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading range changes…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the range changes.'),
        onRetry: () => ref.invalidate(dueChangesProvider),
      ),
      data: (rows) => ListView(
        padding: const EdgeInsets.all(AppSpacing.xl),
        children: [
          Text(
            'Range decisions that have reached their day and have not been put '
            'into effect yet. Each one says who decided it and why — applying is '
            'a separate step, so a range can be planned weeks ahead.',
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: AppSpacing.lg),
          if (rows.isEmpty)
            const Card(
              key: Key('range-empty'),
              child: Padding(
                padding: EdgeInsets.all(AppSpacing.lg),
                child: Text('Nothing is waiting: every dated change is in force.'),
              ),
            )
          else ...[
            FilledButton.icon(
              key: const Key('range-apply'),
              onPressed: _applying ? null : _apply,
              icon: const Icon(Icons.playlist_add_check),
              label: Text('Apply ${rows.length} due change'
                  '${rows.length == 1 ? '' : 's'}'),
            ),
            const SizedBox(height: AppSpacing.lg),
            for (final c in rows)
              Card(
                child: ListTile(
                  key: Key('change-${c.id}'),
                  leading: Icon(c.delisting ? Icons.remove_circle_outline
                      : Icons.add_circle_outline),
                  title: Text('${c.delisting ? 'De-list' : 'List'}'
                      '  ·  due ${c.effectiveFrom}'),
                  subtitle: Text(c.reason),
                ),
              ),
          ],
        ],
      ),
    );
  }

  Future<void> _apply() async {
    setState(() => _applying = true);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post('$_range/changes/apply', data: const {});
      final applied = (resp.data['data']?['applied'] as num?)?.toInt() ?? 0;
      final notApplied =
          (resp.data['data']?['notApplied'] as List?) ?? const [];
      ref.invalidate(dueChangesProvider);
      if (!mounted) return;
      // The refusals are named, not swallowed: a change that could not be put
      // into effect stays due, so the shop can fix the cause rather than
      // re-enter the decision.
      final first = notApplied.isEmpty
          ? null
          : (notApplied.first as Map<String, dynamic>)['detail'] as String?;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(notApplied.isEmpty
              ? '$applied change${applied == 1 ? '' : 's'} in force.'
              : '$applied in force, ${notApplied.length} could not be '
                  'applied. ${first ?? ''}'),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(friendlyError(e))),
      );
    } finally {
      if (mounted) setState(() => _applying = false);
    }
  }
}
