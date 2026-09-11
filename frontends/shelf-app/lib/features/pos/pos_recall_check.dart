import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ---------------------------------------------------------------------------
// The till's recall check.
//
// A recall quarantines the affected batches in inventory-svc, which stops them
// being reserved online — but the till takes no reservation, and it sells a
// variant, not a batch. Without a check here a cashier would ring up the
// recalled jar of peanut butter the store has just been told to take off the
// shelf. So the till keeps the list of open recalls and checks every item
// against it before it reaches the sale, by scan or by catalog pick alike.
//
// The list is kept rather than asked for per scan: a scan should not wait on a
// network call, and a till that has lost its network is still a till.
// ---------------------------------------------------------------------------

/// How long a list may go without a successful refresh before the till says so.
const recallListStaleAfter = Duration(minutes: 30);

/// One scope line of an open recall.
class ActiveRecallItem {
  final String recallId;
  final String reference;

  /// WITHDRAWAL (taken off sale) or RECALL (customers are told as well).
  final String kind;
  final String hazard;
  final String? customerNotice;
  final String variantId;
  final String? batchNo;
  final DateTime? expiryFrom;
  final DateTime? expiryTo;

  const ActiveRecallItem({
    required this.recallId,
    required this.reference,
    required this.kind,
    required this.hazard,
    this.customerNotice,
    required this.variantId,
    this.batchNo,
    this.expiryFrom,
    this.expiryTo,
  });

  /// True when the recall names no lot and no dates: every pack is affected.
  bool get coversEveryPack =>
      batchNo == null && expiryFrom == null && expiryTo == null;

  factory ActiveRecallItem.fromJson(Map<String, dynamic> j) => ActiveRecallItem(
        recallId: j['recallId'] as String? ?? '',
        reference: j['reference'] as String? ?? '',
        kind: j['kind'] as String? ?? 'RECALL',
        hazard: j['hazard'] as String? ?? 'OTHER',
        customerNotice: j['customerNotice'] as String?,
        variantId: j['variantId'] as String? ?? '',
        batchNo: j['batchNo'] as String?,
        expiryFrom: _date(j['expiryFrom']),
        expiryTo: _date(j['expiryTo']),
      );

  static DateTime? _date(Object? v) => v is String ? DateTime.tryParse(v) : null;
}

class RecallList {
  final List<ActiveRecallItem> items;
  final DateTime? fetchedAt;

  /// True when the last attempt to refresh failed.
  final bool failed;

  const RecallList({this.items = const [], this.fetchedAt, this.failed = false});

  /// The till says so when it has not heard about recalls for a while — but it
  /// keeps selling: blocking every sale because inventory-svc is down would
  /// close the shop, and the list it holds is still the best it knows.
  bool isStale(DateTime now) =>
      failed &&
      (fetchedAt == null || now.difference(fetchedAt!) > recallListStaleAfter);
}

sealed class RecallCheckResult {
  const RecallCheckResult();
}

class RecallClear extends RecallCheckResult {
  const RecallClear();
}

/// Every pack of this item is recalled. Not sold, and no override.
class RecallBlocked extends RecallCheckResult {
  final ActiveRecallItem item;
  const RecallBlocked(this.item);
}

/// Only some lots or dates are recalled, and the till cannot see the pack — so
/// the cashier is told exactly what to look for.
class RecallCheckPack extends RecallCheckResult {
  final List<ActiveRecallItem> items;
  const RecallCheckPack(this.items);
}

RecallCheckResult checkRecall(String variantId, List<ActiveRecallItem> items) {
  final matching = items.where((i) => i.variantId == variantId).toList();
  if (matching.isEmpty) return const RecallClear();
  for (final i in matching) {
    if (i.coversEveryPack) return RecallBlocked(i);
  }
  return RecallCheckPack(matching);
}

class ActiveRecallsNotifier extends Notifier<RecallList> {
  static const refreshEvery = Duration(minutes: 5);

  @override
  RecallList build() {
    final timer = Timer.periodic(refreshEvery, (_) => refresh());
    ref.onDispose(timer.cancel);
    Future.microtask(refresh);
    return const RecallList();
  }

