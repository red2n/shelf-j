import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../core/format.dart';
import 'providers/admin_providers.dart';
import 'providers/orders_pagination.dart';

class AdminOrdersScreen extends ConsumerStatefulWidget {
  const AdminOrdersScreen({super.key});

  @override
  ConsumerState<AdminOrdersScreen> createState() => _AdminOrdersScreenState();
}

class _AdminOrdersScreenState extends ConsumerState<AdminOrdersScreen> {
  String _channel = 'ALL';
  String _status = 'ALL';

  static const _channels = ['ALL', 'ONLINE', 'POS'];
  static const _statuses = [
    'ALL',
    'PENDING',
    'CONFIRMED',
    'FULFILLED',
    'CANCELLED'
  ];

  final _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  OrdersFilter get _filter => OrdersFilter(_channel, _status);

  /// Fetch the next page once the user scrolls within 300px of the bottom.
  void _onScroll() {
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 300) {
      ref.read(ordersPaginationProvider(_filter).notifier).loadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(ordersPaginationProvider(_filter));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            children: [
              Text('Orders', style: Theme.of(context).textTheme.headlineMedium),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh',
                onPressed: () =>
                    ref.read(ordersPaginationProvider(_filter).notifier).refresh(),
              ),
            ],
          ),
        ),

        // Filter bar — scrollable on mobile
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.fromLTRB(24, 16, 24, 0),
          child: Row(
            children: [
              // Channel chips
              ...(_channels.map((c) => Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: FilterChip(
                      label: Text(c == 'ALL' ? 'All channels' : c),
                      selected: _channel == c,
                      onSelected: (_) => setState(() => _channel = c),
                      avatar: c == 'POS'
                          ? const Icon(Icons.point_of_sale, size: 14)
                          : c == 'ONLINE'
                              ? const Icon(Icons.shopping_bag_outlined, size: 14)
                              : null,
                    ),
                  ))),
              const SizedBox(width: 8),
              const VerticalDivider(width: 1, indent: 4, endIndent: 4),
              const SizedBox(width: 8),
              // Status chips
              ...(_statuses.map((s) => Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: FilterChip(
                      label: Text(s == 'ALL' ? 'All statuses' : s),
                      selected: _status == s,
                      onSelected: (_) => setState(() => _status = s),
                    ),
                  ))),
            ],
          ),
        ),
        const SizedBox(height: 16),

        // Orders list
        Expanded(
          child: Builder(builder: (context) {
            if (page.isLoadingInitial) {
              return const LoadingView(label: 'Loading orders…');
            }
            if (page.error != null && page.orders.isEmpty) {
              return ErrorView(
                message: 'Could not load orders.\n${page.error}',
                onRetry: () =>
                    ref.read(ordersPaginationProvider(_filter).notifier).refresh(),
              );
            }
            final orders = page.orders;
            if (orders.isEmpty) {
                final hasFilter = _channel != 'ALL' || _status != 'ALL';
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.receipt_long_outlined,
                          size: 64,
                          color: Theme.of(context).colorScheme.outlineVariant),
                      const SizedBox(height: 16),
                      Text(
                        hasFilter ? 'No matching orders' : 'No orders yet',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 6),
                      Text(
                        hasFilter
                            ? 'Try changing the channel or status filter.'
                            : 'Orders placed by customers will appear here.',
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.outline),
                      ),
                    ],
                  ),
                );
              }
              return LayoutBuilder(builder: (context, bc) {
                final wide = bc.maxWidth >= 700;
                return ListView.separated(
                  controller: _scrollController,
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  itemCount:
                      orders.length + (page.hasMore || page.isLoadingMore ? 1 : 0),
                  separatorBuilder: (_, _) => const SizedBox(height: 4),
                  itemBuilder: (context, i) {
                    if (i >= orders.length) {
                      return const Padding(
                        padding: EdgeInsets.all(16),
                        child: Center(child: CircularProgressIndicator()),
                      );
                    }
                    final o = orders[i];
                    return Card(
                      child: ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                            horizontal: 16, vertical: 8),
                        leading: CircleAvatar(
                          backgroundColor: o.channel == 'POS'
                              ? Colors.orange.shade100
                              : Colors.blue.shade100,
                          child: Icon(
                            o.channel == 'POS'
                                ? Icons.point_of_sale
                                : Icons.shopping_bag_outlined,
                            size: 18,
                            color: o.channel == 'POS'
                                ? Colors.orange.shade700
                                : Colors.blue.shade700,
                          ),
                        ),
                        title: Row(
                          children: [
                            Text(
                              '#${o.id.length >= 8 ? o.id.substring(0, 8) : o.id}',
                              style: const TextStyle(fontFamily: 'monospace'),
                            ),
                            const SizedBox(width: 8),
                            _ChannelBadge(o.channel),
                            if (o.paymentMethod != null) ...[
                              const SizedBox(width: 6),
                              _PaymentMethodBadge(
                                  o.paymentMethod!, o.fulfilmentType),
                            ],
                          ],
                        ),
                        subtitle: Text(AppFormat.dateTime(o.createdAt)),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (wide) ...[
                              _StatusBadge(o.status),
                              const SizedBox(width: 16),
                              SizedBox(
                                width: 90,
                                child: Text(
                                  AppFormat.money(o.total, currencyCode: o.currency),
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleSmall
                                      ?.copyWith(fontWeight: FontWeight.bold),
                                  textAlign: TextAlign.end,
                                ),
                              ),
                            ] else
                              Column(
                                mainAxisSize: MainAxisSize.min,
                                crossAxisAlignment: CrossAxisAlignment.end,
                                children: [
                                  _StatusBadge(o.status),
                                  const SizedBox(height: 4),
                                  Text(
                                      AppFormat.money(o.total, currencyCode: o.currency),
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleSmall
                                          ?.copyWith(
                                              fontWeight: FontWeight.bold)),
                                ],
                              ),
                            _OrderActionsMenu(
                              status: o.status,
                              paymentMethod: o.paymentMethod,
                              onAction: (a) => _action(o, a),
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                );
              });
          }),
        ),
      ],
    );
  }

  Future<void> _action(OrderSummary o, String action) async {
    if (action == 'collect') {
      await showDialog<void>(
        context: context,
        builder: (_) => _CollectPaymentDialog(
          order: o,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'return') {
      await showDialog<void>(
        context: context,
        builder: (_) => _ReturnDialog(
          orderId: o.id,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'receipt') {
      try {
        await ref.read(apiClientProvider).dio.post(
          '/${ApiConstants.order}/admin/orders/${o.id}/receipts',
          data: {'receiptType': 'SALE', 'printCount': 1},
        );
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Receipt generated.')),
        );
      } catch (e) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content:
              Text(friendlyError(e, fallback: 'Could not generate receipt.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
      return;
    }
    String? reason;
    if (action == 'cancel') {
      reason = await _promptReason(context);
      if (reason == null) return; // dialog dismissed
    }
    try {
      await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/${o.id}/$action',
            data: action == 'cancel' ? {'reason': reason} : null,
          );
      ref.read(ordersPaginationProvider(_filter).notifier).refresh();
      ref.invalidate(recentOrdersProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Order ${_pastTense(action)}.')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(friendlyError(e, fallback: 'Could not $action order.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  String _pastTense(String action) => switch (action) {
        'confirm' => 'confirmed',
        'fulfil' => 'fulfilled',
        'cancel' => 'cancelled',
        _ => action,
      };

  Future<String?> _promptReason(BuildContext context) async {
    final ctrl = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Cancel order?'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: 'Reason (optional)',
            hintText: 'e.g. customer request',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Keep order'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
            child: const Text('Cancel order'),
          ),
        ],
      ),
    );
    ctrl.dispose();
    return reason;
  }
}

/// Creates a return against an order (order-svc) and records the matching
/// money refund (payment-svc) for CASH/CARD refunds.
class _ReturnDialog extends ConsumerStatefulWidget {
  final String orderId;
  final VoidCallback onDone;
  const _ReturnDialog({required this.orderId, required this.onDone});

  @override
  ConsumerState<_ReturnDialog> createState() => _ReturnDialogState();
}

class _ReturnDialogState extends ConsumerState<_ReturnDialog> {
  final _reasonCtrl = TextEditingController();
  final Map<String, int> _returnQty = {}; // variantId → qty to return
  String _method = 'CARD';
  bool _submitting = false;
  String? _error;

  static const _methods = ['CARD', 'CASH', 'STORE_CREDIT'];

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  double _previewRefund(List<OrderLine> lines) {
    var sum = 0.0;
    for (final l in lines) {
      sum += (_returnQty[l.variantId] ?? 0) * l.unitPrice;
    }
    return sum;
  }

  Future<void> _submit(OrderDetail order) async {
    final items = [
      for (final entry in _returnQty.entries)
        if (entry.value > 0)
          {'variantId': entry.key, 'qty': entry.value, 'condition': 'GOOD'},
    ];
    if (items.isEmpty) {
      setState(() => _error = 'Select at least one item to return.');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    try {
      // 1. Record the return (computes the refund amount server-side).
      final retResp = await dio.post(
        '/${ApiConstants.order}/orders/${widget.orderId}/returns',
        data: {
          'reason': _reasonCtrl.text.trim().isEmpty
              ? 'Customer return'
              : _reasonCtrl.text.trim(),
          'refundMethod': _method,
          'items': items,
        },
      );
      final ret = retResp.data['data'] as Map<String, dynamic>;
      final refundAmount = (ret['refundAmount'] as num?)?.toDouble() ?? 0;

      // 2. For a money refund, record it against the original payment tender.
      String? refundNote;
      if (_method != 'STORE_CREDIT' && refundAmount > 0) {
        final paymentId = await _findPaymentId(dio);
        if (paymentId != null) {
          await dio.post(
            '/${ApiConstants.payment}/payments/by-order/${widget.orderId}/refunds',
            data: {
              'paymentId': paymentId,
              'amount': refundAmount,
              'method': _method,
              'reason': _reasonCtrl.text.trim().isEmpty
                  ? 'Customer return'
                  : _reasonCtrl.text.trim(),
            },
          );
        } else {
          refundNote = ' (no captured payment found — refund not posted)';
        }
      }

      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text(
                'Return recorded · ${order.currency} ${refundAmount.toStringAsFixed(2)} '
                '${_method == 'STORE_CREDIT' ? 'as store credit' : 'refunded'}'
                '${refundNote ?? ''}')),
      );
    } catch (e) {
      setState(() {
        _submitting = false;
        _error = _friendly(e);
      });
    }
  }

  /// First captured tender id for the order, to refund against.
  Future<String?> _findPaymentId(Dio dio) async {
    try {
      final resp = await dio
          .get('/${ApiConstants.payment}/payments/by-order/${widget.orderId}');
      final list = (resp.data['data'] as List?) ?? [];
      for (final t in list) {
        final m = t as Map<String, dynamic>;
        final status = (m['status'] as String? ?? '').toUpperCase();
        if (status == 'CAPTURED' || status == 'AUTHORIZED' || status.isEmpty) {
          return m['id'] as String?;
        }
      }
      return list.isNotEmpty ? (list.first['id'] as String?) : null;
    } catch (_) {
      return null;
    }
  }

  String _friendly(Object e) {
    if (e is DioException) {
      final status = e.response?.statusCode;
      if (status == 409) return 'Refund exceeds the captured payment.';
      if (status == 404) return 'Order or item not found.';
    }
    return friendlyError(e, fallback: 'Could not process return.');
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final detailAsync = ref.watch(orderDetailProvider(widget.orderId));
    final returnsAsync = ref.watch(orderReturnsProvider(widget.orderId));

    return AlertDialog(
      title: const Text('Return / Refund'),
      content: SizedBox(
        width: 460,
        child: detailAsync.when(
          loading: () => const SizedBox(
              height: 160, child: LoadingView(label: 'Loading order…')),
          error: (e, _) => SizedBox(
            height: 160,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load order.'),
              onRetry: () =>
                  ref.invalidate(orderDetailProvider(widget.orderId)),
            ),
          ),
          data: (order) {
            final preview = _previewRefund(order.items);
            final labels = ref
                    .watch(variantLabelsProvider(
                        variantIdsKey(order.items.map((l) => l.variantId))))
                    .value ??
                const <String, VariantLabel>{};
            return SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(_error!,
                          style: TextStyle(color: cs.onErrorContainer)),
                    ),
                    const SizedBox(height: 12),
                  ],
                  // Existing returns (if any).
                  returnsAsync.maybeWhen(
                    data: (returns) => returns.isEmpty
                        ? const SizedBox.shrink()
                        : Padding(
                            padding: const EdgeInsets.only(bottom: 12),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Previous returns',
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelLarge),
                                for (final r in returns)
                                  Text(
                                    '· ${order.currency} ${r.refundAmount.toStringAsFixed(2)} '
                                    'via ${r.refundMethod} (${r.status})',
                                    style: TextStyle(
                                        fontSize: 12, color: cs.outline),
                                  ),
                                const Divider(),
                              ],
                            ),
                          ),
                    orElse: () => const SizedBox.shrink(),
                  ),
                  Text('Select quantities to return',
                      style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 8),
                  for (final line in order.items)
                    _ReturnLineRow(
                      line: line,
                      name: variantDisplayName(line.variantId, labels),
                      sku: variantSku(line.variantId, labels),
                      currency: order.currency,
                      value: _returnQty[line.variantId] ?? 0,
                      onChanged: (v) =>
                          setState(() => _returnQty[line.variantId] = v),
                    ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _reasonCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Reason',
                      hintText: 'e.g. damaged, wrong size',
                    ),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _method,
                    decoration: const InputDecoration(labelText: 'Refund method'),
                    items: _methods
                        .map((m) => DropdownMenuItem(
                            value: m, child: Text(m.replaceAll('_', ' '))))
                        .toList(),
                    onChanged: (v) => setState(() => _method = v!),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Text('Refund total',
                          style: Theme.of(context).textTheme.titleMedium),
                      const Spacer(),
                      Text('${order.currency} ${preview.toStringAsFixed(2)}',
                          style: Theme.of(context)
                              .textTheme
                              .titleMedium
                              ?.copyWith(fontWeight: FontWeight.bold)),
                    ],
                  ),
                ],
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _submitting
              ? null
              : () {
                  final order = detailAsync.value;
                  if (order != null) _submit(order);
                },
          child: _submitting
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : const Text('Process return'),
        ),
      ],
    );
  }
}

