import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
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

/// The store whose queue is shown; null until the stores are known.
final fulfilmentStoreProvider = StateProvider<String?>((ref) => null);

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
          padding: AppSpacing.pagePadding,
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

  Future<void> _post(BuildContext context, WidgetRef ref, String path, Map<String, dynamic> body,
      String done, String storeId) async {
    final messenger = ScaffoldMessenger.of(context);
    final errorColor = Theme.of(context).colorScheme.error;
    try {
      await ref.read(apiClientProvider).dio.post(path, data: body);
      _refresh(ref, storeId);
      messenger.showSnackBar(SnackBar(content: Text(done)));
    } catch (e) {
      messenger.showSnackBar(SnackBar(
        content: Text(friendlyError(e, fallback: 'Could not record the handover.')),
        backgroundColor: errorColor,
      ));
    }
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
          Text(title, style: Theme.of(context).textTheme.titleMedium),
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
                        child: ListTile(
                          title: Text('Order #${shortRef(o.id)}'),
                          subtitle: Text(
                            '${o.fulfilmentType == 'DELIVERY' ? 'Delivery' : 'Collection'} · '
                            '${o.currency} ${o.total.toStringAsFixed(2)} · placed ${AppFormat.dateTime(o.createdAt)}'
                            '${o.handoverAt == null ? '' : ' · handed over ${AppFormat.dateTime(o.handoverAt)}'}',
                          ),
                          trailing: action != null
                              ? action!(o)
                              : trailing != null
                                  ? trailing!(o)
                                  : null,
                        ),
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
