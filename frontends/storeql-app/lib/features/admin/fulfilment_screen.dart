import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/ids.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// Ship-from-store and dark-store picking: one store's online orders as work.
// What waits to be picked (inventory-svc's list; picked on the Inventory
// screen's Picking tab), what is packed and waiting for the courier, what is
// ready and waiting for its shopper, and what was handed over today. A pick
// leaves an order FULFILLED — picked and packed; the handover after it is
// recorded here: Dispatch (carrier, reference, parcels) or Collected.
// Substitutions for out-of-stock online lines: what a confirmed order still
// owes is listed too, and a picker who finds a line short either puts a
// substitute in the bag (where the shopper allowed it) or closes the line short.
// ---------------------------------------------------------------------------

const _orders = '/${ApiConstants.order}/orders';
const _inv = '/${ApiConstants.inventory}/admin/inventory';

/// One order in the queue, as the list names it.
class QueuedOrder {
  final String id;
  final String storeId;
  final String fulfilmentType;
  final String status;
  final double total;
  final String currency;
  final String createdAt;
  final String? handoverKind;
  final String? handoverCarrier;
  final String? handoverReference;
  final String? handoverCollectedBy;
  final String? handoverAt;

  const QueuedOrder({
    required this.id,
    required this.storeId,
    required this.fulfilmentType,
    required this.status,
    required this.total,
    required this.currency,
    required this.createdAt,
    this.handoverKind,
    this.handoverCarrier,
    this.handoverReference,
    this.handoverCollectedBy,
    this.handoverAt,
  });

  factory QueuedOrder.fromJson(Map<String, dynamic> j) {
    final h = j['handover'] as Map<String, dynamic>?;
    return QueuedOrder(
      id: j['id'] as String? ?? '',
      storeId: j['storeId'] as String? ?? '',
      fulfilmentType: j['fulfilmentType'] as String? ?? '',
      status: j['status'] as String? ?? '',
      total: (j['total'] as num?)?.toDouble() ?? 0,
      currency: j['currency'] as String? ?? '',
      createdAt: j['createdAt'] as String? ?? '',
      handoverKind: h?['kind'] as String?,
      handoverCarrier: h?['carrier'] as String?,
      handoverReference: h?['reference'] as String?,
      handoverCollectedBy: h?['collectedBy'] as String?,
      handoverAt: h?['at'] as String?,
    );
  }

  /// How it left, in words: *Dispatched · DPD 1Z…* or *Collected by Sam*.
  String get handoverLabel {
    if (handoverKind == 'DISPATCHED') {
      final ref = [handoverCarrier, handoverReference]
          .where((e) => e != null && e.isNotEmpty)
          .join(' ');
      return 'Dispatched · $ref';
    }
    if (handoverKind == 'COLLECTED') {
      return handoverCollectedBy == null || handoverCollectedBy!.isEmpty
          ? 'Collected'
          : 'Collected by $handoverCollectedBy';
    }
    return '';
  }
}

/// A line an online order still owes (substitutions for out-of-stock online lines).
class OwingLine {
  final String variantId;
  final double qty;
  final double fulfilledQty;
  final double shortQty;
  final double outstandingQty;

  const OwingLine({
    required this.variantId,
    required this.qty,
    required this.fulfilledQty,
    required this.shortQty,
    required this.outstandingQty,
  });

  factory OwingLine.fromJson(Map<String, dynamic> j) => OwingLine(
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        fulfilledQty: (j['fulfilledQty'] as num?)?.toDouble() ?? 0,
        shortQty: (j['shortQty'] as num?)?.toDouble() ?? 0,
        outstandingQty: (j['outstandingQty'] as num?)?.toDouble() ?? 0,
      );
}

/// A confirmed or part-picked online order with the lines it still owes.
class OwingOrder {
  final String id;
  final String status;
  final String fulfilmentType;
  final bool allowSubstitutions;
  final String createdAt;
  final List<OwingLine> lines;

  const OwingOrder({
    required this.id,
    required this.status,
    required this.fulfilmentType,
    required this.allowSubstitutions,
    required this.createdAt,
    required this.lines,
  });