  Future<void> refresh() async {
    try {
      final resp = await ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.inventory}/admin/inventory/recalls/active');
      final data = (resp.data as Map?)?['data'];
      if (data is! List) throw const FormatException('no recall list');
      state = RecallList(
        items: [
          for (final e in data)
            if (e is Map) ActiveRecallItem.fromJson(e.cast<String, dynamic>()),
        ],
        fetchedAt: DateTime.now(),
      );
    } catch (_) {
      // Keep what the till already knew: an outage must not empty the list.
      state = RecallList(items: state.items, fetchedAt: state.fetchedAt, failed: true);
    }
  }
}

final activeRecallsProvider =
    NotifierProvider<ActiveRecallsNotifier, RecallList>(ActiveRecallsNotifier.new);

String _hazardLabel(String hazard) => switch (hazard) {
      'MICROBIOLOGICAL' => 'Microbiological contamination',
      'ALLERGEN' => 'Undeclared allergen',
      'FOREIGN_BODY' => 'Foreign body',
      'CHEMICAL' => 'Chemical contamination',
      'LABELLING' => 'Labelling error',
      'QUALITY' => 'Quality defect',
      _ => 'Safety issue',
    };

String _kindLabel(String kind) =>
    kind == 'WITHDRAWAL' ? 'Product withdrawal' : 'Product recall';

final _day = DateFormat('d MMM yyyy');

/// What to look for on the pack, in the words printed on it.
String describePackScope(ActiveRecallItem i) {
  final parts = <String>[
    if (i.batchNo != null) 'lot ${i.batchNo}',
    if (i.expiryFrom != null && i.expiryTo != null)
      'best before ${_day.format(i.expiryFrom!)} to ${_day.format(i.expiryTo!)}'
    else if (i.expiryFrom != null)
      'best before ${_day.format(i.expiryFrom!)} or later'
    else if (i.expiryTo != null)
      'best before ${_day.format(i.expiryTo!)} or earlier',
  ];
  return parts.join(', ');
}

/// Every pack of the item is recalled: the cashier can only take it out.
class RecallStopSaleDialog extends StatelessWidget {
  final String itemName;
  final ActiveRecallItem item;
  const RecallStopSaleDialog({super.key, required this.itemName, required this.item});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      icon: Icon(Icons.dangerous_outlined, color: cs.error),
      title: const Text('Do not sell this item'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(itemName, style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          Text('${_kindLabel(item.kind)} ${item.reference} · ${_hazardLabel(item.hazard)}'),
          if (item.customerNotice != null) ...[
            const SizedBox(height: 12),
            Text(item.customerNotice!),
          ],
          const SizedBox(height: 12),
          const Text('Every pack is affected. Keep it out of the sale and hand it '
              'to a supervisor for the store\'s withdrawal.'),
        ],
      ),
      actions: [
        FilledButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Remove from sale'),
        ),
      ],
    );
  }
}

/// Some lots or dates are recalled: the cashier checks the pack. Removing it is
/// the prominent answer; selling it means having looked.
class RecallCheckPackDialog extends StatelessWidget {
  final String itemName;
  final List<ActiveRecallItem> items;
  const RecallCheckPackDialog({super.key, required this.itemName, required this.items});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      icon: Icon(Icons.fact_check_outlined, color: cs.error),
      title: const Text('Check the pack before selling'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(itemName, style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          const Text('Do not sell it if the pack shows:'),
          const SizedBox(height: 4),
          for (final i in items)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text('• ${describePackScope(i)} '
                  '(${_kindLabel(i.kind).toLowerCase()} ${i.reference}, '
                  '${_hazardLabel(i.hazard).toLowerCase()})'),
            ),
        ],
      ),
      actions: [
        OutlinedButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Not affected — sell'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Affected — remove'),
        ),
      ],
    );
  }
}

/// Shown at the till while the recall list cannot be refreshed.
class RecallListBanner extends ConsumerWidget {
  const RecallListBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final list = ref.watch(activeRecallsProvider);
    if (!list.isStale(DateTime.now())) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: cs.errorContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Row(children: [
          Icon(Icons.sync_problem, color: cs.onErrorContainer, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              list.fetchedAt == null
                  ? "Recalls couldn't be loaded. Check items against the recall notices."
                  : "Recalls haven't refreshed for a while. Check items against the recall notices.",
              style: TextStyle(color: cs.onErrorContainer),
            ),
          ),
          TextButton(
            onPressed: () => ref.read(activeRecallsProvider.notifier).refresh(),
            child: const Text('Retry'),
          ),
        ]),
      ),
    );
  }
}