class _ReturnLineRow extends StatelessWidget {
  final OrderLine line;
  final String name;
  final String sku;
  final String currency;
  final int value;
  final ValueChanged<int> onChanged;
  const _ReturnLineRow({
    required this.line,
    required this.name,
    required this.sku,
    required this.currency,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final maxQty = line.qty.toInt();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name,
                    style: const TextStyle(
                        fontWeight: FontWeight.w600, fontSize: 13)),
                Text(
                    '${sku.isNotEmpty ? '$sku · ' : ''}ordered $maxQty · $currency ${line.unitPrice.toStringAsFixed(2)}',
                    style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context).colorScheme.outline)),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.remove_circle_outline),
            tooltip: 'Decrease quantity',
            onPressed: value > 0 ? () => onChanged(value - 1) : null,
          ),
          Text('$value', style: const TextStyle(fontWeight: FontWeight.bold)),
          IconButton(
            icon: const Icon(Icons.add_circle_outline),
            tooltip: 'Increase quantity',
            onPressed: value < maxQty ? () => onChanged(value + 1) : null,
          ),
        ],
      ),
    );
  }
}

/// Per-order action menu — options depend on the current status.
class _OrderActionsMenu extends StatelessWidget {
  final String status;
  final String? paymentMethod;
  final void Function(String action) onAction;
  const _OrderActionsMenu(
      {required this.status, this.paymentMethod, required this.onAction});