  factory OwingOrder.fromJson(Map<String, dynamic> j) => OwingOrder(
        id: j['orderId'] as String? ?? '',
        status: j['status'] as String? ?? '',
        fulfilmentType: j['fulfilmentType'] as String? ?? '',
        allowSubstitutions: j['allowSubstitutions'] as bool? ?? true,
        createdAt: j['createdAt'] as String? ?? '',
        lines: ((j['lines'] as List?) ?? const [])
            .map((e) => OwingLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// A stand-in the business declared for a line's product, with what the store has of it.
class SubstituteSuggestion {
  final String variantId;
  final String productName;
  final String sku;
  final double available;

  const SubstituteSuggestion({
    required this.variantId,
    required this.productName,
    required this.sku,
    required this.available,
  });

  factory SubstituteSuggestion.fromJson(Map<String, dynamic> j) => SubstituteSuggestion(
        variantId: j['variantId'] as String? ?? '',
        productName: j['productName'] as String? ?? '',
        sku: j['sku'] as String? ?? '',
        available: (j['available'] as num?)?.toDouble() ?? 0,
      );
}

/// The store whose queue is shown; null until the stores are known.
final fulfilmentStoreProvider = StateProvider<String?>((ref) => null);

/// What the store's confirmed online orders still owe, oldest first.
final owingProvider =
    FutureProvider.autoDispose.family<List<OwingOrder>, String>((ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('$_orders/owing', queryParameters: {'store': storeId});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => OwingOrder.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// The declared stand-ins for a line, most available first; none when product-svc lists none.
Future<List<SubstituteSuggestion>> _suggestions(
    WidgetRef ref, String orderId, String variantId) async {
  try {
    final resp = await ref
        .read(apiClientProvider)
        .dio
        .get('$_orders/$orderId/lines/$variantId/substitutes');
    return ((resp.data['data'] as List?) ?? const [])
        .map((e) => SubstituteSuggestion.fromJson(e as Map<String, dynamic>))
        .toList();
  } catch (_) {
    return const [];
  }
}

/// A quantity as a person writes it: 2, not 2.0; 1.5 stays 1.5.
String qtyText(double q) => q == q.roundToDouble() ? q.toStringAsFixed(0) : q.toString();

num _qtyNumber(double q) => q == q.roundToDouble() ? q.toInt() : q;

Future<List<QueuedOrder>> _queue(Ref ref, Map<String, dynamic> query) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get(_orders, queryParameters: {...query, 'limit': 100});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => QueuedOrder.fromJson(e as Map<String, dynamic>))
      .toList();
}

/// Picked deliveries waiting for the courier.
final packedProvider =
    FutureProvider.autoDispose.family<List<QueuedOrder>, String>((ref, storeId) => _queue(ref, {
          'store': storeId,
          'status': 'FULFILLED',
          'fulfilmentType': 'DELIVERY',
          'handover': 'PENDING',
        }));

/// Picked pickups waiting for their shopper.
final readyProvider =
    FutureProvider.autoDispose.family<List<QueuedOrder>, String>((ref, storeId) => _queue(ref, {
          'store': storeId,
          'status': 'FULFILLED',
          'fulfilmentType': 'PICKUP',
          'handover': 'PENDING',
        }));

/// Handed over today (UTC), dispatched or collected.
final handedOverTodayProvider =
    FutureProvider.autoDispose.family<List<QueuedOrder>, String>((ref, storeId) {
  final now = DateTime.now().toUtc();
  final from = DateTime.utc(now.year, now.month, now.day).toIso8601String();
  return _queue(ref, {'store': storeId, 'handover': 'DONE', 'from': from});
});

/// How many confirmed orders wait to be picked at the store (inventory-svc's list).
final awaitingPickCountProvider =
    FutureProvider.autoDispose.family<int, String>((ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('$_inv/waves/awaiting', queryParameters: {'storeId': storeId});
  return ((resp.data['data'] as List?) ?? const []).length;
});

class FulfilmentScreen extends ConsumerWidget {
  const FulfilmentScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storesProvider);
    return storesAsync.when(
      loading: () => const LoadingView(label: 'Loading stores…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the stores.'),
        onRetry: () => ref.invalidate(storesProvider),
      ),
      data: (all) {
        // Shops and dark stores fill online orders; a warehouse serves shops and takes no
        // shopper's order.
        final stores = all.where((s) => s.type.toUpperCase() != 'WAREHOUSE').toList();
        if (stores.isEmpty) {
          return const EmptyState(icon: Icons.store_outlined, title: 'No store yet');
        }
        final chosen = ref.watch(fulfilmentStoreProvider) ?? stores.first.id;
        final store = stores.firstWhere((s) => s.id == chosen, orElse: () => stores.first);
        return ListView(
          padding: context.pagePadding,
          children: [
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    key: const Key('fulfilment-store'),
                    initialValue: store.id,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Store'),
                    items: [
                      for (final s in stores)
                        DropdownMenuItem(
                          value: s.id,
                          child: Text(
                            s.type.toUpperCase() == 'DARK_STORE' ? '${s.name} · dark store' : s.name,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (v) => ref.read(fulfilmentStoreProvider.notifier).state = v,
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                IconButton(
                  tooltip: 'Refresh',
                  onPressed: () => _refresh(ref, store.id),
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.lg),
            _ToPick(storeId: store.id),
            const SizedBox(height: AppSpacing.lg),
            _Outstanding(storeId: store.id),
            const SizedBox(height: AppSpacing.lg),
            _Stage(
              title: 'Packed — awaiting courier',
              icon: Icons.inventory_2_outlined,
              empty: 'Nothing packed is waiting for the courier',
              orders: ref.watch(packedProvider(store.id)),
              onRetry: () => ref.invalidate(packedProvider(store.id)),
              action: (o) => FilledButton.icon(
                key: Key('dispatch-${o.id}'),
                onPressed: () => _dispatch(context, ref, o),
                icon: const Icon(Icons.local_shipping_outlined, size: 18),
                label: const Text('Dispatch'),
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            _Stage(
              title: 'Ready for collection',
              icon: Icons.shopping_bag_outlined,
              empty: 'Nothing is waiting to be collected',
              orders: ref.watch(readyProvider(store.id)),
              onRetry: () => ref.invalidate(readyProvider(store.id)),
              action: (o) => FilledButton.icon(
                key: Key('collect-${o.id}'),
                onPressed: () => _collect(context, ref, o),
                icon: const Icon(Icons.check, size: 18),
                label: const Text('Collected'),
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            _Stage(
              title: 'Handed over today',
              icon: Icons.outbox_outlined,
              empty: 'Nothing handed over yet today',
              orders: ref.watch(handedOverTodayProvider(store.id)),
              onRetry: () => ref.invalidate(handedOverTodayProvider(store.id)),
              trailing: (o) => Text(o.handoverLabel, key: Key('handed-${o.id}')),
            ),
          ],
        );
      },
    );
  }

  static void _refresh(WidgetRef ref, String storeId) {
    ref.invalidate(awaitingPickCountProvider(storeId));
    ref.invalidate(owingProvider(storeId));
    ref.invalidate(packedProvider(storeId));
    ref.invalidate(readyProvider(storeId));
    ref.invalidate(handedOverTodayProvider(storeId));
  }

  Future<void> _dispatch(BuildContext context, WidgetRef ref, QueuedOrder o) async {
    final result = await showDialog<_DispatchInput>(
      context: context,
      builder: (_) => const _DispatchDialog(),
    );
    if (result == null || !context.mounted) return;
    await _post(context, ref, '$_orders/${o.id}/dispatch', {
      'carrier': result.carrier,
      if (result.reference.isNotEmpty) 'reference': result.reference,
      if (result.parcels != null) 'parcels': result.parcels,
    }, 'Dispatched with ${result.carrier}.', o.storeId);
  }

  Future<void> _collect(BuildContext context, WidgetRef ref, QueuedOrder o) async {
    final who = await showDialog<String>(
      context: context,
      builder: (_) => const _CollectDialog(),
    );
    if (who == null || !context.mounted) return;
    await _post(context, ref, '$_orders/${o.id}/collect',
        {if (who.trim().isNotEmpty) 'collectedBy': who.trim()}, 'Collected.', o.storeId);
  }

  static Future<void> _post(BuildContext context, WidgetRef ref, String path,
      Map<String, dynamic> body, String done, String storeId,
      {String fallback = 'Could not record the handover.'}) async {
    final messenger = ScaffoldMessenger.of(context);
    final errorColor = Theme.of(context).colorScheme.error;
    try {
      // Once per attempt: a retried tap must not close or substitute a line twice.
      await ref.read(apiClientProvider).dio.post(path,
          data: body, options: Options(headers: {'Idempotency-Key': newId()}));
      _refresh(ref, storeId);
      messenger.showSnackBar(SnackBar(content: Text(done)));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
        content: Text(friendlyError(e, fallback: fallback)),
        backgroundColor: errorColor,
      ));
    }
  }
}

/// What the store's confirmed online orders still owe, line by line, and what a picker does about
/// a line the shelf cannot fill: a substitute where the shopper allowed one, else closing it short.
class _Outstanding extends ConsumerWidget {
  final String storeId;
  const _Outstanding({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final owing = ref.watch(owingProvider(storeId));
    final ids = owing.value?.expand((o) => o.lines.map((l) => l.variantId)) ?? const <String>[];
    final labels = ref.watch(variantLabelsProvider(variantIdsKey(ids))).value ?? const {};
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(children: [
          Icon(Icons.rule_outlined, size: 18, color: cs.onSurfaceVariant),
          const SizedBox(width: AppSpacing.sm),
          Text('Outstanding lines', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(width: AppSpacing.sm),
          if (owing.value != null)
            Text('${owing.value!.length}',
                key: const Key('owing-count'), style: TextStyle(color: cs.onSurfaceVariant)),
        ]),
        const SizedBox(height: AppSpacing.sm),
        owing.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load what is owed.'),
            onRetry: () => ref.invalidate(owingProvider(storeId)),
          ),
          data: (list) => list.isEmpty
              ? Text('Nothing is owed: every confirmed order is picked or closed.',
                  style: TextStyle(color: cs.onSurfaceVariant))
              : Column(
                  children: [
                    for (final o in list)
                      Card(
                        key: Key('owing-${o.id}'),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            ListTile(
                              title: Text('Order #${shortRef(o.id)}'),
                              subtitle: Text(
                                '${o.fulfilmentType == 'DELIVERY' ? 'Delivery' : 'Collection'} · '
                                'placed ${AppFormat.dateTime(o.createdAt)} · '
                                '${o.allowSubstitutions ? 'substitutions allowed' : 'no substitutions'}',
                              ),
                            ),
                            for (final l in o.lines)
                              Builder(builder: (context) {
                                final actions = Wrap(
                                  spacing: AppSpacing.sm,
                                  runSpacing: AppSpacing.xs,
                                  children: [
                                    if (o.allowSubstitutions)
                                      OutlinedButton(
                                        key: Key('substitute-${o.id}-${l.variantId}'),
                                        onPressed: () => _substitute(context, ref, o, l),
                                        child: const Text('Substitute'),
                                      ),
                                    TextButton(
                                      key: Key('short-${o.id}-${l.variantId}'),
                                      onPressed: () => _short(context, ref, o, l),
                                      child: const Text('Short'),
                                    ),
                                  ],
                                );
                                final outstanding = Text(
                                    '${qtyText(l.outstandingQty)} of ${qtyText(l.qty)} outstanding');
                                // On a phone the actions go under the line, so the
                                // product's name keeps the width.
                                final compact = context.isCompact;
                                return ListTile(
                                  dense: true,
                                  title: Text(variantDisplayName(l.variantId, labels)),
                                  subtitle: compact
                                      ? Column(
                                          crossAxisAlignment: CrossAxisAlignment.start,
                                          children: [outstanding, actions],
                                        )
                                      : outstanding,
                                  trailing: compact ? null : actions,
                                );
                              }),
                          ],
                        ),
                      ),
                  ],
                ),
        ),
      ],
    );
  }

  Future<void> _substitute(
      BuildContext context, WidgetRef ref, OwingOrder o, OwingLine l) async {
    final suggestions = await _suggestions(ref, o.id, l.variantId);
    if (!context.mounted) return;
    final result = await showDialog<_SubstituteInput>(
      context: context,
      builder: (_) => _SubstituteDialog(outstanding: l.outstandingQty, suggestions: suggestions),
    );
    if (result == null || !context.mounted) return;
    await FulfilmentScreen._post(
      context,
      ref,
      '$_orders/${o.id}/lines/${l.variantId}/substitute',
      {
        'substituteVariantId': result.variantId,
        'qty': _qtyNumber(result.qty),
        if (result.reason.isNotEmpty) 'reason': result.reason,
      },
      'Substituted. The shopper is told and pays no more.',
      storeId,
      fallback: 'Could not substitute the line.',
    );
  }

  Future<void> _short(BuildContext context, WidgetRef ref, OwingOrder o, OwingLine l) async {
    final result = await showDialog<_ShortInput>(
      context: context,
      builder: (_) => _ShortDialog(outstanding: l.outstandingQty),
    );
    if (result == null || !context.mounted) return;
    await FulfilmentScreen._post(
      context,
      ref,
      '$_orders/${o.id}/lines/${l.variantId}/short',
      {'qty': _qtyNumber(result.qty), if (result.reason.isNotEmpty) 'reason': result.reason},
      'Closed short. The shopper is told and refunded.',
      storeId,
      fallback: 'Could not close the line short.',
    );
  }
}

class _SubstituteInput {
  final String variantId;
  final double qty;
  final String reason;
  const _SubstituteInput(this.variantId, this.qty, this.reason);
}

/// Which stand-in went in the bag, and how many: a declared one from the list, or any variant
/// the picker names; at most what the line still owes.
class _SubstituteDialog extends StatefulWidget {
  final double outstanding;
  final List<SubstituteSuggestion> suggestions;
  const _SubstituteDialog({required this.outstanding, required this.suggestions});

