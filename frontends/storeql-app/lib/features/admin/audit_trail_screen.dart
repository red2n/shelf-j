import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:intl/intl.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/file_download.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

// The business audit trail (20.11): who discounted, voided, opened the drawer,
// cancelled, took goods back or wrote stock off — one timeline across the two
// services that record those actions, newest first, naming the member of
// staff. Read-only by design: every row comes from an append-only log and
// nothing on this screen can change one.

/// The event types the trail shows, with the words used for them.
const auditTypeLabels = <String, String>{
  'DISCOUNT': 'Discount',
  'VOID': 'Void',
  'NO_SALE': 'No sale',
  'CANCEL': 'Cancel',
  'RETURN': 'Return',
  'STOCK_ADJUSTMENT': 'Stock adjustment',
};

/// One event on the trail, from either source.
class AuditEvent {
  final String id;
  final String type;
  final DateTime? occurredAt;
  final String? actorId;
  final String storeId;
  final String? orderId;
  final double? amount;
  final String? reason;
  final String? detail;

  /// Stock adjustments only: the signed quantity and what was adjusted.
  final double? qty;
  final String? variantId;

  const AuditEvent({
    required this.id,
    required this.type,
    this.occurredAt,
    this.actorId,
    required this.storeId,
    this.orderId,
    this.amount,
    this.reason,
    this.detail,
    this.qty,
    this.variantId,
  });

  bool get fromStock => type == 'STOCK_ADJUSTMENT';

  /// An event from order-svc's trail.
  factory AuditEvent.fromSales(Map<String, dynamic> j) => AuditEvent(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? '',
        occurredAt: DateTime.tryParse(j['occurredAt'] as String? ?? ''),
        actorId: j['actorId'] as String?,
        storeId: j['storeId'] as String? ?? '',
        orderId: j['orderId'] as String?,
        amount: (j['amount'] as num?)?.toDouble(),
        reason: j['reason'] as String?,
        detail: j['detail'] as String?,
      );

  /// An inventory movement of type ADJUST, read as an event on the trail.
  factory AuditEvent.fromMovement(Map<String, dynamic> j) => AuditEvent(
        id: j['id'] as String? ?? '',
        type: 'STOCK_ADJUSTMENT',
        occurredAt: DateTime.tryParse(j['createdAt'] as String? ?? ''),
        actorId: j['actorId'] as String?,
        storeId: j['storeId'] as String? ?? '',
        reason: j['reasonCode'] as String?,
        detail: j['refType'] as String?,
        qty: (j['qty'] as num?)?.toDouble(),
        variantId: j['variantId'] as String?,
      );
}

/// The trail's filters: one store or all, a period, one kind of action or
/// all, one member of staff or anyone. The whole filter is the provider
/// family's key, so changing any of it starts a fresh first page.
class AuditFilter {
  final String? storeId;
  final DateTime from;
  final DateTime to;
  final String? type;
  final String? actorId;

  const AuditFilter({
    this.storeId,
    required this.from,
    required this.to,
    this.type,
    this.actorId,
  });

  static const _unset = Object();

  AuditFilter copyWith({
    Object? storeId = _unset,
    DateTime? from,
    DateTime? to,
    Object? type = _unset,
    Object? actorId = _unset,
  }) =>
      AuditFilter(
        storeId: storeId == _unset ? this.storeId : storeId as String?,
        from: from ?? this.from,
        to: to ?? this.to,
        type: type == _unset ? this.type : type as String?,
        actorId: actorId == _unset ? this.actorId : actorId as String?,
      );

  /// Whether order-svc's trail is wanted at all under this filter.
  bool get wantsSales => type != 'STOCK_ADJUSTMENT';

  /// Whether inventory's adjustments are wanted under this filter.
  bool get wantsStock => type == null || type == 'STOCK_ADJUSTMENT';

  /// The API's upper bound is exclusive; the picker's is a day, inclusive.
  DateTime get toExclusive => to.add(const Duration(days: 1));

  Map<String, dynamic> get salesQuery => {
        if (storeId != null) 'store': storeId,
        'from': from.toUtc().toIso8601String(),
        'to': toExclusive.toUtc().toIso8601String(),
        if (type != null && type != 'STOCK_ADJUSTMENT') 'type': type,
        if (actorId != null) 'actor': actorId,
      };

