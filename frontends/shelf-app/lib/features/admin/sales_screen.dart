import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'sales_providers.dart';
import 'widgets/variant_picker.dart';

class SalesScreen extends ConsumerWidget {
  const SalesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return DefaultTabController(
      length: 3,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
            child: Text('Sales tools',
                style: Theme.of(context).textTheme.headlineMedium),
          ),
          const TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [
              Tab(text: 'Gift Cards'),
              Tab(text: 'Layaways'),
              Tab(text: 'Special Orders'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _GiftCardsTab(),
                _LayawaysTab(),
                _SpecialOrdersTab(),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ── Gift cards ───────────────────────────────────────────────────────────────

class _GiftCardsTab extends ConsumerStatefulWidget {
  const _GiftCardsTab();

  @override
  ConsumerState<_GiftCardsTab> createState() => _GiftCardsTabState();
}

class _GiftCardsTabState extends ConsumerState<_GiftCardsTab> {
  final _codeCtrl = TextEditingController();
  GiftCard? _card;
  List<GiftCardTxn> _txns = [];
  bool _loading = false;
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _lookup() async {
    final code = _codeCtrl.text.trim();
    if (code.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final dio = ref.read(apiClientProvider).dio;
      final cardResp = await dio.get('/${ApiConstants.order}/gift-cards/$code');
      final txnResp =
          await dio.get('/${ApiConstants.order}/gift-cards/$code/transactions');
      setState(() {
        _card = GiftCard.fromJson(cardResp.data['data'] as Map<String, dynamic>);
        _txns = ((txnResp.data['data'] as List?) ?? [])
            .map((e) => GiftCardTxn.fromJson(e as Map<String, dynamic>))
            .toList();
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _loading = false;
        _card = null;
        _error = e.toString().contains('404')
            ? 'No gift card with that code.'
            : 'Lookup failed: $e';
      });
    }
  }

  Future<void> _reloadOrRedeem(String action) async {
    if (_submitting) return;
    final card = _card;
    if (card == null) return;
    final amount = await _amountDialog(
        context, action == 'reload' ? 'Reload gift card' : 'Redeem gift card');
    if (amount == null) return;
    setState(() => _submitting = true);
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/gift-cards/${card.code}/$action',
        data: {'amount': amount},
      );
      await _lookup();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Failed: $e'),
          backgroundColor: Theme.of(context).colorScheme.error));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _codeCtrl,
                decoration: const InputDecoration(
                  labelText: 'Gift card code',
                  prefixIcon: Icon(Icons.card_giftcard),
                ),
                onSubmitted: (_) => _lookup(),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(onPressed: _lookup, child: const Text('Look up')),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              onPressed: () => showDialog(
                  context: context, builder: (_) => const _IssueGiftCardDialog()),
              icon: const Icon(Icons.add),
              label: const Text('Issue'),
            ),
          ],
        ),
        const SizedBox(height: 16),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null)
          Text(_error!, style: TextStyle(color: cs.error)),
        if (_card != null) ...[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_card!.code,
                      style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 18,
                          fontWeight: FontWeight.bold)),
                  const SizedBox(height: 8),
                  Text(
                      'Balance: ${_card!.currency} ${_card!.currentBalance.toStringAsFixed(2)}',
                      style: Theme.of(context).textTheme.titleMedium),
                  Text('Status: ${_card!.status}',
                      style: TextStyle(color: cs.outline)),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      OutlinedButton.icon(
                        onPressed: _submitting ? null : () => _reloadOrRedeem('reload'),
                        icon: const Icon(Icons.add, size: 18),
                        label: const Text('Reload'),
                      ),
                      const SizedBox(width: 8),
                      OutlinedButton.icon(
                        onPressed: _submitting ? null : () => _reloadOrRedeem('redeem'),
                        icon: const Icon(Icons.remove, size: 18),
                        label: const Text('Redeem'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text('Transactions', style: Theme.of(context).textTheme.labelLarge),
          for (final t in _txns)
            ListTile(
              dense: true,
              leading: Icon(
                  t.amount >= 0 ? Icons.arrow_upward : Icons.arrow_downward,
                  size: 16,
                  color: t.amount >= 0 ? Colors.green : Colors.red),
              title: Text(t.txType),
              trailing: Text(
                  '${t.amount.toStringAsFixed(2)} → ${t.balanceAfter.toStringAsFixed(2)}'),
            ),
        ],
      ],
    );
  }
}

class _IssueGiftCardDialog extends ConsumerStatefulWidget {
  const _IssueGiftCardDialog();

  @override
  ConsumerState<_IssueGiftCardDialog> createState() =>
      _IssueGiftCardDialogState();
}