  @override
  State<_SubstituteDialog> createState() => _SubstituteDialogState();
}

class _SubstituteDialogState extends State<_SubstituteDialog> {
  final _variant = TextEditingController();
  late final _qty = TextEditingController(text: qtyText(widget.outstanding));
  final _reason = TextEditingController();
  String? _chosen;
  String? _error;

  @override
  void dispose() {
    _variant.dispose();
    _qty.dispose();
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Substitute'),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          if (widget.suggestions.isEmpty)
            Text(
              'No stand-ins are declared for this product. Enter the variant id of what you packed.',
              style: TextStyle(color: cs.onSurfaceVariant),
            ),
          for (final s in widget.suggestions)
            ListTile(
              key: Key('suggestion-${s.variantId}'),
              dense: true,
              contentPadding: EdgeInsets.zero,
              selected: _chosen == s.variantId,
              leading: Icon(_chosen == s.variantId
                  ? Icons.radio_button_checked
                  : Icons.radio_button_off),
              title: Text(s.productName.isEmpty ? '…${shortRef(s.variantId)}' : s.productName),
              subtitle: Text('${s.sku.isEmpty ? '' : '${s.sku} · '}${qtyText(s.available)} available'),
              onTap: () => setState(() {
                _chosen = s.variantId;
                _variant.text = s.variantId;
              }),
            ),
          TextField(
            key: const Key('substitute-variant'),
            controller: _variant,
            decoration: const InputDecoration(labelText: 'Variant id of what you packed'),
            onChanged: (_) => setState(() => _chosen = null),
          ),
          TextField(
            key: const Key('substitute-qty'),
            controller: _qty,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
                labelText: 'Quantity', helperText: '${qtyText(widget.outstanding)} outstanding'),
          ),
          TextField(
            key: const Key('substitute-reason'),
            controller: _reason,
            decoration: const InputDecoration(labelText: 'Reason (optional)'),
          ),
          if (_error != null) ...[
            const SizedBox(height: AppSpacing.sm),
            Text(_error!, key: const Key('substitute-error'), style: TextStyle(color: cs.error)),
          ],
        ]),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('substitute-save'),
          onPressed: () {
            final variant = _variant.text.trim();
            final qty = double.tryParse(_qty.text.trim());
            if (variant.isEmpty) {
              setState(() => _error = 'Say what you packed.');
              return;
            }
            if (qty == null || qty <= 0 || qty > widget.outstanding) {
              setState(() => _error =
                  'Between 0 and ${qtyText(widget.outstanding)}, what the line still owes.');
              return;
            }
            Navigator.pop(context, _SubstituteInput(variant, qty, _reason.text.trim()));
          },
          child: const Text('Substituted'),
        ),
      ],
    );
  }
}