  @override
  bool operator ==(Object other) =>
      other is AuditFilter &&
      other.storeId == storeId &&
      other.from == from &&
      other.to == to &&
      other.type == type &&
      other.actorId == actorId;

  @override
  int get hashCode => Object.hash(storeId, from, to, type, actorId);
}

final auditFilterProvider = StateProvider<AuditFilter>((ref) {
  final today = DateTime.now();
  final day = DateTime(today.year, today.month, today.day);
  return AuditFilter(from: day.subtract(const Duration(days: 29)), to: day);
});

/// What has been loaded so far: the merged timeline, and whether order-svc has
/// an older page to fetch.
class AuditTrailState {
  final List<AuditEvent> events;
  final String? nextCursor;
  final bool loading;
  final bool loadingMore;
  final Object? error;

  const AuditTrailState({
    this.events = const [],
    this.nextCursor,
    this.loading = false,
    this.loadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;
}

/// Loads the trail from its two sources and keeps them as one timeline.
///
/// order-svc's events are cursor-paged and carry every filter server-side.
/// Inventory's adjustments come from the movements ledger, which filters by
/// store and type but not by period or actor, so those two are applied here
/// to the page it returns. "Load older" pages order-svc only: adjustments are
/// a single bounded read.
class AuditTrailNotifier extends StateNotifier<AuditTrailState> {
  AuditTrailNotifier(this._ref, this._filter)
      : super(const AuditTrailState(loading: true)) {
    _load();
  }

  final Ref _ref;
  final AuditFilter _filter;
  static const _pageSize = 50;

  Future<void> _load() async {
    state = const AuditTrailState(loading: true);
    try {
      final sales = _filter.wantsSales ? await _fetchSales(null) : (const <AuditEvent>[], null);
      final stock = _filter.wantsStock ? await _fetchStock() : const <AuditEvent>[];
      if (!mounted) return;
      state = AuditTrailState(events: _merge([...sales.$1, ...stock]), nextCursor: sales.$2);
    } catch (e) {
      if (!mounted) return;
      state = AuditTrailState(error: e);
    }
  }

  Future<void> refresh() => _load();

  Future<void> loadOlder() async {
    if (state.loading || state.loadingMore || !state.hasMore) return;
    state = AuditTrailState(
        events: state.events, nextCursor: state.nextCursor, loadingMore: true);
    try {
      final (more, next) = await _fetchSales(state.nextCursor);
      if (!mounted) return;
      state = AuditTrailState(events: _merge([...state.events, ...more]), nextCursor: next);
    } catch (e) {
      if (!mounted) return;
      state = AuditTrailState(events: state.events, nextCursor: state.nextCursor, error: e);
    }
  }

  Future<(List<AuditEvent>, String?)> _fetchSales(String? after) async {
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.order}/admin/audit/events',
          queryParameters: {
            ..._filter.salesQuery,
            'limit': _pageSize,
            'after': ?after,
          },
        );
    final rows = (resp.data['data'] as List?) ?? const [];
    final next = (resp.data['meta'] as Map<String, dynamic>?)?['nextCursor'] as String?;
    return (
      rows.map((e) => AuditEvent.fromSales(e as Map<String, dynamic>)).toList(),
      next,
    );
  }

  Future<List<AuditEvent>> _fetchStock() async {
    final resp = await _ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.inventory}/admin/inventory/movements',
          queryParameters: {
            if (_filter.storeId != null) 'store': _filter.storeId,
            // The ledger's type for a manual adjustment; its refType is ADJUSTMENT.
            'type': 'ADJUST',
            'limit': 100,
          },
        );
    final rows = (resp.data['data'] as List?) ?? const [];
    final from = _filter.from.toUtc();
    final to = _filter.toExclusive.toUtc();
    return rows
        .map((e) => AuditEvent.fromMovement(e as Map<String, dynamic>))
        .where((e) {
          final at = e.occurredAt?.toUtc();
          if (at == null || at.isBefore(from) || !at.isBefore(to)) return false;
          return _filter.actorId == null || e.actorId == _filter.actorId;
        })
        .toList();
  }

  static List<AuditEvent> _merge(List<AuditEvent> all) {
    final out = [...all];
    out.sort((a, b) {
      final x = a.occurredAt, y = b.occurredAt;
      if (x == null) return y == null ? 0 : 1;
      if (y == null) return -1;
      return y.compareTo(x);
    });
    return out;
  }
}