class _IssueGiftCardDialogState extends ConsumerState<_IssueGiftCardDialog> {
  String? _storeId;
  final _amountCtrl = TextEditingController();
  String _currency = 'INR';
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _amountCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final amount = double.tryParse(_amountCtrl.text.trim());
    if (_storeId == null || amount == null || amount <= 0) {
      setState(() => _error = 'Pick a store and enter an amount.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/gift-cards',
        data: {'storeId': _storeId, 'amount': amount, 'currency': _currency},
      );
      final card = resp.data['data'] as Map<String, dynamic>;
      if (!mounted) return;
      Navigator.pop(context);
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: const Icon(Icons.card_giftcard, size: 36),
          title: const Text('Gift card issued'),
          content: SelectableText(card['code'] as String? ?? '',
              style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 20,
                  fontWeight: FontWeight.bold)),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
          ],
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not issue: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Issue gift card'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null) ...[
              Text(_error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
              const SizedBox(height: 8),
            ],
            storesAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text('Stores failed: $e'),
              data: (stores) => DropdownButtonFormField<String>(
                value: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Store *'),
                items: [
                  for (final s in stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _amountCtrl,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Amount'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: DropdownButtonFormField<String>(
                    value: _currency,
                    decoration: const InputDecoration(labelText: 'Currency'),
                    items: const [
                      DropdownMenuItem(value: 'INR', child: Text('INR')),
                      DropdownMenuItem(value: 'USD', child: Text('USD')),
                      DropdownMenuItem(value: 'GBP', child: Text('GBP')),
                    ],
                    onChanged: (v) => setState(() => _currency = v!),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
      actions: _actions(context, _loading, _submit, 'Issue'),
    );
  }
}

// ── Layaways ─────────────────────────────────────────────────────────────────

class _LayawaysTab extends ConsumerStatefulWidget {
  const _LayawaysTab();

  @override
  ConsumerState<_LayawaysTab> createState() => _LayawaysTabState();
}

class _LayawaysTabState extends ConsumerState<_LayawaysTab> {
  final _idCtrl = TextEditingController();
  Layaway? _layaway;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _idCtrl.dispose();
    super.dispose();
  }

  Future<void> _lookup([String? id]) async {
    final lid = (id ?? _idCtrl.text).trim();
    if (lid.isEmpty) return;
    _idCtrl.text = lid;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp =
          await ref.read(apiClientProvider).dio.get('/${ApiConstants.order}/layaways/$lid');
      setState(() {
        _layaway = Layaway.fromJson(resp.data['data'] as Map<String, dynamic>);
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _loading = false;
        _layaway = null;
        _error = e.toString().contains('404') ? 'No layaway with that id.' : '$e';
      });
    }
  }

  Future<void> _action(String action) async {
    final l = _layaway;
    if (l == null) return;
    final dio = ref.read(apiClientProvider).dio;
    try {
      if (action == 'deposit') {
        final amount = await _amountDialog(context, 'Add deposit');
        if (amount == null) return;
        await dio.post('/${ApiConstants.order}/layaways/${l.id}/deposits',
            data: {'amount': amount, 'paymentMethod': 'CASH'});
      } else {
        await dio.post('/${ApiConstants.order}/layaways/${l.id}/$action',
            data: action == 'cancel' ? {'reason': 'Cancelled by staff'} : null);
      }
      await _lookup(l.id);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Failed: $e'),
          backgroundColor: Theme.of(context).colorScheme.error));
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final l = _layaway;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _idCtrl,
                decoration: const InputDecoration(
                    labelText: 'Layaway id', prefixIcon: Icon(Icons.search)),
                onSubmitted: (_) => _lookup(),
              ),
            ),
            const SizedBox(width: 8),
            FilledButton(onPressed: () => _lookup(), child: const Text('Look up')),
            const SizedBox(width: 8),
            OutlinedButton.icon(
              onPressed: () => showDialog<String>(
                context: context,
                builder: (_) => const _CreateLayawayDialog(),
              ).then((newId) {
                if (newId != null) _lookup(newId);
              }),
              icon: const Icon(Icons.add),
              label: const Text('New'),
            ),
          ],
        ),
        const SizedBox(height: 16),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null) Text(_error!, style: TextStyle(color: cs.error)),
        if (l != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('#${l.id.length >= 8 ? l.id.substring(0, 8) : l.id}',
                      style: const TextStyle(fontFamily: 'monospace')),
                  const SizedBox(height: 8),
                  Text('Total: ${l.totalAmount.toStringAsFixed(2)}'),
                  Text('Paid: ${l.depositPaid.toStringAsFixed(2)}'),
                  Text('Balance: ${l.balance.toStringAsFixed(2)}',
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                  Text('Status: ${l.status}', style: TextStyle(color: cs.outline)),
                  const SizedBox(height: 12),
                  if (l.status.toUpperCase() == 'ACTIVE')
                    Wrap(
                      spacing: 8,
                      children: [
                        OutlinedButton(
                            onPressed: () => _action('deposit'),
                            child: const Text('Add deposit')),
                        OutlinedButton(
                            onPressed: () => _action('complete'),
                            child: const Text('Complete')),
                        OutlinedButton(
                            onPressed: () => _action('cancel'),
                            child: const Text('Cancel')),
                      ],
                    ),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

class _CreateLayawayDialog extends ConsumerStatefulWidget {
  const _CreateLayawayDialog();

  @override
  ConsumerState<_CreateLayawayDialog> createState() =>
      _CreateLayawayDialogState();
}

class _CreateLayawayDialogState extends ConsumerState<_CreateLayawayDialog> {
  String? _storeId;
  final _depositCtrl = TextEditingController();
  final List<Map<String, dynamic>> _items = [];
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _depositCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final deposit = double.tryParse(_depositCtrl.text.trim());
    if (_storeId == null || _items.isEmpty || deposit == null || deposit <= 0) {
      setState(() => _error = 'Pick a store, add items, and enter a deposit.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/layaways',
        data: {
          'storeId': _storeId,
          'items': _items,
          'initialDeposit': deposit,
          'paymentMethod': 'CASH',
        },
      );
      final id = (resp.data['data'] as Map<String, dynamic>)['id'] as String?;
      if (!mounted) return;
      Navigator.pop(context, id);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not create layaway: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('New layaway'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Stores failed: $e'),
                data: (stores) => DropdownButtonFormField<String>(
                  value: _storeId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Store *'),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(value: s.id, child: Text(s.name)),
                  ],
                  onChanged: (v) => setState(() => _storeId = v),
                ),
              ),
              const SizedBox(height: 12),
              _LineItemsEditor(
                items: _items,
                onChanged: () => setState(() {}),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _depositCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Initial deposit'),
              ),
            ],
          ),
        ),
      ),
      actions: _actions(context, _loading, _submit, 'Create'),
    );
  }
}