class _ShortInput {
  final double qty;
  final String reason;
  const _ShortInput(this.qty, this.reason);
}

/// How much of the line will never be handed over, and why.
class _ShortDialog extends StatefulWidget {
  final double outstanding;
  const _ShortDialog({required this.outstanding});

  @override
  State<_ShortDialog> createState() => _ShortDialogState();
}

class _ShortDialogState extends State<_ShortDialog> {
  late final _qty = TextEditingController(text: qtyText(widget.outstanding));
  final _reason = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _qty.dispose();
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Close short'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        Text('The shopper is refunded for what they will not get.',
            style: TextStyle(color: cs.onSurfaceVariant)),
        TextField(
          key: const Key('short-qty'),
          controller: _qty,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(
              labelText: 'Quantity', helperText: '${qtyText(widget.outstanding)} outstanding'),
        ),
        TextField(
          key: const Key('short-reason'),
          controller: _reason,
          decoration: const InputDecoration(labelText: 'Reason (optional)'),
        ),
        if (_error != null) ...[
          const SizedBox(height: AppSpacing.sm),
          Text(_error!, key: const Key('short-error'), style: TextStyle(color: cs.error)),
        ],
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('short-save'),
          onPressed: () {
            final qty = double.tryParse(_qty.text.trim());
            if (qty == null || qty <= 0 || qty > widget.outstanding) {
              setState(() => _error =
                  'Between 0 and ${qtyText(widget.outstanding)}, what the line still owes.');
              return;
            }
            Navigator.pop(context, _ShortInput(qty, _reason.text.trim()));
          },
          child: const Text('Close short'),
        ),
      ],
    );
  }
}

