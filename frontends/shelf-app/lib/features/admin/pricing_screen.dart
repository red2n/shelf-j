import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'pricing_providers.dart';
import 'providers/admin_providers.dart';
import 'widgets/variant_picker.dart';

class PricingScreen extends ConsumerWidget {
  const PricingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return DefaultTabController(
      length: 4,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
            child: Text(
              'Pricing',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
          ),
          const TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [
              Tab(text: 'Price Lists'),
              Tab(text: 'Promotions'),
              Tab(text: 'VAT Rates'),
              Tab(text: 'VAT Return'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _PriceListsTab(),
                _PromotionsTab(),
                _VatRatesTab(),
                _VatReturnTab(),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

Widget _addBar(BuildContext context, String label, VoidCallback onPressed) {
  return Padding(
    padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
    child: Row(
      children: [
        const Spacer(),
        FilledButton.icon(
          onPressed: onPressed,
          icon: const Icon(Icons.add),
          label: Text(label),
        ),
      ],
    ),
  );
}

// ── Price lists ──────────────────────────────────────────────────────────────

class _PriceListsTab extends ConsumerWidget {
  const _PriceListsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(priceListsProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        _addBar(
          context,
          'New price list',
          () => showDialog(
            context: context,
            builder: (_) => const _PriceListDialog(),
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading price lists…'),
            error: (e, _) => ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load price lists.',
              ),
              onRetry: () => ref.invalidate(priceListsProvider),
            ),
            data: (lists) {
              if (lists.isEmpty) {
                return _empty(
                  cs,
                  Icons.price_change_outlined,
                  'No price lists yet',
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: lists.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final l = lists[i];
                  return Card(
                    child: ListTile(
                      onTap: () => showDialog(
                        context: context,
                        builder: (_) => _PriceListItemsDialog(priceList: l),
                      ),
                      leading: CircleAvatar(
                        backgroundColor: cs.primaryContainer,
                        child: Icon(
                          Icons.sell_outlined,
                          color: cs.onPrimaryContainer,
                        ),
                      ),
                      title: Text(
                        l.name,
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      subtitle: Text(
                        [
                          if (l.channel != null) l.channel,
                          if (l.currency != null) l.currency,
                          if (l.effectiveFrom != null)
                            'from ${l.effectiveFrom}',
                        ].whereType<String>().join(' · '),
                      ),
                      // Same defect as promotions, on the thing that IS the
                      // price: the resolve engine filters on active and nothing
                      // could write it (SJ-D38).
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _activeBadge(context, l.active),
                          const SizedBox(width: 4),
                          IconButton(
                            tooltip: l.active
                                ? 'Stop this price list'
                                : 'Start it again',
                            icon: Icon(
                              l.active
                                  ? Icons.pause_circle_outline
                                  : Icons.play_circle_outline,
                              size: 22,
                            ),
                            onPressed: () => showDialog(
                              context: context,
                              builder: (_) => _SwitchDialog(
                                collection: 'price-lists',
                                subjectId: l.id,
                                name: l.name,
                                activate: !l.active,
                                onSwitched: () =>
                                    ref.invalidate(priceListsProvider),
                                effect: l.active
                                    ? 'Its prices stop being offered immediately. If nothing '
                                          'else prices these items, they cannot be sold until you '
                                          'start it again — which is the safe answer to a price '
                                          'nobody agreed. Orders already placed keep what they '
                                          'were charged.'
                                    : 'Its prices are offered again from the next basket.',
                              ),
                            ),
                          ),
                        ],
                      ),
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

class _PriceListDialog extends ConsumerStatefulWidget {
  const _PriceListDialog();

  @override
  ConsumerState<_PriceListDialog> createState() => _PriceListDialogState();
}

class _PriceListDialogState extends ConsumerState<_PriceListDialog> {
  final _nameCtrl = TextEditingController();
  String _channel = 'ALL';
  String _currency = 'INR';
  DateTime _from = DateTime.now();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_nameCtrl.text.trim().isEmpty) {
      setState(() => _error = 'Name is required.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.pricing}/admin/price-lists',
            data: {
              'name': _nameCtrl.text.trim(),
              'channel': _channel,
              'currency': _currency,
              // A bare '2026-01-01' is rejected with INVALID_DATE — the column is
              // TIMESTAMPTZ. Sent as a UTC instant, which is also what golden rule
              // 14 asks for: convert at the UI edge, store UTC.
              'effectiveFrom': DateTime.utc(
                _from.year,
                _from.month,
                _from.day,
              ).toIso8601String().replaceFirst(RegExp(r'\.\d+Z$'), 'Z'),
            },
          );
      if (!mounted) return;
      ref.invalidate(priceListsProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not create price list.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('New price list'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _errorBox(context, _error),
            TextField(
              controller: _nameCtrl,
              decoration: const InputDecoration(labelText: 'Name *'),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: _channel,
                    decoration: const InputDecoration(labelText: 'Channel'),
                    items: const [
                      DropdownMenuItem(value: 'ALL', child: Text('All')),
                      DropdownMenuItem(value: 'ONLINE', child: Text('Online')),
                      DropdownMenuItem(value: 'POS', child: Text('POS')),
                    ],
                    onChanged: (v) => setState(() => _channel = v!),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _currencyDropdown(
                    _currency,
                    (v) => setState(() => _currency = v),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _datePickerTile(
              context,
              'Effective from',
              _from,
              (d) => setState(() => _from = d),
            ),
          ],
        ),
      ),
      actions: _dialogActions(context, _loading, _submit, 'Create'),
    );
  }
}

class _PriceListItemsDialog extends ConsumerWidget {
  final PriceList priceList;
  const _PriceListItemsDialog({required this.priceList});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(priceListItemsProvider(priceList.id));
    return AlertDialog(
      title: Row(
        children: [
          Expanded(child: Text(priceList.name)),
          FilledButton.icon(
            onPressed: () => showDialog(
              context: context,
              builder: (_) => _PriceListItemDialog(priceListId: priceList.id),
            ),
            icon: const Icon(Icons.add, size: 18),
            label: const Text('Add item'),
          ),
        ],
      ),
      content: SizedBox(
        width: 460,
        height: 360,
        child: async.when(
          loading: () => const LoadingView(label: 'Loading items…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load items.'),
            onRetry: () => ref.invalidate(priceListItemsProvider(priceList.id)),
          ),
          data: (items) {
            if (items.isEmpty) {
              return Center(
                child: Text(
                  'No items yet.',
                  style: TextStyle(color: cs.outline),
                ),
              );
            }
            final labels =
                ref
                    .watch(
                      variantLabelsProvider(
                        variantIdsKey(items.map((it) => it.variantId)),
                      ),
                    )
                    .value ??
                const <String, VariantLabel>{};
            return ListView.separated(
              itemCount: items.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final it = items[i];
                final sku = variantSku(it.variantId, labels);
                return ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(
                    variantDisplayName(it.variantId, labels),
                    style: const TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 13,
                    ),
                  ),
                  subtitle: Text(
                    '${sku.isNotEmpty ? '$sku  ·  ' : ''}min qty ${it.minQty.toStringAsFixed(0)}',
                  ),
                  trailing: Text(
                    '${priceList.currency ?? ''} ${it.price.toStringAsFixed(2)}',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                );
              },
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Close'),
        ),
      ],
    );
  }
}

class _PriceListItemDialog extends ConsumerStatefulWidget {
  final String priceListId;
  const _PriceListItemDialog({required this.priceListId});

  @override
  ConsumerState<_PriceListItemDialog> createState() =>
      _PriceListItemDialogState();
}

class _PriceListItemDialogState extends ConsumerState<_PriceListItemDialog> {
  String? _productId;
  String? _variantId;
  final _priceCtrl = TextEditingController();
  final _minQtyCtrl = TextEditingController(text: '1');
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _priceCtrl.dispose();
    _minQtyCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final price = double.tryParse(_priceCtrl.text.trim());
    final minQty = double.tryParse(_minQtyCtrl.text.trim()) ?? 1;
    if (_variantId == null || price == null || price <= 0) {
      setState(() => _error = 'Pick a variant and enter a price.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.pricing}/admin/price-lists/${widget.priceListId}/items',
            data: {'variantId': _variantId, 'price': price, 'minQty': minQty},
          );
      if (!mounted) return;
      ref.invalidate(priceListItemsProvider(widget.priceListId));
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not add item.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Add price'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _errorBox(context, _error),
            VariantPicker(
              productId: _productId,
              variantId: _variantId,
              onProduct: (p) => setState(() {
                _productId = p;
                _variantId = null;
              }),
              onVariant: (v) => setState(() => _variantId = v),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _priceCtrl,
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(labelText: 'Price'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _minQtyCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Min qty'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
      actions: _dialogActions(context, _loading, _submit, 'Add'),
    );
  }
}

// ── Promotions ───────────────────────────────────────────────────────────────

/// A small inline label, for the one or two promotion properties that change how
/// every other promotion behaves and are therefore worth seeing in the list.
Widget _chip(
  BuildContext context,
  String text,
  Color bg,
  Color fg,
) => Container(
  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
  decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(4)),
  child: Text(
    text,
    style: TextStyle(color: fg, fontSize: 10.5, fontWeight: FontWeight.bold),
  ),
);

class _PromotionsTab extends ConsumerWidget {
  const _PromotionsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(promotionsProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        _addBar(
          context,
          'New promotion',
          () => showDialog(
            context: context,
            builder: (_) => const _PromotionDialog(),
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading promotions…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load promotions.'),
              onRetry: () => ref.invalidate(promotionsProvider),
            ),
            data: (promos) {
              if (promos.isEmpty) {
                return _empty(
                  cs,
                  Icons.local_offer_outlined,
                  'No promotions yet',
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: promos.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final p = promos[i];
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: cs.tertiaryContainer,
                        child: Icon(
                          p.couponCode != null
                              ? Icons.confirmation_number_outlined
                              : Icons.local_offer_outlined,
                          color: cs.onTertiaryContainer,
                        ),
                      ),
                      title: Row(
                        children: [
                          Flexible(
                            child: Text(
                              p.name,
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                              ),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          // An exclusive promotion changes what every other one
                          // does, so it is the one property worth seeing without
                          // opening the row.
                          if (p.exclusive) ...[
                            const SizedBox(width: 8),
                            _chip(
                              context,
                              'Exclusive',
                              cs.errorContainer,
                              cs.onErrorContainer,
                            ),
                          ],
                        ],
                      ),
                      subtitle: Text(
                        [
                          p.summary,
                          if (p.couponCode != null) 'code ${p.couponCode}',
                          if (p.channel != null && p.channel != 'ALL')
                            p.channel,
                          // Priority only earns space when it is not the default:
                          // every promotion showing "priority 100" tells nobody
                          // anything.
                          if (p.priority != 100) 'priority ${p.priority}',
                          if (p.maxRedemptions != null)
                            'max ${p.maxRedemptions}',
                        ].whereType<String>().join(' · '),
                      ),
                      // The badge said whether it was running and offered no
                      // way to change that — because until SJ-D33 there was no
                      // endpoint behind it. A promotion nobody can switch off
                      // is the one that matters most to be able to switch off.
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          _activeBadge(context, p.active),
                          const SizedBox(width: 4),
                          IconButton(
                            tooltip: p.active
                                ? 'Stop this promotion'
                                : 'Start it again',
                            icon: Icon(
                              p.active
                                  ? Icons.pause_circle_outline
                                  : Icons.play_circle_outline,
                              size: 22,
                            ),
                            onPressed: () => showDialog(
                              context: context,
                              builder: (_) => _SwitchDialog(
                                collection: 'promotions',
                                subjectId: p.id,
                                name: p.name,
                                activate: !p.active,
                                onSwitched: () =>
                                    ref.invalidate(promotionsProvider),
                                effect: p.active
                                    ? 'It stops applying to baskets immediately. Orders already '
                                          'placed are unaffected — the discount they received is '
                                          'recorded on the order.'
                                    : 'It will start applying to baskets immediately.',
                              ),
                            ),
                          ),
                        ],
                      ),
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

/// Stops a running promotion or price list, or starts a stopped one again.
///
/// One dialog for both because they are the same defect and the same fix: each
/// had an `active` column the resolve engine reads and nothing in the product
/// could write (SJ-D33, SJ-D38). A price list is the harsher of the two — a
/// promotion discounts a price, a price list *is* the price.
///
/// A reason is required in both directions, matching the server. Restarting is
/// the change more likely to be questioned later, and a trail recording only why
/// things were stopped answers the easier half of the question.
class _SwitchDialog extends ConsumerStatefulWidget {
  /// Admin collection this subject lives under — `promotions` or `price-lists`.
  final String collection;
  final String subjectId;
  final String name;
  final bool activate;

  /// What stopping it does, in the words of someone who has to decide.
  final String effect;

  /// Called after a successful switch, to redraw the list behind the dialog.
  final VoidCallback onSwitched;

  const _SwitchDialog({
    required this.collection,
    required this.subjectId,
    required this.name,
    required this.activate,
    required this.effect,
    required this.onSwitched,
  });

  @override
  ConsumerState<_SwitchDialog> createState() => _SwitchDialogState();
}

class _SwitchDialogState extends ConsumerState<_SwitchDialog> {
  final _reason = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final reason = _reason.text.trim();
    if (reason.isEmpty) {
      setState(() => _error = 'Say why — this is recorded against your name.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final action = widget.activate ? 'activate' : 'deactivate';
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.pricing}/admin/${widget.collection}/${widget.subjectId}/$action',
            data: {'reason': reason},
          );
      if (!mounted) return;
      widget.onSwitched();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.activate
                ? 'Started — it is live again now.'
                : 'Stopped, with immediate effect.',
          ),
        ),
      );
    } catch (e) {
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not change this.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(
        '${widget.activate ? 'Start' : 'Stop'} ${widget.collection == 'promotions' ? 'promotion' : 'price list'}',
      ),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              widget.name,
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(widget.effect),
            const SizedBox(height: 12),
            TextField(
              controller: _reason,
              autofocus: true,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Reason',
                hintText: 'e.g. priced wrong — 50% was meant to be 5%',
                border: OutlineInputBorder(),
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Text(
                _error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _busy ? null : _submit,
          child: Text(widget.activate ? 'Start' : 'Stop'),
        ),
      ],
    );
  }
}

class _PromotionDialog extends ConsumerStatefulWidget {
  const _PromotionDialog();

  @override
  ConsumerState<_PromotionDialog> createState() => _PromotionDialogState();
}

class _PromotionDialogState extends ConsumerState<_PromotionDialog> {
  final _nameCtrl = TextEditingController();
  final _valueCtrl = TextEditingController();
  final _minCtrl = TextEditingController();
  final _couponCtrl = TextEditingController();
  final _priorityCtrl = TextEditingController(text: '100');
  final _maxRedemptionsCtrl = TextEditingController();
  final _maxPerCustomerCtrl = TextEditingController();
  final _buyQtyCtrl = TextEditingController();
  final _getQtyCtrl = TextEditingController();
  final _getPctCtrl = TextEditingController(text: '100');
  String _type = 'PERCENT';
  String _channel = 'ALL';
  bool _exclusive = false;
  DateTime _starts = DateTime.now();
  DateTime? _ends;
  bool _loading = false;
  String? _error;

  bool get _isBogo => _type == 'BOGO';
  bool get _isThreshold => _type == 'SPEND_THRESHOLD';

  @override
  void dispose() {
    _nameCtrl.dispose();
    _valueCtrl.dispose();
    _minCtrl.dispose();
    _couponCtrl.dispose();
    _priorityCtrl.dispose();
    _maxRedemptionsCtrl.dispose();
    _maxPerCustomerCtrl.dispose();
    _buyQtyCtrl.dispose();
    _getQtyCtrl.dispose();
    _getPctCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    // A BOGO is described by its quantities, not by a value, so the server takes
    // a placeholder 1 there. Validating client-side as well as server-side is
    // deliberate: a half-configured BOGO would apply to every basket and
    // discount nothing, which is the exact failure this rebuild removed.
    final value = _isBogo ? 1.0 : double.tryParse(_valueCtrl.text.trim());
    if (_nameCtrl.text.trim().isEmpty || value == null || value <= 0) {
      setState(() => _error = 'Enter a name and a positive value.');
      return;
    }
    if (_isBogo) {
      final buy = double.tryParse(_buyQtyCtrl.text.trim());
      final get = double.tryParse(_getQtyCtrl.text.trim());
      final pct = double.tryParse(_getPctCtrl.text.trim());
      if (buy == null ||
          buy <= 0 ||
          get == null ||
          get <= 0 ||
          pct == null ||
          pct <= 0 ||
          pct > 100) {
        setState(
          () => _error =
              'A buy-one-get-one needs a buy quantity, a get quantity, and a '
              'discount between 1 and 100 percent.',
        );
        return;
      }
    }
    if (_isThreshold && double.tryParse(_minCtrl.text.trim()) == null) {
      setState(
        () => _error =
            'A spend threshold needs a minimum order amount — without one it '
            'would discount every basket.',
      );
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    try {
      final resp = await dio.post(
        '/${ApiConstants.pricing}/admin/promotions',
        data: {
          'name': _nameCtrl.text.trim(),
          'type': _type,
          'value': value,
          if (_minCtrl.text.trim().isNotEmpty)
            'minOrderAmount': double.tryParse(_minCtrl.text.trim()),
          'channel': _channel,
          'startsAt': _starts.toIso8601String(),
          if (_ends != null) 'endsAt': _ends!.toIso8601String(),
          'priority': int.tryParse(_priorityCtrl.text.trim()) ?? 100,
          'exclusive': _exclusive,
          if (_couponCtrl.text.trim().isNotEmpty)
            'couponCode': _couponCtrl.text.trim(),
          if (_maxRedemptionsCtrl.text.trim().isNotEmpty)
            'maxRedemptions': int.tryParse(_maxRedemptionsCtrl.text.trim()),
          if (_maxPerCustomerCtrl.text.trim().isNotEmpty)
            'maxPerCustomer': int.tryParse(_maxPerCustomerCtrl.text.trim()),
          if (_isBogo) 'buyQty': double.tryParse(_buyQtyCtrl.text.trim()),
          if (_isBogo) 'getQty': double.tryParse(_getQtyCtrl.text.trim()),
          if (_isBogo)
            'getDiscountPct': double.tryParse(_getPctCtrl.text.trim()),
        },
      );
      // Apply to all products by default so the promo is usable immediately.
      final promo = resp.data['data'] as Map<String, dynamic>;
      final promoId = promo['id'] as String?;
      if (promoId != null) {
        await dio.post(
          '/${ApiConstants.pricing}/admin/promotions/$promoId/items',
          data: {'scopeType': 'ALL'},
        );
      }
      if (!mounted) return;
      ref.invalidate(promotionsProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not create promotion.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('New promotion'),
      content: SizedBox(
        width: 400,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _errorBox(context, _error),
              TextField(
                controller: _nameCtrl,
                decoration: const InputDecoration(labelText: 'Name *'),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: _type,
                      decoration: const InputDecoration(labelText: 'Type'),
                      items: const [
                        DropdownMenuItem(
                          value: 'PERCENT',
                          child: Text('% off each item'),
                        ),
                        DropdownMenuItem(
                          value: 'FLAT',
                          child: Text('Amount off each item'),
                        ),
                        DropdownMenuItem(
                          value: 'BASKET_PERCENT',
                          child: Text('% off the basket'),
                        ),
                        DropdownMenuItem(
                          value: 'BASKET_FLAT',
                          child: Text('Amount off the basket'),
                        ),
                        DropdownMenuItem(
                          value: 'SPEND_THRESHOLD',
                          child: Text('Spend and save'),
                        ),
                        DropdownMenuItem(
                          value: 'BOGO',
                          child: Text('Buy X get Y'),
                        ),
                      ],
                      onChanged: (v) => setState(() => _type = v!),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    // A BOGO has no single value — its three quantities below
                    // describe it — so the field goes away rather than sitting
                    // there inviting a number that means nothing.
                    child: _isBogo
                        ? const SizedBox.shrink()
                        : TextField(
                            controller: _valueCtrl,
                            keyboardType: const TextInputType.numberWithOptions(
                              decimal: true,
                            ),
                            decoration: InputDecoration(
                              labelText: _type.contains('PERCENT')
                                  ? 'Percent'
                                  : 'Amount',
                            ),
                          ),
                  ),
                ],
              ),
              if (_isBogo) ...[
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _buyQtyCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: const InputDecoration(labelText: 'Buy *'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextField(
                        controller: _getQtyCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: const InputDecoration(labelText: 'Get *'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextField(
                        controller: _getPctCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: const InputDecoration(
                          labelText: '% off (100 = free)',
                        ),
                      ),
                    ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    'Counted across every product the promotion covers, not '
                    'within one line — and the cheapest units are the ones '
                    'given away.',
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.outline,
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: _channel,
                      decoration: const InputDecoration(labelText: 'Channel'),
                      items: const [
                        DropdownMenuItem(value: 'ALL', child: Text('All')),
                        DropdownMenuItem(
                          value: 'ONLINE',
                          child: Text('Online'),
                        ),
                        DropdownMenuItem(value: 'POS', child: Text('POS')),
                      ],
                      onChanged: (v) => setState(() => _channel = v!),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _minCtrl,
                      keyboardType: const TextInputType.numberWithOptions(
                        decimal: true,
                      ),
                      decoration: InputDecoration(
                        labelText: _isThreshold
                            ? 'Spend at least *'
                            : 'Min order (opt)',
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _couponCtrl,
                      textCapitalization: TextCapitalization.characters,
                      decoration: const InputDecoration(
                        labelText: 'Coupon code (opt)',
                        helperText: 'Blank = applies on its own',
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _priorityCtrl,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Priority',
                        helperText: 'Lower runs first',
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _maxRedemptionsCtrl,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Max uses (opt)',
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _maxPerCustomerCtrl,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: 'Max per customer (opt)',
                        helperText: 'Guests are uncapped',
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: _exclusive,
                onChanged: (v) => setState(() => _exclusive = v),
                title: const Text('Cannot be combined'),
                subtitle: const Text(
                  'Stops every promotion with a higher priority number',
                ),
              ),
              const SizedBox(height: 8),
              _datePickerTile(
                context,
                'Starts',
                _starts,
                (d) => setState(() => _starts = d),
              ),
              _datePickerTile(
                context,
                'Ends (optional)',
                _ends,
                (d) => setState(() => _ends = d),
              ),
            ],
          ),
        ),
      ),
      actions: _dialogActions(context, _loading, _submit, 'Create'),
    );
  }
}

// ── VAT rates ────────────────────────────────────────────────────────────────

class _VatRatesTab extends ConsumerWidget {
  const _VatRatesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(vatRatesProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        _addBar(
          context,
          'New VAT rate',
          () => showDialog(
            context: context,
            builder: (_) => const _VatRateDialog(),
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading VAT rates…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load VAT rates.'),
              onRetry: () => ref.invalidate(vatRatesProvider),
            ),
            data: (rates) {
              if (rates.isEmpty) {
                return _empty(cs, Icons.percent, 'No VAT rates yet');
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: rates.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final r = rates[i];
                  return Card(
                    child: ListTile(
                      onTap: () => showDialog(
                        context: context,
                        builder: (_) => _VatRateDialog(existing: r),
                      ),
                      leading: CircleAvatar(
                        backgroundColor: cs.secondaryContainer,
                        child: Icon(
                          Icons.percent,
                          color: cs.onSecondaryContainer,
                        ),
                      ),
                      title: Text(
                        '${r.code} · ${r.name}',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      subtitle: Text(
                        r.exempt ? 'Exempt' : '${r.rate.toStringAsFixed(2)}%',
                      ),
                      trailing: const Icon(Icons.edit_outlined, size: 18),
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

class _VatRateDialog extends ConsumerStatefulWidget {
  final VatRate? existing;
  const _VatRateDialog({this.existing});

  @override
  ConsumerState<_VatRateDialog> createState() => _VatRateDialogState();
}

class _VatRateDialogState extends ConsumerState<_VatRateDialog> {
  late final TextEditingController _codeCtrl;
  late final TextEditingController _nameCtrl;
  late final TextEditingController _rateCtrl;
  late final TextEditingController _descCtrl;
  late bool _exempt;
  DateTime _from = DateTime.now();
  bool _loading = false;
  String? _error;

  bool get _isEdit => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _codeCtrl = TextEditingController(text: e?.code ?? '');
    _nameCtrl = TextEditingController(text: e?.name ?? '');
    _rateCtrl = TextEditingController(text: e?.rate.toString() ?? '');
    _descCtrl = TextEditingController(text: e?.description ?? '');
    _exempt = e?.exempt ?? false;
  }

  @override
  void dispose() {
    _codeCtrl.dispose();
    _nameCtrl.dispose();
    _rateCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final rate = double.tryParse(_rateCtrl.text.trim()) ?? 0;
    if (_codeCtrl.text.trim().isEmpty || _nameCtrl.text.trim().isEmpty) {
      setState(() => _error = 'Code and name are required.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    final body = {
      'code': _codeCtrl.text.trim().toUpperCase(),
      'name': _nameCtrl.text.trim(),
      'rate': _exempt ? 0 : rate,
      'exempt': _exempt,
      'description': _descCtrl.text.trim().isEmpty
          ? null
          : _descCtrl.text.trim(),
      'effectiveFrom': _from.toIso8601String().split('T').first,
    };
    try {
      if (_isEdit) {
        await dio.put(
          '/${ApiConstants.pricing}/vat-rates/${widget.existing!.code}',
          data: body,
        );
      } else {
        await dio.post('/${ApiConstants.pricing}/vat-rates', data: body);
      }
      if (!mounted) return;
      ref.invalidate(vatRatesProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not save VAT rate.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEdit ? 'Edit VAT rate' : 'New VAT rate'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _errorBox(context, _error),
            TextField(
              controller: _codeCtrl,
              enabled: !_isEdit,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: 'Code *',
                hintText: 'STANDARD',
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _nameCtrl,
              decoration: const InputDecoration(labelText: 'Name *'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _rateCtrl,
              enabled: !_exempt,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              decoration: const InputDecoration(labelText: 'Rate %'),
            ),
            const SizedBox(height: 4),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _exempt,
              onChanged: (v) => setState(() => _exempt = v),
              title: const Text('Exempt'),
            ),
            _datePickerTile(
              context,
              'Effective from',
              _from,
              (d) => setState(() => _from = d),
            ),
          ],
        ),
      ),
      actions: _dialogActions(
        context,
        _loading,
        _submit,
        _isEdit ? 'Save' : 'Create',
      ),
    );
  }
}

// ── VAT Return (HMRC MTD boxes 1–9) ──────────────────────────────────────────

class _VatReturnTab extends ConsumerStatefulWidget {
  const _VatReturnTab();

  @override
  ConsumerState<_VatReturnTab> createState() => _VatReturnTabState();
}

class _VatReturnTabState extends ConsumerState<_VatReturnTab> {
  late VatReturnRange _range;

  @override
  void initState() {
    super.initState();
    _range = defaultVatReturnRange();
  }

  Future<void> _pickFrom() async {
    final initial = DateTime.tryParse(_range.from) ?? DateTime.now().toUtc();
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(DateTime.now().year - 5),
      lastDate: DateTime(DateTime.now().year + 1),
    );
    if (picked == null) return;
    setState(() {
      _range = VatReturnRange(
        from: DateTime.utc(
          picked.year,
          picked.month,
          picked.day,
        ).toIso8601String(),
        to: _range.to,
      );
    });
  }

  Future<void> _pickTo() async {
    final initial = DateTime.tryParse(_range.to) ?? DateTime.now().toUtc();
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(DateTime.now().year - 5),
      lastDate: DateTime(DateTime.now().year + 1),
    );
    if (picked == null) return;
    // Exclusive end of day → next midnight
    final end = DateTime.utc(
      picked.year,
      picked.month,
      picked.day,
    ).add(const Duration(days: 1));
    setState(() {
      _range = VatReturnRange(from: _range.from, to: end.toIso8601String());
    });
  }

  String _dayLabel(String iso) {
    final d = DateTime.tryParse(iso);
    if (d == null) return iso;
    return d.toIso8601String().split('T').first;
  }

  static const _boxLabels = <int, String>{
    1: 'VAT due on sales and other outputs',
    2: 'VAT due on acquisitions from EU (usually 0)',
    3: 'Total VAT due (box 1 + box 2)',
    4: 'VAT reclaimed on purchases (input VAT)',
    5: 'Net VAT to pay / reclaim',
    6: 'Total value of sales excluding VAT',
    7: 'Total value of purchases excluding VAT',
    8: 'Total value of EU supplies (goods)',
    9: 'Total value of EU acquisitions (goods)',
  };

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(vatReturnProvider(_range));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: Wrap(
            spacing: 12,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              OutlinedButton.icon(
                onPressed: _pickFrom,
                icon: const Icon(Icons.event_outlined, size: 18),
                label: Text('From: ${_dayLabel(_range.from)}'),
              ),
              OutlinedButton.icon(
                onPressed: _pickTo,
                icon: const Icon(Icons.event_outlined, size: 18),
                label: Text('To: ${_dayLabel(_range.to)}'),
              ),
              TextButton(
                onPressed: () =>
                    setState(() => _range = defaultVatReturnRange()),
                child: const Text('This quarter'),
              ),
              TextButton(
                onPressed: () {
                  final now = DateTime.now().toUtc();
                  final from = now
                      .subtract(const Duration(days: 90))
                      .toIso8601String();
                  setState(() {
                    _range = VatReturnRange(
                      from: from,
                      to: now.toIso8601String(),
                    );
                  });
                },
                child: const Text('Last 90 days'),
              ),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh',
                onPressed: () => ref.invalidate(vatReturnProvider(_range)),
              ),
            ],
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Computing VAT return…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load VAT return.'),
              onRetry: () => ref.invalidate(vatReturnProvider(_range)),
            ),
            data: (vr) {
              final boxes = <int, double>{
                1: vr.box1,
                2: vr.box2,
                3: vr.box3,
                4: vr.box4,
                5: vr.box5,
                6: vr.box6,
                7: vr.box7,
                8: vr.box8,
                9: vr.box9,
              };
              return ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  Text(
                    'HMRC Making Tax Digital VAT return (boxes 1–9)',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'Period ${_dayLabel(_range.from)} → ${_dayLabel(_range.to)}',
                    style: TextStyle(color: cs.outline),
                  ),
                  const SizedBox(height: 16),
                  // SJ-D39: the return says on its face which boxes are real — and, once
                  // it is fit to file, what the zero boxes assume.
                  if (vr.fitToFile && vr.caveat != null) ...[
                    Container(
                      key: const Key('vat-return-note'),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(Icons.info_outline, color: cs.onSurfaceVariant),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              vr.caveat!,
                              style: TextStyle(color: cs.onSurfaceVariant),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],
                  if (!vr.fitToFile) ...[
                    Container(
                      key: const Key('vat-return-caveat'),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Icon(
                            Icons.report_problem_outlined,
                            color: cs.onErrorContainer,
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              vr.caveat ??
                                  'Not every box is computed. Do not file from this return.',
                              style: TextStyle(color: cs.onErrorContainer),
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                  ],
                  ...boxes.entries.map((e) {
                    final n = e.key;
                    final notComputed = vr.notComputedBoxes.contains(n);
                    final highlight = !notComputed && (n == 3 || n == 5);
                    return Card(
                      color: highlight
                          ? cs.primaryContainer.withAlpha(80)
                          : null,
                      child: ListTile(
                        leading: CircleAvatar(
                          backgroundColor: highlight
                              ? cs.primaryContainer
                              : cs.secondaryContainer,
                          child: Text(
                            '$n',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: highlight
                                  ? cs.onPrimaryContainer
                                  : cs.onSecondaryContainer,
                            ),
                          ),
                        ),
                        title: Text(_boxLabels[n] ?? 'Box $n'),
                        subtitle: notComputed
                            ? Text(
                                vr.fitToFile
                                    ? 'Not modelled — zero unless you have Northern Ireland protocol trade'
                                    : 'Not computed — recorded elsewhere, not carried here',
                                style: TextStyle(
                                  color: vr.fitToFile ? cs.outline : cs.error,
                                ),
                              )
                            : null,
                        trailing: notComputed
                            ? Text(
                                '—',
                                key: Key('vat-box-$n-not-computed'),
                                style: TextStyle(
                                  color: cs.outline,
                                  fontFamily: 'monospace',
                                  fontSize: 15,
                                ),
                              )
                            : Text(
                                e.value.toStringAsFixed(2),
                                style: TextStyle(
                                  fontWeight: highlight
                                      ? FontWeight.bold
                                      : FontWeight.w600,
                                  fontFamily: 'monospace',
                                  fontSize: 15,
                                ),
                              ),
                      ),
                    );
                  }),
                  const SizedBox(height: 16),
                  _MtdFilingSection(range: _range),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

// ── Making Tax Digital (18.5) ───────────────────────────────────────────────

/// The digital link: the number the business files under, the periods HMRC
/// expects, and the returns filed — each from the same figures shown above,
/// with nobody re-keying them.
class _MtdFilingSection extends ConsumerWidget {
  const _MtdFilingSection({required this.range});
  final VatReturnRange range;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final reg = ref.watch(vatRegistrationProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Filing with HMRC (Making Tax Digital)',
          style: Theme.of(
            context,
          ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        reg.when(
          loading: () => const LoadingView(label: 'Loading registration…'),
          error: (e, _) => ErrorView(
            message: friendlyError(
              e,
              fallback: 'Could not load the VAT registration.',
            ),
            onRetry: () => ref.invalidate(vatRegistrationProvider),
          ),
          data: (r) => Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Card(
                child: ListTile(
                  key: const Key('mtd-registration'),
                  leading: Icon(
                    r.registered
                        ? Icons.verified_outlined
                        : Icons.app_registration_outlined,
                    color: r.registered ? cs.primary : cs.outline,
                  ),
                  title: Text(
                    r.registered
                        ? 'VAT number ${r.vrn} · files through ${r.provider == 'HMRC' ? 'HMRC' : 'the simulator'}'
                        : 'No VAT number registered',
                  ),
                  subtitle: Text(
                    r.registered
                        ? (r.provider == 'HMRC'
                              ? (r.connected
                                    ? 'HMRC\'s grant held since ${r.connectedAt}'
                                    : 'HMRC\'s grant not yet given')
                              : 'The simulator accepts what HMRC\'s sandbox accepts; nothing reaches HMRC.')
                        : (r.hmrcConfigured
                              ? 'Register the number to file under, through HMRC or the simulator.'
                              : 'Register the number to file under. HMRC is not configured on this deployment; the simulator is.'),
                  ),
                  trailing: TextButton(
                    key: const Key('mtd-register'),
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (_) => _MtdRegisterDialog(current: r),
                    ),
                    child: Text(r.registered ? 'Change' : 'Register'),
                  ),
                ),
              ),
              if (r.registered) ...[
                const SizedBox(height: 8),
                Text(
                  'Periods to file',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                ref
                    .watch(vatObligationsProvider(range))
                    .when(
                      loading: () =>
                          const LoadingView(label: 'Loading obligations…'),
                      error: (e, _) => ErrorView(
                        message: friendlyError(
                          e,
                          fallback: 'Could not load the obligations.',
                        ),
                        onRetry: () =>
                            ref.invalidate(vatObligationsProvider(range)),
                      ),
                      data: (obligations) => obligations.isEmpty
                          ? const Text('No obligations in this range.')
                          : Card(
                              child: Column(
                                children: [
                                  for (final o in obligations)
                                    ListTile(
                                      dense: true,
                                      leading: Icon(
                                        o.open
                                            ? Icons.pending_outlined
                                            : Icons.check_circle_outline,
                                        color: o.open
                                            ? cs.tertiary
                                            : cs.primary,
                                      ),
                                      title: Text(
                                        '${o.periodKey} · ${_day(o.start)} → ${_day(o.end)}',
                                      ),
                                      subtitle: Text(
                                        o.open
                                            ? 'Open · due ${_day(o.due)}'
                                            : 'Filed',
                                      ),
                                      trailing: o.open
                                          ? FilledButton.tonal(
                                              key: Key(
                                                'mtd-file-${o.periodKey}',
                                              ),
                                              onPressed: () => showDialog<void>(
                                                context: context,
                                                builder: (_) => _MtdFileDialog(
                                                  obligation: o,
                                                  range: range,
                                                ),
                                              ),
                                              child: const Text(
                                                'File this period',
                                              ),
                                            )
                                          : null,
                                    ),
                                ],
                              ),
                            ),
                    ),
                const SizedBox(height: 8),
                Text(
                  'Returns filed',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                ref
                    .watch(vatSubmissionsProvider)
                    .when(
                      loading: () =>
                          const LoadingView(label: 'Loading filings…'),
                      error: (e, _) => ErrorView(
                        message: friendlyError(
                          e,
                          fallback: 'Could not load the filings.',
                        ),
                        onRetry: () => ref.invalidate(vatSubmissionsProvider),
                      ),
                      data: (subs) => subs.isEmpty
                          ? const Text(
                              'Nothing filed yet.',
                              key: Key('mtd-no-filings'),
                            )
                          : Card(
                              child: Column(
                                children: [
                                  for (final s in subs)
                                    ListTile(
                                      dense: true,
                                      key: Key('mtd-filing-${s.id}'),
                                      leading: Icon(
                                        s.status == 'ACCEPTED'
                                            ? Icons.receipt_long_outlined
                                            : Icons.error_outline,
                                        color: s.status == 'ACCEPTED'
                                            ? cs.primary
                                            : cs.error,
                                      ),
                                      title: Text(
                                        '${s.periodKey} · ${s.status == 'ACCEPTED' ? 'accepted' : 'refused'} · ${_day(s.submittedAt)}',
                                      ),
                                      subtitle: Text(
                                        s.status == 'ACCEPTED'
                                            ? 'Box 1 ${s.box1.toStringAsFixed(2)} · box 5 ${s.box5.toStringAsFixed(2)} · box 6 ${s.box6.toStringAsFixed(0)} · form bundle ${s.formBundleNumber ?? '—'}'
                                            : '${s.errorCode ?? ''} ${s.errorMessage ?? ''}',
                                      ),
                                    ),
                                ],
                              ),
                            ),
                    ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  static String _day(String? iso) {
    if (iso == null) return '—';
    final d = DateTime.tryParse(iso);
    return d == null ? iso : d.toIso8601String().split('T').first;
  }
}

class _MtdRegisterDialog extends ConsumerStatefulWidget {
  const _MtdRegisterDialog({required this.current});
  final VatRegistration current;
  @override
  ConsumerState<_MtdRegisterDialog> createState() => _MtdRegisterDialogState();
}

class _MtdRegisterDialogState extends ConsumerState<_MtdRegisterDialog> {
  late final TextEditingController _vrn;
  late String _provider;
  String? _error;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _vrn = TextEditingController(text: widget.current.vrn ?? '');
    _provider =
        widget.current.provider ??
        (widget.current.providers.contains('HMRC') ? 'HMRC' : 'SIMULATED');
  }

  @override
  void dispose() {
    _vrn.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            '/${ApiConstants.pricing}/vat-return/mtd/registration',
            data: {'vrn': _vrn.text.trim(), 'provider': _provider},
          );
      ref.invalidate(vatRegistrationProvider);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not register.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final providers = widget.current.providers.isEmpty
        ? const ['SIMULATED']
        : widget.current.providers;
    return AlertDialog(
      title: const Text('VAT registration'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              key: const Key('mtd-vrn'),
              controller: _vrn,
              decoration: const InputDecoration(
                labelText: 'VAT registration number',
                helperText: 'Nine digits, e.g. GB 123 4567 82',
              ),
            ),
            DropdownButtonFormField<String>(
              key: const Key('mtd-provider'),
              isExpanded: true,
              initialValue: providers.contains(_provider)
                  ? _provider
                  : providers.first,
              decoration: const InputDecoration(labelText: 'File through'),
              items: [
                for (final p in providers)
                  DropdownMenuItem(
                    value: p,
                    child: Text(
                      p == 'HMRC'
                          ? 'HMRC — the VAT (MTD) API'
                          : 'Simulator — nothing reaches HMRC',
                    ),
                  ),
              ],
              onChanged: (v) => setState(() => _provider = v ?? 'SIMULATED'),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: const Key('mtd-register-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('mtd-register-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}

class _MtdFileDialog extends ConsumerStatefulWidget {
  const _MtdFileDialog({required this.obligation, required this.range});
  final VatObligation obligation;
  final VatReturnRange range;
  @override
  ConsumerState<_MtdFileDialog> createState() => _MtdFileDialogState();
}

class _MtdFileDialogState extends ConsumerState<_MtdFileDialog> {
  bool _finalised = false;
  bool _saving = false;
  String? _error;

  Future<void> _file() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final o = widget.obligation;
      // The period filed is the obligation's own, to the day after its end (exclusive).
      final end =
          DateTime.tryParse(
            o.end,
          )?.add(const Duration(days: 1)).toIso8601String() ??
          widget.range.to;
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.pricing}/vat-return/mtd/submissions',
            data: {
              'periodKey': o.periodKey,
              'from': o.start,
              'to': end,
              'finalised': _finalised,
              'client': {
                'timezone': _timezone(),
                'screens':
                    'width=${WidgetsBinding.instance.platformDispatcher.views.first.physicalSize.width.toInt()}&height=${WidgetsBinding.instance.platformDispatcher.views.first.physicalSize.height.toInt()}&scaling-factor=${WidgetsBinding.instance.platformDispatcher.views.first.devicePixelRatio}&colour-depth=24',
                'windowSize':
                    'width=${MediaQuery.sizeOf(context).width.toInt()}&height=${MediaQuery.sizeOf(context).height.toInt()}',
              },
            },
          );
      ref.invalidate(vatSubmissionsProvider);
      ref.invalidate(vatObligationsProvider(widget.range));
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'The return was not filed.');
      });
    }
  }

  static String _timezone() {
    final offset = DateTime.now().timeZoneOffset;
    final sign = offset.isNegative ? '-' : '+';
    final h = offset.inHours.abs().toString().padLeft(2, '0');
    final m = (offset.inMinutes.abs() % 60).toString().padLeft(2, '0');
    return 'UTC$sign$h:$m';
  }

  @override
  Widget build(BuildContext context) {
    final o = widget.obligation;
    return AlertDialog(
      title: Text('File period ${o.periodKey}'),
      content: SizedBox(
        width: 440,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'The nine boxes are computed from this business\'s records for '
              '${o.start.split('T').first} to ${o.end.split('T').first} and sent as they are; '
              'boxes 6 to 9 in whole pounds. What HMRC answers is kept.',
            ),
            const SizedBox(height: 12),
            CheckboxListTile(
              key: const Key('mtd-finalised'),
              value: _finalised,
              onChanged: (v) => setState(() => _finalised = v ?? false),
              controlAffinity: ListTileControlAffinity.leading,
              title: const Text(
                'I declare the information is true and complete',
              ),
              subtitle: const Text(
                'A false declaration can result in prosecution.',
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: const Key('mtd-file-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('mtd-file-submit'),
          onPressed: _saving || !_finalised ? null : _file,
          child: const Text('File with HMRC'),
        ),
      ],
    );
  }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

Widget _empty(ColorScheme cs, IconData icon, String text) => Center(
  child: Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, size: 64, color: cs.outlineVariant),
      const SizedBox(height: 12),
      Text(text),
    ],
  ),
);

Widget _activeBadge(BuildContext context, bool active) {
  final cs = Theme.of(context).colorScheme;
  return Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      color: active ? cs.secondaryContainer : cs.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      active ? 'ACTIVE' : 'INACTIVE',
      style: TextStyle(
        fontSize: 11,
        fontWeight: FontWeight.bold,
        color: active ? cs.onSecondaryContainer : cs.onSurfaceVariant,
      ),
    ),
  );
}

Widget _errorBox(BuildContext context, String? error) {
  if (error == null) return const SizedBox.shrink();
  final cs = Theme.of(context).colorScheme;
  return Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.errorContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(error, style: TextStyle(color: cs.onErrorContainer)),
    ),
  );
}

Widget _currencyDropdown(String value, ValueChanged<String> onChanged) =>
    DropdownButtonFormField<String>(
      initialValue: value,
      decoration: const InputDecoration(labelText: 'Currency'),
      items: const [
        DropdownMenuItem(value: 'INR', child: Text('INR')),
        DropdownMenuItem(value: 'USD', child: Text('USD')),
        DropdownMenuItem(value: 'GBP', child: Text('GBP')),
        DropdownMenuItem(value: 'SGD', child: Text('SGD')),
        DropdownMenuItem(value: 'AED', child: Text('AED')),
      ],
      onChanged: (v) => onChanged(v!),
    );

Widget _datePickerTile(
  BuildContext context,
  String label,
  DateTime? value,
  ValueChanged<DateTime> onPicked,
) {
  return ListTile(
    contentPadding: EdgeInsets.zero,
    leading: const Icon(Icons.event_outlined),
    title: Text(
      value == null
          ? label
          : '$label: ${value.toIso8601String().split('T').first}',
    ),
    trailing: const Icon(Icons.edit_calendar_outlined),
    onTap: () async {
      final now = DateTime.now();
      final picked = await showDatePicker(
        context: context,
        initialDate: value ?? now,
        firstDate: DateTime(now.year - 1),
        lastDate: DateTime(now.year + 3),
      );
      if (picked != null) onPicked(picked);
    },
  );
}

List<Widget> _dialogActions(
  BuildContext context,
  bool loading,
  VoidCallback onSubmit,
  String label,
) {
  return [
    TextButton(
      onPressed: loading ? null : () => Navigator.pop(context),
      child: const Text('Cancel'),
    ),
    FilledButton(
      onPressed: loading ? null : onSubmit,
      child: loading
          ? SizedBox(
              height: 18,
              width: 18,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: Theme.of(context).colorScheme.onPrimary,
              ),
            )
          : Text(label),
    ),
  ];
}