final auditTrailProvider = StateNotifierProvider.autoDispose
    .family<AuditTrailNotifier, AuditTrailState, AuditFilter>(
  (ref, filter) => AuditTrailNotifier(ref, filter),
);

class AuditTrailScreen extends ConsumerWidget {
  const AuditTrailScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider);
    final staff = ref.watch(staffProvider);
    final filter = ref.watch(auditFilterProvider);
    final trail = ref.watch(auditTrailProvider(filter));
    final theme = Theme.of(context);
    final dateFmt = DateFormat.yMMMd();

    Future<void> pickRange() async {
      final picked = await showDateRangePicker(
        context: context,
        firstDate: DateTime(2020),
        lastDate: DateTime.now().add(const Duration(days: 1)),
        initialDateRange: DateTimeRange(start: filter.from, end: filter.to),
      );
      if (picked != null) {
        ref.read(auditFilterProvider.notifier).state = filter.copyWith(
          from: DateTime(picked.start.year, picked.start.month, picked.start.day),
          to: DateTime(picked.end.year, picked.end.month, picked.end.day),
        );
      }
    }

    // One entry per person, whatever stores they are assigned at.
    final actors = <String, String>{};
    for (final s in staff.value ?? const <StaffMember>[]) {
      actors.putIfAbsent(s.userId, () => '${s.role} · ${shortRef(s.userId)}');
    }

    return ListView(
      padding: const EdgeInsets.all(AppSpacing.xl),
      children: [
        Text('Audit trail', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 4),
        Text(
          'Who discounted, voided, opened the drawer, cancelled, took goods back '
          'or wrote stock off — one record across the till and the stockroom, '
          'newest first. Every row is an append-only log; nothing here can change one.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('audit-store'),
                isExpanded: true,
                initialValue: filter.storeId,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('All stores')),
                  for (final s in stores.value ?? const [])
                    DropdownMenuItem<String?>(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => ref.read(auditFilterProvider.notifier).state =
                    filter.copyWith(storeId: v),
              ),
            ),
            OutlinedButton.icon(
              onPressed: pickRange,
              icon: const Icon(Icons.date_range),
              label: Text('${dateFmt.format(filter.from)} – ${dateFmt.format(filter.to)}'),
            ),
            SizedBox(
              width: 220,
              child: DropdownButtonFormField<String?>(
                key: const Key('audit-type'),
                isExpanded: true,
                initialValue: filter.type,
                decoration: const InputDecoration(labelText: 'Action'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Everything')),
                  for (final e in auditTypeLabels.entries)
                    DropdownMenuItem<String?>(value: e.key, child: Text('${e.value}s')),
                ],
                onChanged: (v) => ref.read(auditFilterProvider.notifier).state =
                    filter.copyWith(type: v),
              ),
            ),
            SizedBox(
              width: 240,
              child: DropdownButtonFormField<String?>(
                key: const Key('audit-actor'),
                isExpanded: true,
                initialValue: actors.containsKey(filter.actorId) ? filter.actorId : null,
                decoration: const InputDecoration(labelText: 'Who'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Anyone')),
                  for (final e in actors.entries)
                    DropdownMenuItem<String?>(value: e.key, child: Text(e.value)),
                ],
                onChanged: (v) => ref.read(auditFilterProvider.notifier).state =
                    filter.copyWith(actorId: v),
              ),
            ),
            OutlinedButton.icon(
              key: const Key('audit-export'),
              onPressed: trail.events.isEmpty ? null : () => _exportCsv(trail.events),
              icon: const Icon(Icons.download_outlined),
              label: const Text('Export CSV'),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (trail.loading)
          const LoadingView(label: 'Loading the trail…')
        else if (trail.error != null && trail.events.isEmpty)
          ErrorView(
            message: friendlyError(trail.error!, fallback: 'Could not load the audit trail.'),
            onRetry: () => ref.read(auditTrailProvider(filter).notifier).refresh(),
          )
        else if (trail.events.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 24),
            child: Text(
              'Nothing recorded in this period. A busy shop with an empty trail has '
              'either had a quiet month or is not using the till for its exceptions.',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          )
        else ...[
          Card(
            child: Column(
              children: [for (final e in trail.events) _AuditRow(event: e)],
            ),
          ),
          if (trail.error != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                friendlyError(trail.error!, fallback: 'Could not load older events.'),
                style: TextStyle(color: theme.colorScheme.error),
              ),
            ),
          if (trail.hasMore)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Center(
                child: trail.loadingMore
                    ? const CircularProgressIndicator()
                    : TextButton.icon(
                        key: const Key('audit-load-older'),
                        onPressed: () =>
                            ref.read(auditTrailProvider(filter).notifier).loadOlder(),
                        icon: const Icon(Icons.expand_more),
                        label: const Text('Load older'),
                      ),
              ),
            ),
        ],
      ],
    );
  }

  static void _exportCsv(List<AuditEvent> events) {
    final buf = StringBuffer('occurredAt,type,actorId,storeId,orderId,amount,qty,variantId,reason,detail\n');
    for (final e in events) {
      buf.writeln([
        e.occurredAt?.toUtc().toIso8601String() ?? '',
        e.type,
        e.actorId ?? '',
        e.storeId,
        e.orderId ?? '',
        e.amount?.toStringAsFixed(2) ?? '',
        e.qty?.toString() ?? '',
        e.variantId ?? '',
        e.reason ?? '',
        e.detail ?? '',
      ].map(_csv).join(','));
    }
    downloadTextFile('audit-trail.csv', buf.toString(), mimeType: 'text/csv;charset=utf-8');
  }

  static String _csv(String s) =>
      s.contains(',') || s.contains('"') || s.contains('\n')
          ? '"${s.replaceAll('"', '""')}"'
          : s;
}