/// What waits to be picked at the store: the count from inventory-svc's list, picked on the
/// Inventory screen's Picking & putaway tab (a wave, or one order at a time).
class _ToPick extends ConsumerWidget {
  final String storeId;
  const _ToPick({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(awaitingPickCountProvider(storeId));
    return Card(
      child: ListTile(
        leading: const Icon(Icons.checklist_outlined),
        title: const Text('To pick'),
        subtitle: count.when(
          loading: () => const Text('Counting…'),
          error: (_, _) => const Text('Could not read the waiting list.'),
          data: (n) => Text(
            key: const Key('to-pick-count'),
            n == 0
                ? 'No order is waiting to be picked.'
                : '$n order${n == 1 ? '' : 's'} waiting — picked on Inventory › Picking & putaway.',
          ),
        ),
      ),
    );
  }
}

class _Stage extends StatelessWidget {
  final String title;
  final IconData icon;
  final String empty;
  final AsyncValue<List<QueuedOrder>> orders;
  final VoidCallback onRetry;
  final Widget Function(QueuedOrder)? action;
  final Widget Function(QueuedOrder)? trailing;
  const _Stage({
    required this.title,
    required this.icon,
    required this.empty,
    required this.orders,
    required this.onRetry,
    this.action,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(children: [
          Icon(icon, size: 18, color: cs.onSurfaceVariant),
          const SizedBox(width: AppSpacing.sm),
          // The title wraps on a phone rather than running off it.
          Flexible(child: Text(title, style: Theme.of(context).textTheme.titleMedium)),
          const SizedBox(width: AppSpacing.sm),
          if (orders.value != null)
            Text('${orders.value!.length}', style: TextStyle(color: cs.onSurfaceVariant)),
        ]),
        const SizedBox(height: AppSpacing.sm),
        orders.when(
          loading: () => const LoadingView(),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the queue.'),
            onRetry: onRetry,
          ),
          data: (list) => list.isEmpty
              ? Text(empty, style: TextStyle(color: cs.onSurfaceVariant))
              : Column(
                  children: [
                    for (final o in list)
                      Card(
                        key: Key('queued-${o.id}'),
                        child: Builder(builder: (context) {
                          final says = Text(
                            '${o.fulfilmentType == 'DELIVERY' ? 'Delivery' : 'Collection'} · '
                            '${AppFormat.money(o.total, currencyCode: o.currency)} · placed ${AppFormat.dateTime(o.createdAt)}'
                            '${o.handoverAt == null ? '' : ' · handed over ${AppFormat.dateTime(o.handoverAt)}'}',
                          );
                          final end = action != null
                              ? action!(o)
                              : trailing != null
                                  ? trailing!(o)
                                  : null;
                          // On a phone the action goes under the order, so its
                          // three-part line keeps the width.
                          final compact = context.isCompact && end != null;
                          return ListTile(
                            title: Text('Order #${shortRef(o.id)}'),
                            subtitle: compact
                                ? Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      says,
                                      const SizedBox(height: AppSpacing.xs),
                                      Align(
                                        alignment: AlignmentDirectional.centerStart,
                                        child: end,
                                      ),
                                    ],
                                  )
                                : says,
                            trailing: compact ? null : end,
                          );
                        }),
                      ),
                  ],
                ),
        ),
      ],
    );
  }
}

