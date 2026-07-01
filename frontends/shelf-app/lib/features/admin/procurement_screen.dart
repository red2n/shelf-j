import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'procurement_providers.dart';
import 'widgets/variant_picker.dart';

class ProcurementScreen extends ConsumerWidget {
  const ProcurementScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return DefaultTabController(
      length: 2,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
            child: Text('Procurement',
                style: Theme.of(context).textTheme.headlineMedium),
          ),
          const TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            tabs: [
              Tab(text: 'Purchase Orders'),
              Tab(text: 'Suppliers'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _PurchaseOrdersTab(),
                _SuppliersTab(),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ── Suppliers ────────────────────────────────────────────────────────────────

class _SuppliersTab extends ConsumerWidget {
  const _SuppliersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(suppliersProvider);
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
                  builder: (_) => const _SupplierDialog(),
                ),
                icon: const Icon(Icons.add),
                label: const Text('Add supplier'),
              ),
            ],
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading suppliers…'),
            error: (e, _) => ErrorView(
              message: 'Could not load suppliers.\n$e',
              onRetry: () => ref.invalidate(suppliersProvider),
            ),
            data: (suppliers) {
              if (suppliers.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.local_shipping_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 12),
                      const Text('No suppliers yet'),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: suppliers.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final s = suppliers[i];
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: cs.primaryContainer,
                        child: Icon(Icons.local_shipping_outlined,
                            color: cs.onPrimaryContainer),
                      ),
                      title: Text(s.name,
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text([
                        if (s.currency != null) s.currency,
                        '${s.paymentTermsDays}d terms',
                        if (s.vatRegistered) 'VAT ${s.vatNumber ?? 'reg'}',
                        if (s.countryCode != null) s.countryCode,
                      ].whereType<String>().join(' · ')),
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

class _SupplierDialog extends ConsumerStatefulWidget {
  const _SupplierDialog();

  @override
  ConsumerState<_SupplierDialog> createState() => _SupplierDialogState();
}

class _SupplierDialogState extends ConsumerState<_SupplierDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _vatCtrl = TextEditingController();
  final _termsCtrl = TextEditingController(text: '30');
  String _country = 'IN';
  String _currency = 'INR';
  bool _vatRegistered = false;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _vatCtrl.dispose();
    _termsCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/suppliers',
        data: {
          'name': _nameCtrl.text.trim(),
          'vatNumber': _vatCtrl.text.trim().isEmpty ? null : _vatCtrl.text.trim(),
          'vatRegistered': _vatRegistered,
          'countryCode': _country,
          'currency': _currency,
          'paymentTermsDays': int.tryParse(_termsCtrl.text.trim()) ?? 30,
        },
      );
      if (!mounted) return;
      ref.invalidate(suppliersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Supplier added.')));
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not add supplier: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Add supplier'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
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
                TextFormField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Supplier name *',
                    prefixIcon: Icon(Icons.business),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        value: _country,
                        decoration: const InputDecoration(labelText: 'Country'),
                        items: const [
                          DropdownMenuItem(value: 'IN', child: Text('India')),
                          DropdownMenuItem(value: 'US', child: Text('USA')),
                          DropdownMenuItem(value: 'GB', child: Text('UK')),
                          DropdownMenuItem(value: 'SG', child: Text('Singapore')),
                          DropdownMenuItem(value: 'AE', child: Text('UAE')),
                        ],
                        onChanged: (v) => setState(() => _country = v!),
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
                          DropdownMenuItem(value: 'SGD', child: Text('SGD')),
                          DropdownMenuItem(value: 'AED', child: Text('AED')),
                        ],
                        onChanged: (v) => setState(() => _currency = v!),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _termsCtrl,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'Payment terms (days)',
                    prefixIcon: Icon(Icons.calendar_today_outlined),
                  ),
                ),
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _vatRegistered,
                  onChanged: (v) => setState(() => _vatRegistered = v),
                  title: const Text('VAT registered'),
                ),
                if (_vatRegistered)
                  TextFormField(
                    controller: _vatCtrl,
                    decoration: const InputDecoration(labelText: 'VAT number'),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white))
              : const Text('Add'),
        ),
      ],
    );
  }
}

// ── Purchase Orders ──────────────────────────────────────────────────────────