  @override
  Widget build(BuildContext context) {
    final s = status.toUpperCase();
    final items = <PopupMenuEntry<String>>[];
    if (s == 'PENDING') {
      items.add(const PopupMenuItem(
          value: 'confirm',
          child: Row(children: [
            Icon(Icons.check_circle_outline, size: 18),
            SizedBox(width: 8),
            Text('Confirm'),
          ])));
    }
    if (s == 'CONFIRMED') {
      items.add(const PopupMenuItem(
          value: 'fulfil',
          child: Row(children: [
            Icon(Icons.local_shipping_outlined, size: 18),
            SizedBox(width: 8),
            Text('Mark fulfilled'),
          ])));
    }
    // COD / pay-at-pickup settlement: record the tender when the goods change hands. Shown for
    // any live order — the dialog itself computes what's still outstanding and refuses
    // double-collection.
    if (s == 'PENDING' || s == 'CONFIRMED' || s == 'FULFILLED') {
      items.add(const PopupMenuItem(
          value: 'collect',
          child: Row(children: [
            Icon(Icons.point_of_sale_outlined, size: 18),
            SizedBox(width: 8),
            Text('Collect payment'),
          ])));
    }
    if (s == 'PENDING' || s == 'CONFIRMED') {
      final cs = Theme.of(context).colorScheme;
      items.add(PopupMenuItem(
          value: 'cancel',
          child: Row(children: [
            Icon(Icons.cancel_outlined, size: 18, color: cs.error),
            const SizedBox(width: 8),
            Text('Cancel', style: TextStyle(color: cs.error)),
          ])));
    }
    // Returns are allowed on orders that weren't cancelled/voided.
    if (s != 'CANCELLED' && s != 'VOIDED') {
      items.add(const PopupMenuItem(
          value: 'return',
          child: Row(children: [
            Icon(Icons.assignment_return_outlined, size: 18),
            SizedBox(width: 8),
            Text('Return / Refund'),
          ])));
    }
    items.add(const PopupMenuItem(
        value: 'receipt',
        child: Row(children: [
          Icon(Icons.receipt_outlined, size: 18),
          SizedBox(width: 8),
          Text('Print receipt'),
        ])));
    if (items.isEmpty) {
      return const SizedBox(width: 8);
    }
    return PopupMenuButton<String>(
      icon: const Icon(Icons.more_vert),
      tooltip: 'Actions',
      itemBuilder: (_) => items,
      onSelected: onAction,
    );
  }
}