class _DispatchInput {
  final String carrier;
  final String reference;
  final int? parcels;
  const _DispatchInput(this.carrier, this.reference, this.parcels);
}

/// The carrier a parcel left with, its reference and the parcel count when known.
class _DispatchDialog extends StatefulWidget {
  const _DispatchDialog();

  @override
  State<_DispatchDialog> createState() => _DispatchDialogState();
}

class _DispatchDialogState extends State<_DispatchDialog> {
  final _carrier = TextEditingController();
  final _reference = TextEditingController();
  final _parcels = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _carrier.dispose();
    _reference.dispose();
    _parcels.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Dispatch'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('dispatch-carrier'),
            controller: _carrier,
            autofocus: true,
            decoration: InputDecoration(labelText: 'Carrier', errorText: _error),
          ),
          TextField(
            key: const Key('dispatch-reference'),
            controller: _reference,
            decoration: const InputDecoration(labelText: 'Reference or tracking number (optional)'),
          ),
          TextField(
            key: const Key('dispatch-parcels'),
            controller: _parcels,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(labelText: 'Parcels (optional)'),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('dispatch-save'),
          onPressed: () {
            final carrier = _carrier.text.trim();
            if (carrier.isEmpty) {
              setState(() => _error = 'Say who is carrying it.');
              return;
            }
            final parcels = int.tryParse(_parcels.text.trim());
            Navigator.pop(context, _DispatchInput(carrier, _reference.text.trim(), parcels));
          },
          child: const Text('Dispatched'),
        ),
      ],
    );
  }
}

/// Who took a collection, when the counter noted it.
class _CollectDialog extends StatefulWidget {
  const _CollectDialog();

  @override
  State<_CollectDialog> createState() => _CollectDialogState();
}

class _CollectDialogState extends State<_CollectDialog> {
  final _who = TextEditingController();

  @override
  void dispose() {
    _who.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Collected'),
      content: TextField(
        key: const Key('collect-who'),
        controller: _who,
        autofocus: true,
        decoration: const InputDecoration(labelText: 'Collected by (optional)'),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('collect-save'),
          onPressed: () => Navigator.pop(context, _who.text),
          child: const Text('Collected'),
        ),
      ],
    );
  }
}