class _PurchaseOrdersTab extends ConsumerWidget {
  const _PurchaseOrdersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(purchaseOrdersProvider);
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
                  builder: (_) => const _CreatePoDialog(),
                ),
                icon: const Icon(Icons.add),
                label: const Text('Create PO'),
              ),
            ],
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading purchase orders…'),
            error: (e, _) => ErrorView(
              message: 'Could not load purchase orders.\n$e',
              onRetry: () => ref.invalidate(purchaseOrdersProvider),
            ),
            data: (pos) {
              if (pos.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.receipt_long_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 12),
                      const Text('No purchase orders yet'),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: pos.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final po = pos[i];
                  return Card(
                    child: ListTile(
                      onTap: () => showDialog(
                        context: context,
                        builder: (_) => _PoDetailDialog(poId: po.id),
                      ),
                      leading: CircleAvatar(
                        backgroundColor: cs.secondaryContainer,
                        child: Icon(Icons.receipt_long_outlined,
                            color: cs.onSecondaryContainer),
                      ),
                      title: Row(
                        children: [
                          Text('#${_short(po.id)}',
                              style: const TextStyle(fontFamily: 'monospace')),
                          const SizedBox(width: 8),
                          _PoStatusBadge(po.status),
                        ],
                      ),
                      subtitle: Text([
                        '${po.currency} ${po.totalGross.toStringAsFixed(2)}',
                        if (po.expectedDelivery != null)
                          'ETA ${po.expectedDelivery}',
                      ].join(' · ')),
                      trailing: const Icon(Icons.chevron_right),
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

class _CreatePoDialog extends ConsumerStatefulWidget {
  const _CreatePoDialog();

  @override
  ConsumerState<_CreatePoDialog> createState() => _CreatePoDialogState();
}

class _CreatePoDialogState extends ConsumerState<_CreatePoDialog> {
  String? _supplierId;
  String? _storeId;
  String _currency = 'INR';
  DateTime? _eta;
  bool _loading = false;
  String? _error;

  Future<void> _submit() async {
    if (_supplierId == null || _storeId == null) {
      setState(() => _error = 'Pick a supplier and a store.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/purchase-orders',
        data: {
          'supplierId': _supplierId,
          'storeId': _storeId,
          'currency': _currency,
          if (_eta != null)
            'expectedDelivery':
                _eta!.toIso8601String().split('T').first,
        },
      );
      final po = resp.data['data'] as Map<String, dynamic>;
      if (!mounted) return;
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
      // Open the new PO so the user can add lines straight away.
      showDialog(
        context: context,
        builder: (_) => _PoDetailDialog(poId: po['id'] as String),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not create PO: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final suppliersAsync = ref.watch(suppliersProvider);
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Create purchase order'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: cs.errorContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child:
                    Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
              ),
              const SizedBox(height: 12),
            ],
            suppliersAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text('Suppliers failed: $e',
                  style: TextStyle(color: cs.error)),
              data: (suppliers) => DropdownButtonFormField<String>(
                value: _supplierId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Supplier *'),
                items: [
                  for (final s in suppliers)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() {
                  _supplierId = v;
                  final s = suppliers.firstWhere((e) => e.id == v);
                  if (s.currency != null) _currency = s.currency!;
                }),
              ),
            ),
            const SizedBox(height: 12),
            storesAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) =>
                  Text('Stores failed: $e', style: TextStyle(color: cs.error)),
              data: (stores) => DropdownButtonFormField<String>(
                value: _storeId,
                isExpanded: true,
                decoration:
                    const InputDecoration(labelText: 'Deliver to store *'),
                items: [
                  for (final s in stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              value: _currency,
              decoration: const InputDecoration(labelText: 'Currency'),
              items: const [
                DropdownMenuItem(value: 'INR', child: Text('INR')),
                DropdownMenuItem(value: 'USD', child: Text('USD')),
                DropdownMenuItem(value: 'GBP', child: Text('GBP')),
                DropdownMenuItem(value: 'SGD', child: Text('SGD')),
                DropdownMenuItem(value: 'AED', child: Text('AED')),
              ],
              onChanged: (v) => setState(() => _currency = v!),
            ),
            const SizedBox(height: 12),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.event_outlined),
              title: Text(_eta == null
                  ? 'Expected delivery (optional)'
                  : 'ETA ${_eta!.toIso8601String().split('T').first}'),
              trailing: const Icon(Icons.edit_calendar_outlined),
              onTap: () async {
                final now = DateTime.now();
                final picked = await showDatePicker(
                  context: context,
                  initialDate: now,
                  firstDate: now,
                  lastDate: now.add(const Duration(days: 365)),
                );
                if (picked != null) setState(() => _eta = picked);
              },
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white))
              : const Text('Create'),
        ),
      ],
    );
  }
}

class _PoDetailDialog extends ConsumerStatefulWidget {
  final String poId;
  const _PoDetailDialog({required this.poId});