// ── Special orders ───────────────────────────────────────────────────────────

class _SpecialOrdersTab extends ConsumerWidget {
  const _SpecialOrdersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(specialOrdersProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Row(
            children: [
              const Spacer(),
              FilledButton.icon(
                onPressed: () => showDialog(
                    context: context,
                    builder: (_) => const _CreateSpecialOrderDialog()),
                icon: const Icon(Icons.add),
                label: const Text('New special order'),
              ),
            ],
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading special orders…'),
            error: (e, _) => ErrorView(
              message: 'Could not load special orders.\n$e',
              onRetry: () => ref.invalidate(specialOrdersProvider),
            ),
            data: (orders) {
              if (orders.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.assignment_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 12),
                      const Text('No special orders yet'),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: orders.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final o = orders[i];
                  return Card(
                    child: ListTile(
                      title: Text(o.customerName ?? 'Special order',
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text([
                        '${o.currency} ${o.total.toStringAsFixed(2)}',
                        if (o.requestedDeliveryDate != null)
                          'due ${o.requestedDeliveryDate}',
                      ].join(' · ')),
                      trailing: _SpecialOrderActions(order: o),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class _SpecialOrderActions extends ConsumerWidget {
  final SpecialOrder order;
  const _SpecialOrderActions({required this.order});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = order.status.toUpperCase();
    final actions = <String>[];
    if (s == 'PENDING' || s == 'DRAFT') actions.add('confirm');
    if (s == 'CONFIRMED') actions.add('fulfil');
    if (s != 'CANCELLED' && s != 'FULFILLED') actions.add('cancel');
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _StatusChip(order.status),
        if (actions.isNotEmpty)
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            itemBuilder: (_) => [
              for (final a in actions)
                PopupMenuItem(value: a, child: Text(a[0].toUpperCase() + a.substring(1))),
            ],
            onSelected: (a) async {
              try {
                await ref.read(apiClientProvider).dio.post(
                    '/${ApiConstants.order}/admin/special-orders/${order.id}/$a');
                ref.invalidate(specialOrdersProvider);
              } catch (e) {
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                    content: Text('Failed: $e'),
                    backgroundColor: Theme.of(context).colorScheme.error));
              }
            },
          ),
      ],
    );
  }
}

class _CreateSpecialOrderDialog extends ConsumerStatefulWidget {
  const _CreateSpecialOrderDialog();

