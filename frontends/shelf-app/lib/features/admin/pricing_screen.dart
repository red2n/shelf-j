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
      length: 3,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
            child:
                Text('Pricing', style: Theme.of(context).textTheme.headlineMedium),
          ),
          const TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [
              Tab(text: 'Price Lists'),
              Tab(text: 'Promotions'),
              Tab(text: 'VAT Rates'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _PriceListsTab(),
                _PromotionsTab(),
                _VatRatesTab(),
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
            onPressed: onPressed, icon: const Icon(Icons.add), label: Text(label)),
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
        _addBar(context, 'New price list',
            () => showDialog(context: context, builder: (_) => const _PriceListDialog())),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading price lists…'),
            error: (e, _) => ErrorView(
              message:
                  friendlyError(e, fallback: 'Could not load price lists.'),
              onRetry: () => ref.invalidate(priceListsProvider),
            ),
            data: (lists) {
              if (lists.isEmpty) {
                return _empty(cs, Icons.price_change_outlined, 'No price lists yet');
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: lists.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
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
                        child: Icon(Icons.sell_outlined,
                            color: cs.onPrimaryContainer),
                      ),
                      title: Text(l.name,
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text([
                        if (l.channel != null) l.channel,
                        if (l.currency != null) l.currency,
                        if (l.effectiveFrom != null) 'from ${l.effectiveFrom}',
                      ].whereType<String>().join(' · ')),
                      trailing: _activeBadge(l.active),
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
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.pricing}/price-lists',
        data: {
          'name': _nameCtrl.text.trim(),
          'channel': _channel,
          'currency': _currency,
          'effectiveFrom': _from.toIso8601String().split('T').first,
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
                    value: _channel,
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
                Expanded(child: _currencyDropdown(_currency, (v) => setState(() => _currency = v))),
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
                  child: Text('No items yet.', style: TextStyle(color: cs.outline)));
            }
            final labels = ref
                    .watch(variantLabelsProvider(
                        variantIdsKey(items.map((it) => it.variantId))))
                    .valueOrNull ??
                const <String, VariantLabel>{};
            return ListView.separated(
              itemCount: items.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final it = items[i];
                final sku = variantSku(it.variantId, labels);
                return ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(variantDisplayName(it.variantId, labels),
                      style: const TextStyle(
                          fontWeight: FontWeight.w600, fontSize: 13)),
                  subtitle: Text(
                      '${sku.isNotEmpty ? '$sku  ·  ' : ''}min qty ${it.minQty.toStringAsFixed(0)}'),
                  trailing: Text(
                      '${priceList.currency ?? ''} ${it.price.toStringAsFixed(2)}',
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                );
              },
            );
          },
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('Close')),
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
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.pricing}/price-lists/${widget.priceListId}/items',
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
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
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