class _ChannelBadge extends StatelessWidget {
  final String channel;
  const _ChannelBadge(this.channel);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: channel == 'POS'
            ? Colors.orange.shade50
            : Colors.blue.shade50,
        borderRadius: BorderRadius.circular(4),
        border: Border.all(
          color: channel == 'POS'
              ? Colors.orange.shade200
              : Colors.blue.shade200,
        ),
      ),
      child: Text(
        channel,
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.bold,
          color:
              channel == 'POS' ? Colors.orange.shade800 : Colors.blue.shade800,
        ),
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  final String status;
  const _StatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    Color bg;
    Color fg;
    switch (status.toUpperCase()) {
      case 'PLACED':
        bg = Colors.blue.shade100;
        fg = Colors.blue.shade800;
        break;
      case 'CONFIRMED':
        bg = Colors.green.shade100;
        fg = Colors.green.shade800;
        break;
      case 'FULFILLED':
        bg = Colors.teal.shade100;
        fg = Colors.teal.shade800;
        break;
      case 'CANCELLED':
      case 'VOIDED':
        bg = Colors.red.shade100;
        fg = Colors.red.shade800;
        break;
      default:
        bg = Colors.grey.shade200;
        fg = Colors.grey.shade700;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration:
          BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Text(status,
          style: TextStyle(
              fontSize: 11, fontWeight: FontWeight.w600, color: fg)),
    );
  }
}