  @override
  ConsumerState<_CreateSpecialOrderDialog> createState() =>
      _CreateSpecialOrderDialogState();
}

class _CreateSpecialOrderDialogState
    extends ConsumerState<_CreateSpecialOrderDialog> {
  String? _storeId;
  final _nameCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final List<Map<String, dynamic>> _items = [];
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_storeId == null || _items.isEmpty) {
      setState(() => _error = 'Pick a store and add at least one item.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/admin/special-orders',
        data: {
          'storeId': _storeId,
          'customerName': _nameCtrl.text.trim(),
          'customerPhone': _phoneCtrl.text.trim(),
          'items': _items,
        },
        options: Options(headers: {
          'Idempotency-Key': 'so-${DateTime.now().millisecondsSinceEpoch}'
        }),
      );
      if (!mounted) return;
      ref.invalidate(specialOrdersProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not create: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('New special order'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text('Stores failed: $e'),
                data: (stores) => DropdownButtonFormField<String>(
                  value: _storeId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Store *'),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(value: s.id, child: Text(s.name)),
                  ],
                  onChanged: (v) => setState(() => _storeId = v),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _nameCtrl,
                decoration: const InputDecoration(labelText: 'Customer name'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _phoneCtrl,
                decoration: const InputDecoration(labelText: 'Customer phone'),
              ),
              const SizedBox(height: 12),
              _LineItemsEditor(items: _items, onChanged: () => setState(() {})),
            ],
          ),
        ),
      ),
      actions: _actions(context, _loading, _submit, 'Create'),
    );
  }
}

// ── Shared: line-items editor (variant + qty + price) ────────────────────────

class _LineItemsEditor extends ConsumerStatefulWidget {
  final List<Map<String, dynamic>> items;
  final VoidCallback onChanged;
  const _LineItemsEditor({required this.items, required this.onChanged});

  @override
  ConsumerState<_LineItemsEditor> createState() => _LineItemsEditorState();
}

class _LineItemsEditorState extends ConsumerState<_LineItemsEditor> {
  String? _productId;
  String? _variantId;
  final _qtyCtrl = TextEditingController(text: '1');
  final _priceCtrl = TextEditingController();

  @override
  void dispose() {
    _qtyCtrl.dispose();
    _priceCtrl.dispose();
    super.dispose();
  }

  void _add() {
    final qty = double.tryParse(_qtyCtrl.text.trim());
    final price = double.tryParse(_priceCtrl.text.trim());
    if (_variantId == null || qty == null || qty <= 0 || price == null || price <= 0) {
      return;
    }
    widget.items.add({'variantId': _variantId, 'qty': qty, 'unitPrice': price});
    _variantId = null;
    _priceCtrl.clear();
    _qtyCtrl.text = '1';
    widget.onChanged();
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Items', style: Theme.of(context).textTheme.labelLarge),
        for (final it in widget.items)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: Text(
                '${(it['variantId'] as String).substring(0, 8)}… × ${it['qty']}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            trailing: IconButton(
              icon: Icon(Icons.delete_outline, size: 18, color: cs.error),
              onPressed: () {
                widget.items.remove(it);
                widget.onChanged();
                setState(() {});
              },
            ),
          ),
        VariantPicker(
          productId: _productId,
          variantId: _variantId,
          onProduct: (p) => setState(() {
            _productId = p;
            _variantId = null;
          }),
          onVariant: (v) => setState(() => _variantId = v),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _qtyCtrl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Qty', isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _priceCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Price', isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filledTonal(
                onPressed: _add, icon: const Icon(Icons.add)),
          ],
        ),
      ],
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String status;
  const _StatusChip(this.status);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
          color: Colors.grey.shade200, borderRadius: BorderRadius.circular(12)),
      child: Text(status,
          style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600)),
    );
  }
}

// ── Shared helpers ───────────────────────────────────────────────────────────

Future<double?> _amountDialog(BuildContext context, String title) async {
  final ctrl = TextEditingController();
  final amount = await showDialog<double>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: TextField(
        controller: ctrl,
        autofocus: true,
        keyboardType: const TextInputType.numberWithOptions(decimal: true),
        decoration: const InputDecoration(labelText: 'Amount'),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            final v = double.tryParse(ctrl.text.trim());
            if (v != null && v > 0) Navigator.pop(ctx, v);
          },
          child: const Text('OK'),
        ),
      ],
    ),
  );
  ctrl.dispose();
  return amount;
}

List<Widget> _actions(
    BuildContext context, bool loading, VoidCallback onSubmit, String label) {
  return [
    TextButton(
      onPressed: loading ? null : () => Navigator.pop(context),
      child: const Text('Cancel'),
    ),
    FilledButton(
      onPressed: loading ? null : onSubmit,
      child: loading
          ? const SizedBox(
              height: 18,
              width: 18,
              child:
                  CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
          : Text(label),
    ),
  ];
}