  @override
  ConsumerState<_PoDetailDialog> createState() => _PoDetailDialogState();
}

class _PoDetailDialogState extends ConsumerState<_PoDetailDialog> {
  bool _submitting = false;
  String get poId => widget.poId;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final linesAsync = ref.watch(purchaseOrderLinesProvider(poId));
    final posAsync = ref.watch(purchaseOrdersProvider);
    final po = posAsync.maybeWhen(
      data: (pos) => pos.where((p) => p.id == poId).firstOrNull,
      orElse: () => null,
    );
    final isDraft = (po?.status.toUpperCase() ?? 'DRAFT') == 'DRAFT';

    return AlertDialog(
      title: Row(
        children: [
          Expanded(child: Text('PO #${_short(poId)}')),
          if (po != null) _PoStatusBadge(po.status),
        ],
      ),
      content: SizedBox(
        width: 480,
        child: linesAsync.when(
          loading: () =>
              const SizedBox(height: 140, child: LoadingView(label: 'Loading…')),
          error: (e, _) => SizedBox(
            height: 140,
            child: ErrorView(
              message: 'Could not load lines.\n$e',
              onRetry: () => ref.invalidate(purchaseOrderLinesProvider(poId)),
            ),
          ),
          data: (lines) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (lines.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Text('No lines yet.',
                      style: TextStyle(color: cs.outline)),
                )
              else
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 240),
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final l in lines)
                        ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          title: Text(_short(l.variantId, 14),
                              style: const TextStyle(
                                  fontFamily: 'monospace', fontSize: 12)),
                          subtitle: Text(
                              '${l.qty.toStringAsFixed(0)} × ${l.unitPrice.toStringAsFixed(2)}'
                              '${l.vatCode != null ? ' · ${l.vatCode}' : ''}'),
                          trailing: Text(
                              (l.qty * l.unitPrice).toStringAsFixed(2),
                              style:
                                  const TextStyle(fontWeight: FontWeight.bold)),
                        ),
                    ],
                  ),
                ),
              const Divider(),
              if (po != null)
                Row(
                  children: [
                    const Text('Total (gross)'),
                    const Spacer(),
                    Text('${po.currency} ${po.totalGross.toStringAsFixed(2)}',
                        style: const TextStyle(fontWeight: FontWeight.bold)),
                  ],
                ),
              const SizedBox(height: 8),
              if (isDraft)
                OutlinedButton.icon(
                  onPressed: () => showDialog(
                    context: context,
                    builder: (_) => _AddPoLineDialog(poId: poId),
                  ),
                  icon: const Icon(Icons.add),
                  label: const Text('Add line'),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Close'),
        ),
        if (isDraft)
          FilledButton.icon(
            onPressed: _submitting ? null : () => _submitPo(context, ref),
            icon: const Icon(Icons.send_outlined, size: 18),
            label: const Text('Submit'),
          )
        else
          FilledButton.icon(
            onPressed: po == null
                ? null
                : () {
                    Navigator.pop(context);
                    showDialog(
                      context: context,
                      builder: (_) =>
                          _ReceiveGoodsDialog(poId: poId, storeId: po.storeId),
                    );
                  },
            icon: const Icon(Icons.inventory_outlined, size: 18),
            label: const Text('Receive goods'),
          ),
      ],
    );
  }

  Future<void> _submitPo(BuildContext context, WidgetRef ref) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.purchase}/purchase-orders/$poId/submit');
      ref.invalidate(purchaseOrdersProvider);
      if (!context.mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Purchase order submitted.')));
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('Could not submit PO: $e'),
        backgroundColor: Theme.of(context).colorScheme.error,
      ));
    }
  }
}

class _AddPoLineDialog extends ConsumerStatefulWidget {
  final String poId;
  const _AddPoLineDialog({required this.poId});

  @override
  ConsumerState<_AddPoLineDialog> createState() => _AddPoLineDialogState();
}