/// How the customer said they'd pay, contextualised by fulfilment: CASH + DELIVERY reads
/// "COD", CASH + PICKUP reads "Cash at pickup", online tenders read as themselves.
class _PaymentMethodBadge extends StatelessWidget {
  final String method;
  final String fulfilmentType;
  const _PaymentMethodBadge(this.method, this.fulfilmentType);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final m = method.toUpperCase();
    final label = m == 'CASH'
        ? (fulfilmentType.toUpperCase() == 'DELIVERY' ? 'COD' : 'CASH')
        : m;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: cs.secondaryContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(label,
          style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: cs.onSecondaryContainer)),
    );
  }
}

/// Settle a pay-later (COD / pay-at-pickup) order at handover: shows what's already been
/// captured, and records one tender for the outstanding balance via payment-svc. The captured
/// tender emits PaymentCaptured, which confirms a PENDING order automatically.
class _CollectPaymentDialog extends ConsumerStatefulWidget {
  final OrderSummary order;
  final VoidCallback onDone;
  const _CollectPaymentDialog({required this.order, required this.onDone});

  @override
  ConsumerState<_CollectPaymentDialog> createState() =>
      _CollectPaymentDialogState();
}

class _CollectPaymentDialogState extends ConsumerState<_CollectPaymentDialog> {
  double? _paid; // null while loading
  late String _method;
  bool _submitting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final declared = widget.order.paymentMethod?.toUpperCase();
    _method = const ['CASH', 'CARD', 'UPI', 'WALLET'].contains(declared)
        ? declared!
        : 'CASH';
    _loadPaid();
  }

  Future<void> _loadPaid() async {
    try {
      final resp = await ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.payment}/payments/by-order/${widget.order.id}');
      final data = (resp.data['data'] as List?) ?? [];
      double paid = 0;
      for (final t in data) {
        final m = t as Map<String, dynamic>;
        final status = (m['status'] as String? ?? '').toUpperCase();
        if (status == 'CAPTURED' || status.isEmpty) {
          paid += (m['amount'] as num?)?.toDouble() ?? 0;
        }
      }
      if (mounted) setState(() => _paid = paid);
    } catch (_) {
      // Payment history unavailable — assume nothing collected; the server-side
      // idempotency key still prevents double capture on retry.
      if (mounted) setState(() => _paid = 0);
    }
  }

  Future<void> _collect(double outstanding) async {
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/payments',
        data: {
          'orderId': widget.order.id,
          if (widget.order.storeId.isNotEmpty) 'storeId': widget.order.storeId,
          'amount': outstanding,
          'method': _method,
          'reference': 'ORDER_HANDOVER',
        },
        options: Options(headers: {
          'Idempotency-Key':
              'collect-${widget.order.id}-${outstanding.toStringAsFixed(2)}'
        }),
      );
      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(
              '${AppFormat.money(outstanding, currencyCode: widget.order.currency)} collected by $_method.')));
    } catch (e) {
      setState(() {
        _submitting = false;
        _error = friendlyError(e, fallback: 'Could not record payment.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final o = widget.order;
    final shortId = o.id.length >= 8 ? o.id.substring(0, 8) : o.id;
    final paid = _paid;
    final outstanding = paid == null ? null : (o.total - paid);
    return AlertDialog(
      title: Text('Collect payment · #$shortId'),
      content: SizedBox(
        width: 380,
        child: paid == null
            ? const SizedBox(
                height: 80, child: Center(child: CircularProgressIndicator()))
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(_error!,
                          style: TextStyle(color: cs.onErrorContainer)),
                    ),
                    const SizedBox(height: 12),
                  ],
                  Text('Order total: '
                      '${AppFormat.money(o.total, currencyCode: o.currency)}'),
                  if (paid > 0)
                    Text('Already collected: '
                        '${AppFormat.money(paid, currencyCode: o.currency)}'),
                  const SizedBox(height: 8),
                  if (outstanding! <= 0)
                    Row(children: [
                      Icon(Icons.check_circle_outline, color: cs.primary),
                      const SizedBox(width: 8),
                      const Text('This order is already paid in full.'),
                    ])
                  else ...[
                    Text(
                        'Outstanding: ${AppFormat.money(outstanding, currencyCode: o.currency)}',
                        style: const TextStyle(fontWeight: FontWeight.bold)),
                    const SizedBox(height: 12),
                    SegmentedButton<String>(
                      segments: const [
                        ButtonSegment(value: 'CASH', label: Text('CASH')),
                        ButtonSegment(value: 'CARD', label: Text('CARD')),
                        ButtonSegment(value: 'UPI', label: Text('UPI')),
                        ButtonSegment(value: 'WALLET', label: Text('WALLET')),
                      ],
                      selected: {_method},
                      onSelectionChanged: (s) =>
                          setState(() => _method = s.first),
                    ),
                  ],
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.pop(context),
          child: const Text('Close'),
        ),
        if (outstanding != null && outstanding > 0)
          FilledButton.icon(
            onPressed: _submitting ? null : () => _collect(outstanding),
            icon: _submitting
                ?  SizedBox(
                    height: 16,
                    width: 16,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                : const Icon(Icons.point_of_sale_outlined),
            label: Text(
                'Collect ${AppFormat.money(outstanding, currencyCode: o.currency)}'),
          ),
      ],
    );
  }
}