class _AuditRow extends StatelessWidget {
  const _AuditRow({required this.event});

  final AuditEvent event;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final e = event;
    final (icon, colour) = switch (e.type) {
      'DISCOUNT' => (Icons.sell_outlined, cs.tertiary),
      'VOID' => (Icons.block_outlined, cs.error),
      'NO_SALE' => (Icons.point_of_sale_outlined, cs.secondary),
      'CANCEL' => (Icons.cancel_outlined, cs.error),
      'RETURN' => (Icons.assignment_return_outlined, cs.primary),
      _ => (Icons.inventory_outlined, cs.secondary),
    };
    final label = auditTypeLabels[e.type] ?? e.type;
    final title = switch (e.type) {
      'DISCOUNT' =>
        '$label · ${e.amount?.toStringAsFixed(2) ?? ''}${e.detail != null ? ' (${e.detail})' : ''}',
      'CANCEL' => '$label${e.detail != null ? ' · from ${e.detail}' : ''}',
      'RETURN' =>
        '$label · ${e.amount?.toStringAsFixed(2) ?? ''} refunded${e.detail != null ? ' via ${e.detail}' : ''}',
      'STOCK_ADJUSTMENT' =>
        '$label · ${_signed(e.qty)}${e.variantId != null ? ' × ${shortRef(e.variantId!)}' : ''}',
      _ => label,
    };
    final when = e.occurredAt == null
        ? ''
        : DateFormat.yMMMd().add_Hm().format(e.occurredAt!.toLocal());
    final who = e.actorId == null ? 'Unattributed' : 'by ${shortRef(e.actorId!)}';
    final subtitle = [
      if (when.isNotEmpty) when,
      who,
      if (e.orderId != null) 'order ${shortRef(e.orderId!)}',
      if (e.reason != null && e.reason!.isNotEmpty) e.reason!,
    ].join(' · ');
    return ListTile(
      leading: Icon(icon, color: colour),
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: Chip(
        label: Text(e.fromStock ? 'Stock' : 'Till'),
        visualDensity: VisualDensity.compact,
      ),
    );
  }

  static String _signed(double? q) {
    if (q == null) return '';
    final s = q == q.roundToDouble() ? q.toInt().toString() : q.toString();
    return q > 0 ? '+$s' : s;
  }
}