class _AddPoLineDialogState extends ConsumerState<_AddPoLineDialog> {
  String? _productId;
  String? _variantId;
  final _qtyCtrl = TextEditingController(text: '1');
  final _priceCtrl = TextEditingController();
  String _vatCode = 'STANDARD';
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _qtyCtrl.dispose();
    _priceCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final qty = double.tryParse(_qtyCtrl.text.trim());
    final price = double.tryParse(_priceCtrl.text.trim());
    if (_variantId == null || qty == null || qty <= 0 || price == null || price <= 0) {
      setState(() => _error = 'Pick a variant and enter qty + unit price.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/lines',
        data: {
          'variantId': _variantId,
          'qty': qty,
          'unitPrice': price,
          'vatCode': _vatCode,
        },
      );
      if (!mounted) return;
      ref.invalidate(purchaseOrderLinesProvider(widget.poId));
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not add line: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Add PO line'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: cs.errorContainer,
                  borderRadius: BorderRadius.circular(8),
                ),
                child:
                    Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
              ),
              const SizedBox(height: 12),
            ],
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
                    controller: _qtyCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: 'Qty'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _priceCtrl,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Unit cost'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              value: _vatCode,
              decoration: const InputDecoration(labelText: 'VAT code'),
              items: const [
                DropdownMenuItem(value: 'STANDARD', child: Text('Standard')),
                DropdownMenuItem(value: 'REDUCED', child: Text('Reduced')),
                DropdownMenuItem(value: 'ZERO', child: Text('Zero')),
                DropdownMenuItem(value: 'EXEMPT', child: Text('Exempt')),
              ],
              onChanged: (v) => setState(() => _vatCode = v!),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white))
              : const Text('Add line'),
        ),
      ],
    );
  }
}

class _ReceiveGoodsDialog extends ConsumerStatefulWidget {
  final String poId;
  final String storeId;
  const _ReceiveGoodsDialog({required this.poId, required this.storeId});

  @override
  ConsumerState<_ReceiveGoodsDialog> createState() =>
      _ReceiveGoodsDialogState();
}

class _ReceiveGoodsDialogState extends ConsumerState<_ReceiveGoodsDialog> {
  final Map<String, double> _received = {};
  bool _loading = false;
  String? _error;

  Future<void> _submit(List<PurchaseOrderLine> lines) async {
    final received = [
      for (final l in lines)
        if ((_received[l.variantId] ?? l.qty) > 0)
          {
            'variantId': l.variantId,
            'qtyReceived': _received[l.variantId] ?? l.qty,
          },
    ];
    if (received.isEmpty) {
      setState(() => _error = 'Enter at least one received quantity.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/goods-receipts',
        data: {
          'poId': widget.poId,
          'storeId': widget.storeId,
          'lines': received,
        },
      );
      if (!mounted) return;
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Goods received — stock updated.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = 'Could not record receipt: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final linesAsync = ref.watch(purchaseOrderLinesProvider(widget.poId));
    return AlertDialog(
      title: const Text('Receive goods'),
      content: SizedBox(
        width: 440,
        child: linesAsync.when(
          loading: () =>
              const SizedBox(height: 120, child: LoadingView(label: 'Loading…')),
          error: (e, _) => SizedBox(
            height: 120,
            child: ErrorView(
              message: 'Could not load PO lines.\n$e',
              onRetry: () =>
                  ref.invalidate(purchaseOrderLinesProvider(widget.poId)),
            ),
          ),
          data: (lines) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
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
              Text('Confirm received quantities',
                  style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 280),
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final l in lines)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(_short(l.variantId, 14),
                                      style: const TextStyle(
                                          fontFamily: 'monospace',
                                          fontSize: 12)),
                                  Text('ordered ${l.qty.toStringAsFixed(0)}',
                                      style: TextStyle(
                                          fontSize: 11, color: cs.outline)),
                                ],
                              ),
                            ),
                            SizedBox(
                              width: 90,
                              child: TextFormField(
                                initialValue: l.qty.toStringAsFixed(0),
                                keyboardType: TextInputType.number,
                                decoration: const InputDecoration(
                                    labelText: 'Received', isDense: true),
                                onChanged: (v) => _received[l.variantId] =
                                    double.tryParse(v) ?? 0,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading
              ? null
              : () {
                  final lines =
                      ref.read(purchaseOrderLinesProvider(widget.poId)).value;
                  if (lines != null) _submit(lines);
                },
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white))
              : const Text('Confirm receipt'),
        ),
      ],
    );
  }
}

class _PoStatusBadge extends StatelessWidget {
  final String status;
  const _PoStatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    Color bg;
    Color fg;
    switch (status.toUpperCase()) {
      case 'DRAFT':
        bg = Colors.grey.shade200;
        fg = Colors.grey.shade800;
        break;
      case 'SUBMITTED':
        bg = Colors.blue.shade100;
        fg = Colors.blue.shade800;
        break;
      case 'RECEIVED':
      case 'CLOSED':
        bg = Colors.green.shade100;
        fg = Colors.green.shade800;
        break;
      default:
        bg = Colors.amber.shade100;
        fg = Colors.amber.shade900;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration:
          BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Text(status,
          style:
              TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg)),
    );
  }
}

String _short(String s, [int n = 8]) =>
    s.length > n ? '${s.substring(0, n)}…' : s;