class _PromotionsTab extends ConsumerWidget {
  const _PromotionsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(promotionsProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        _addBar(context, 'New promotion',
            () => showDialog(context: context, builder: (_) => const _PromotionDialog())),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading promotions…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load promotions.'),
              onRetry: () => ref.invalidate(promotionsProvider),
            ),
            data: (promos) {
              if (promos.isEmpty) {
                return _empty(cs, Icons.local_offer_outlined, 'No promotions yet');
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: promos.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final p = promos[i];
                  final label = p.type == 'PERCENT'
                      ? '${p.value.toStringAsFixed(0)}% off'
                      : '${p.value.toStringAsFixed(2)} off';
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: cs.tertiaryContainer,
                        child: Icon(Icons.local_offer_outlined,
                            color: cs.onTertiaryContainer),
                      ),
                      title: Text(p.name,
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text([
                        label,
                        if (p.channel != null) p.channel,
                        if (p.minOrderAmount != null)
                          'min ${p.minOrderAmount!.toStringAsFixed(0)}',
                      ].whereType<String>().join(' · ')),
                      trailing: _activeBadge(p.active),
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

class _PromotionDialog extends ConsumerStatefulWidget {
  const _PromotionDialog();

  @override
  ConsumerState<_PromotionDialog> createState() => _PromotionDialogState();
}

class _PromotionDialogState extends ConsumerState<_PromotionDialog> {
  final _nameCtrl = TextEditingController();
  final _valueCtrl = TextEditingController();
  final _minCtrl = TextEditingController();
  String _type = 'PERCENT';
  String _channel = 'ALL';
  DateTime _starts = DateTime.now();
  DateTime? _ends;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _valueCtrl.dispose();
    _minCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final value = double.tryParse(_valueCtrl.text.trim());
    if (_nameCtrl.text.trim().isEmpty || value == null || value <= 0) {
      setState(() => _error = 'Enter a name and a positive value.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    try {
      final resp = await dio.post(
        '/${ApiConstants.pricing}/promotions',
        data: {
          'name': _nameCtrl.text.trim(),
          'type': _type,
          'value': value,
          if (_minCtrl.text.trim().isNotEmpty)
            'minOrderAmount': double.tryParse(_minCtrl.text.trim()),
          'channel': _channel,
          'startsAt': _starts.toIso8601String(),
          if (_ends != null) 'endsAt': _ends!.toIso8601String(),
        },
      );
      // Apply to all products by default so the promo is usable immediately.
      final promo = resp.data['data'] as Map<String, dynamic>;
      final promoId = promo['id'] as String?;
      if (promoId != null) {
        await dio.post(
          '/${ApiConstants.pricing}/promotions/$promoId/items',
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
                      value: _type,
                      decoration: const InputDecoration(labelText: 'Type'),
                      items: const [
                        DropdownMenuItem(
                            value: 'PERCENT', child: Text('% off')),
                        DropdownMenuItem(value: 'FLAT', child: Text('Flat off')),
                      ],
                      onChanged: (v) => setState(() => _type = v!),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _valueCtrl,
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      decoration: InputDecoration(
                          labelText: _type == 'PERCENT' ? 'Percent' : 'Amount'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      value: _channel,
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
                    child: TextField(
                      controller: _minCtrl,
                      keyboardType:
                          const TextInputType.numberWithOptions(decimal: true),
                      decoration:
                          const InputDecoration(labelText: 'Min order (opt)'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              _datePickerTile(context, 'Starts', _starts,
                  (d) => setState(() => _starts = d)),
              _datePickerTile(context, 'Ends (optional)', _ends,
                  (d) => setState(() => _ends = d)),
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
        _addBar(context, 'New VAT rate',
            () => showDialog(context: context, builder: (_) => const _VatRateDialog())),
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
                separatorBuilder: (_, __) => const SizedBox(height: 4),
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
                        child: Icon(Icons.percent,
                            color: cs.onSecondaryContainer),
                      ),
                      title: Text('${r.code} · ${r.name}',
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text(r.exempt
                          ? 'Exempt'
                          : '${r.rate.toStringAsFixed(2)}%'),
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
      'description': _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
      'effectiveFrom': _from.toIso8601String().split('T').first,
    };
    try {
      if (_isEdit) {
        await dio.put(
            '/${ApiConstants.pricing}/vat-rates/${widget.existing!.code}',
            data: body);
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
                  labelText: 'Code *', hintText: 'STANDARD'),
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
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
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
                context, 'Effective from', _from, (d) => setState(() => _from = d)),
          ],
        ),
      ),
      actions: _dialogActions(context, _loading, _submit, _isEdit ? 'Save' : 'Create'),
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

Widget _activeBadge(bool active) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: active ? Colors.green.shade100 : Colors.grey.shade200,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(active ? 'ACTIVE' : 'INACTIVE',
          style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: active ? Colors.green.shade800 : Colors.grey.shade700)),
    );

Widget _errorBox(BuildContext context, String? error) {
  if (error == null) return const SizedBox.shrink();
  final cs = Theme.of(context).colorScheme;
  return Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
          color: cs.errorContainer, borderRadius: BorderRadius.circular(8)),
      child: Text(error, style: TextStyle(color: cs.onErrorContainer)),
    ),
  );
}

Widget _currencyDropdown(String value, ValueChanged<String> onChanged) =>
    DropdownButtonFormField<String>(
      value: value,
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

Widget _datePickerTile(BuildContext context, String label, DateTime? value,
    ValueChanged<DateTime> onPicked) {
  return ListTile(
    contentPadding: EdgeInsets.zero,
    leading: const Icon(Icons.event_outlined),
    title: Text(value == null
        ? label
        : '$label: ${value.toIso8601String().split('T').first}'),
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
