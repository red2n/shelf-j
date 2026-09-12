import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/theme.dart';
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
      length: 3,
      child: Builder(
        // A Builder gives this subtree a context below DefaultTabController,
        // so DefaultTabController.of(context) below can find it.
        builder: (context) {
          final tabController = DefaultTabController.of(context);
          return Scaffold(
            body: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
                  child: Text(
                    'Procurement',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                ),
                const TabBar(
                  isScrollable: true,
                  tabAlignment: TabAlignment.start,
                  tabs: [
                    Tab(text: 'Purchase Orders'),
                    Tab(text: 'Invoices'),
                    Tab(text: 'Suppliers'),
                  ],
                ),
                const Expanded(
                  child: TabBarView(
                    children: [
                      _PurchaseOrdersTab(),
                      _SupplierInvoicesTab(),
                      _SuppliersTab(),
                    ],
                  ),
                ),
              ],
            ),
            // One primary action, following whichever tab is active, instead
            // of a separate "Create PO" / "Add supplier" button duplicated
            // per tab.
            floatingActionButton: ListenableBuilder(
              listenable: tabController,
              builder: (context, _) => tabController.index == 2
                  ? FloatingActionButton.extended(
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => const _SupplierDialog(),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('Add supplier'),
                    )
                  : FloatingActionButton.extended(
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => const _CreatePoDialog(),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('Create PO'),
                    ),
            ),
          );
        },
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
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        const SizedBox(height: 12),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading suppliers…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load suppliers.'),
              onRetry: () => ref.invalidate(suppliersProvider),
            ),
            data: (suppliers) {
              if (suppliers.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.local_shipping_outlined,
                        size: 64,
                        color: cs.outlineVariant,
                      ),
                      const SizedBox(height: 12),
                      const Text('No suppliers yet'),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: suppliers.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final s = suppliers[i];
                  return Card(
                    child: ListTile(
                      leading: CircleAvatar(
                        backgroundColor: cs.primaryContainer,
                        child: Icon(
                          Icons.local_shipping_outlined,
                          color: cs.onPrimaryContainer,
                        ),
                      ),
                      title: Text(
                        s.name,
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      subtitle: Text(
                        [
                          if (s.currency != null) s.currency,
                          '${s.paymentTermsDays}d terms',
                          if (s.vatRegistered) 'VAT ${s.vatNumber ?? 'reg'}',
                          if (s.countryCode != null) s.countryCode,
                        ].whereType<String>().join(' · '),
                      ),
                      trailing: isManager
                          ? IconButton(
                              tooltip: 'Edit supplier',
                              icon: const Icon(Icons.edit_outlined),
                              onPressed: () => showDialog<void>(
                                context: context,
                                builder: (_) => _SupplierDialog(existing: s),
                              ),
                            )
                          : null,
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

// ── Supplier invoices: the three-way match ───────────────────────────────────

/// Ordered against received against invoiced, per line.
///
/// A status badge alone answers the wrong question. A buyer told an invoice is
/// FLAGGED still has to know *which* line disagreed and by how much before they
/// can ring the supplier — so the three figures sit side by side, and the
/// flagged ones lead.
class _SupplierInvoicesTab extends ConsumerWidget {
  const _SupplierInvoicesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(supplierInvoicesProvider);
    final cs = Theme.of(context).colorScheme;
    return async.when(
      loading: () => const LoadingView(label: 'Loading invoices…'),
      error: (e, _) => ErrorView(
        message: friendlyError(
          e,
          fallback: 'Could not load supplier invoices.',
        ),
        onRetry: () => ref.invalidate(supplierInvoicesProvider),
      ),
      data: (invoices) {
        if (invoices.isEmpty) {
          return Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.receipt_long_outlined,
                  size: 64,
                  color: cs.outlineVariant,
                ),
                const SizedBox(height: 12),
                const Text('No supplier invoices yet'),
                const SizedBox(height: 4),
                Text(
                  'Capture one from a purchase order to match it',
                  style: TextStyle(color: cs.outline, fontSize: 12),
                ),
              ],
            ),
          );
        }
        // Flagged first: the whole point of the control is the exceptions, and a
        // list ordered by date buries them behind the ones nobody needs to read.
        final sorted = [...invoices]
          ..sort((a, b) {
            if (a.flagged == b.flagged) return 0;
            return a.flagged ? -1 : 1;
          });
        return ListView.separated(
          padding: const EdgeInsets.all(16),
          itemCount: sorted.length,
          separatorBuilder: (_, _) => const SizedBox(height: 8),
          itemBuilder: (_, i) => _InvoiceCard(sorted[i]),
        );
      },
    );
  }
}

class _InvoiceCard extends StatelessWidget {
  final SupplierInvoice invoice;
  const _InvoiceCard(this.invoice);

  @override
  Widget build(BuildContext context) {
    final flagged = invoice.flagged;
    return Card(
      child: ExpansionTile(
        // Flagged invoices open by default. A variance the buyer has to click to
        // discover is a variance that waits until the payment run.
        initiallyExpanded: flagged,
        leading: Icon(
          flagged ? Icons.warning_amber_rounded : Icons.check_circle_outline,
          color: flagged ? context.status.warning : context.status.success,
        ),
        title: Row(
          children: [
            Text(
              invoice.invoiceNumber,
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(width: 8),
            _InvoiceStatusBadge(invoice.status),
          ],
        ),
        subtitle: Text(
          [
            AppFormat.money(
              invoice.grossAmount,
              currencyCode: invoice.currency,
            ),
            if (invoice.invoiceDate != null) invoice.invoiceDate!,
            'PO ${_short(invoice.poId, 8)}',
          ].join(' · '),
        ),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _MatchHeaderRow(),
                const Divider(height: 12),
                for (final l in invoice.lines) _MatchRow(l, invoice.currency),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MatchHeaderRow extends StatelessWidget {
  const _MatchHeaderRow();

  @override
  Widget build(BuildContext context) {
    const style = TextStyle(fontSize: 11, fontWeight: FontWeight.w600);
    return const Row(
      children: [
        Expanded(flex: 3, child: Text('Variant', style: style)),
        Expanded(
          child: Text('Ordered', style: style, textAlign: TextAlign.right),
        ),
        Expanded(
          child: Text('Received', style: style, textAlign: TextAlign.right),
        ),
        Expanded(
          child: Text('Invoiced', style: style, textAlign: TextAlign.right),
        ),
        Expanded(
          flex: 2,
          child: Text('Price', style: style, textAlign: TextAlign.right),
        ),
      ],
    );
  }
}

class _MatchRow extends StatelessWidget {
  final InvoiceMatchLine line;
  final String currency;
  const _MatchRow(this.line, this.currency);

  @override
  Widget build(BuildContext context) {
    final bad = !line.matched;
    final warn = context.status.warning;
    final num = TextStyle(
      fontSize: 12,
      fontFamily: 'monospace',
      color: bad ? warn : null,
      fontWeight: bad ? FontWeight.bold : null,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                flex: 3,
                child: Text(
                  _short(line.variantId, 14),
                  style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                ),
              ),
              Expanded(
                child: Text(
                  _trim(line.qtyOrdered),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
              Expanded(
                child: Text(
                  _trim(line.qtyReceived),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
              Expanded(
                child: Text(
                  _trim(line.qtyInvoiced),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
              Expanded(
                flex: 2,
                child: Text(
                  // Both prices when they differ, so the buyer can see the gap
                  // rather than being told there is one.
                  line.orderedUnitPrice != null &&
                          line.orderedUnitPrice != line.invoicedUnitPrice
                      ? '${_trim(line.orderedUnitPrice!)} → ${_trim(line.invoicedUnitPrice)}'
                      : _trim(line.invoicedUnitPrice),
                  style: num,
                  textAlign: TextAlign.right,
                ),
              ),
            ],
          ),
          if (bad)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [for (final v in line.variances) _VarianceChip(v)],
              ),
            ),
        ],
      ),
    );
  }
}

/// A variance in words. The codes are precise and unreadable; a buyer chasing a
/// supplier needs the sentence, not the constant.
class _VarianceChip extends StatelessWidget {
  final String code;
  const _VarianceChip(this.code);

  static const _labels = {
    'INVOICED_ABOVE_RECEIVED': 'Billed for more than arrived',
    'NOT_RECEIVED': 'Nothing received yet',
    'NOT_ON_ORDER': 'Not on the purchase order',
    'PRICE_ABOVE_ORDER': 'Charged above the agreed price',
    'PRICE_BELOW_ORDER': 'Charged below the agreed price',
  };

  @override
  Widget build(BuildContext context) {
    final warn = context.status.warning;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: warn.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(
        _labels[code] ?? code,
        style: TextStyle(
          fontSize: 11,
          color: warn,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _InvoiceStatusBadge extends StatelessWidget {
  final String status;
  const _InvoiceStatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    final flagged = status == 'FLAGGED';
    final fg = flagged ? context.status.warning : context.status.success;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: fg.withValues(alpha: 0.18),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        status,
        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg),
      ),
    );
  }
}

/// Add a supplier, or — with [existing] — correct one. Terms, VAT number,
/// country and currency were fixed at creation until SJ-D34; a supplier
/// created in the wrong currency was wrong for every order ever raised
/// against it, and the only fix was a second supplier.
class _SupplierDialog extends ConsumerStatefulWidget {
  const _SupplierDialog({this.existing});
  final Supplier? existing;

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

  static const _countries = {
    'IN': 'India',
    'US': 'USA',
    'GB': 'UK',
    'SG': 'Singapore',
    'AE': 'UAE',
  };
  static const _currencies = ['INR', 'USD', 'GBP', 'SGD', 'AED'];

  bool get _editing => widget.existing != null;

  /// The picker's choices, plus whatever the supplier already has — a JPY
  /// supplier must open in JPY, not in the first currency on the list.
  List<DropdownMenuItem<String>> _countryItems() => [
    for (final e in _countries.entries)
      DropdownMenuItem(value: e.key, child: Text(e.value)),
    if (!_countries.containsKey(_country))
      DropdownMenuItem(value: _country, child: Text(_country)),
  ];

  List<DropdownMenuItem<String>> _currencyItems() => [
    for (final c in _currencies) DropdownMenuItem(value: c, child: Text(c)),
    if (!_currencies.contains(_currency))
      DropdownMenuItem(value: _currency, child: Text(_currency)),
  ];

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    if (e != null) {
      _nameCtrl.text = e.name;
      _vatCtrl.text = e.vatNumber ?? '';
      _termsCtrl.text = e.paymentTermsDays.toString();
      _country = e.countryCode ?? _country;
      _currency = e.currency ?? _currency;
      _vatRegistered = e.vatRegistered;
    }
  }

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
      final dio = ref.read(apiClientProvider).dio;
      final data = {
        'name': _nameCtrl.text.trim(),
        'vatNumber': _vatCtrl.text.trim().isEmpty ? null : _vatCtrl.text.trim(),
        'vatRegistered': _vatRegistered,
        'countryCode': _country,
        'currency': _currency,
        'paymentTermsDays': int.tryParse(_termsCtrl.text.trim()) ?? 30,
      };
      if (_editing) {
        await dio.put(
          '/${ApiConstants.purchase}/suppliers/${widget.existing!.id}',
          data: data,
        );
      } else {
        await dio.post('/${ApiConstants.purchase}/suppliers', data: data);
      }
      if (!mounted) return;
      ref.invalidate(suppliersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_editing ? 'Supplier updated.' : 'Supplier added.'),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(
          e,
          fallback: _editing
              ? 'Could not update supplier.'
              : 'Could not add supplier.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(_editing ? 'Edit supplier' : 'Add supplier'),
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
                    child: Text(
                      _error!,
                      style: TextStyle(color: cs.onErrorContainer),
                    ),
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
                        initialValue: _country,
                        decoration: const InputDecoration(labelText: 'Country'),
                        items: _countryItems(),
                        onChanged: (v) => setState(() => _country = v!),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _currency,
                        decoration: const InputDecoration(
                          labelText: 'Currency',
                        ),
                        items: _currencyItems(),
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : Text(_editing ? 'Save' : 'Add'),
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
        const SizedBox(height: 12),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading purchase orders…'),
            error: (e, _) => ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load purchase orders.',
              ),
              onRetry: () => ref.invalidate(purchaseOrdersProvider),
            ),
            data: (pos) {
              if (pos.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        Icons.receipt_long_outlined,
                        size: 64,
                        color: cs.outlineVariant,
                      ),
                      const SizedBox(height: 12),
                      const Text('No purchase orders yet'),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(16),
                itemCount: pos.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
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
                        child: Icon(
                          Icons.receipt_long_outlined,
                          color: cs.onSecondaryContainer,
                        ),
                      ),
                      title: Row(
                        children: [
                          Text(
                            '#${_short(po.id)}',
                            style: const TextStyle(fontFamily: 'monospace'),
                          ),
                          const SizedBox(width: 8),
                          _PoStatusBadge(po.status),
                        ],
                      ),
                      subtitle: Text(
                        [
                          AppFormat.money(
                            po.totalGross,
                            currencyCode: po.currency,
                          ),
                          if (po.expectedDelivery != null)
                            'ETA ${po.expectedDelivery}',
                        ].join(' · '),
                      ),
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
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/purchase-orders',
            data: {
              'supplierId': _supplierId,
              'storeId': _storeId,
              'currency': _currency,
              if (_eta != null)
                'expectedDelivery': _eta!.toIso8601String().split('T').first,
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
        _error = friendlyError(e, fallback: 'Could not create PO.');
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
                child: Text(
                  _error!,
                  style: TextStyle(color: cs.onErrorContainer),
                ),
              ),
              const SizedBox(height: 12),
            ],
            suppliersAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text(
                friendlyError(e, fallback: 'Could not load suppliers.'),
                style: TextStyle(color: cs.error),
              ),
              data: (suppliers) => DropdownButtonFormField<String>(
                initialValue: _supplierId,
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
              error: (e, _) => Text(
                friendlyError(e, fallback: 'Could not load stores.'),
                style: TextStyle(color: cs.error),
              ),
              data: (stores) => DropdownButtonFormField<String>(
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Deliver to store *',
                ),
                items: [
                  for (final s in stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: _currency,
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
              title: Text(
                _eta == null
                    ? 'Expected delivery (optional)'
                    : 'ETA ${_eta!.toIso8601String().split('T').first}',
              ),
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
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
    final status = po?.status.toUpperCase() ?? 'DRAFT';
    final isDraft = status == 'DRAFT';
    // Receivable is now two states, not "anything that isn't a draft". The button used to offer
    // itself on a CANCELLED or already-RECEIVED order, which could only ever end in a 400.
    final isReceivable =
        status == 'SUBMITTED' || status == 'PARTIALLY_RECEIVED';
    final isPartial = status == 'PARTIALLY_RECEIVED';
    // Above the raiser's own spend authority: nobody entitled to commit this much has agreed yet,
    // and until they do the supplier has not been sent anything.
    final isPendingApproval = status == 'PENDING_APPROVAL';
    // Goods can go back once something arrived: received, partly received, or short-closed.
    final isReturnable =
        status == 'RECEIVED' ||
        status == 'PARTIALLY_RECEIVED' ||
        status == 'CLOSED';
    final progressAsync = isDraft
        ? const AsyncValue<List<PurchaseOrderLineProgress>>.data([])
        : ref.watch(purchaseOrderProgressProvider(poId));
    final progress = progressAsync.asData?.value ?? const [];

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
          loading: () => const SizedBox(
            height: 140,
            child: LoadingView(label: 'Loading…'),
          ),
          error: (e, _) => SizedBox(
            height: 140,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load lines.'),
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
                  child: Text(
                    'No lines yet.',
                    style: TextStyle(color: cs.outline),
                  ),
                )
              else
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 240),
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final l in lines)
                        Builder(
                          builder: (context) {
                            final p = progress
                                .where((x) => x.variantId == l.variantId)
                                .firstOrNull;
                            final owed = p?.qtyOutstanding ?? 0;
                            return ListTile(
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              title: Text(
                                _short(l.variantId, 14),
                                style: const TextStyle(
                                  fontFamily: 'monospace',
                                  fontSize: 12,
                                ),
                              ),
                              subtitle: Text(
                                [
                                  // The unit price is shown at its own precision, not the
                                  // currency's: a trade price of 0.0125 per screw is ordinary, and
                                  // rounding it to the penny here would misreport the line by 25%.
                                  '${l.qty.toStringAsFixed(0)} × ${_trim(l.unitPrice)}',
                                  if (l.vatCode != null) l.vatCode!,
                                  // What is still owed, which the status alone cannot say.
                                  if (p != null && owed > 0)
                                    '${owed.toStringAsFixed(0)} outstanding',
                                  if (p != null && owed == 0 && !isDraft)
                                    'complete',
                                  if (p != null && p.qtyReturned > 0)
                                    '${p.qtyReturned.toStringAsFixed(0)} returned',
                                ].join(' · '),
                                style: TextStyle(
                                  color: owed > 0
                                      ? context.status.warning
                                      : null,
                                ),
                              ),
                              trailing: Text(
                                AppFormat.money(
                                  l.qty * l.unitPrice,
                                  currencyCode: po?.currency,
                                ),
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            );
                          },
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
                    Text(
                      AppFormat.money(po.totalGross, currencyCode: po.currency),
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
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
              if (isReturnable)
                _VendorReturnsSection(poId: poId, currency: po?.currency),
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
        else if (isPendingApproval) ...[
          // Rejecting needs no spend authority — refusing to commit money is not a commitment —
          // so it is offered to anyone who can see the order. The server still decides whether
          // this caller may approve, and says so if not.
          TextButton.icon(
            onPressed: () => showDialog(
              context: context,
              builder: (_) => _RejectPoDialog(poId: poId),
            ),
            icon: const Icon(Icons.block_outlined, size: 18),
            label: const Text('Reject'),
          ),
          FilledButton.icon(
            onPressed: _submitting ? null : () => _approvePo(context, ref),
            icon: const Icon(Icons.check_circle_outline, size: 18),
            label: const Text('Approve'),
          ),
        ] else ...[
          // Abandoning the balance is a deliberate act with a reason, so it sits beside the
          // receive action rather than hiding in a menu — but only while there is a balance.
          if (isPartial)
            TextButton.icon(
              onPressed: () => showDialog(
                context: context,
                builder: (_) => _CloseShortDialog(poId: poId),
              ),
              icon: const Icon(Icons.do_not_disturb_on_outlined, size: 18),
              label: const Text('Close short'),
            ),
          if (isReturnable)
            TextButton.icon(
              key: const Key('po-return-to-vendor'),
              onPressed: () => showDialog(
                context: context,
                builder: (_) =>
                    _ReturnToVendorDialog(poId: poId, currency: po?.currency),
              ),
              icon: const Icon(Icons.undo_outlined, size: 18),
              label: const Text('Return to vendor'),
            ),
          if (isReceivable)
            FilledButton.icon(
              onPressed: po == null
                  ? null
                  : () {
                      Navigator.pop(context);
                      showDialog(
                        context: context,
                        builder: (_) => _ReceiveGoodsDialog(
                          poId: poId,
                          storeId: po.storeId,
                        ),
                      );
                    },
              icon: const Icon(Icons.inventory_outlined, size: 18),
              label: Text(isPartial ? 'Receive balance' : 'Receive goods'),
            ),
        ],
      ],
    );
  }

  Future<void> _submitPo(BuildContext context, WidgetRef ref) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.purchase}/purchase-orders/$poId/submit');
      ref.invalidate(purchaseOrdersProvider);
      if (!context.mounted) return;
      Navigator.pop(context);
      // The server decides which of the two happened, so the message reads the status back rather
      // than assuming. Telling a buyer their order went to the supplier when it is actually
      // waiting for a manager is the one thing this screen must not do.
      final held = (resp.data['data'] as Map?)?['status'] == 'PENDING_APPROVAL';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            held
                ? 'Above your spend authority — sent for approval.'
                : 'Purchase order submitted.',
          ),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(friendlyError(e, fallback: 'Could not submit PO.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  Future<void> _approvePo(BuildContext context, WidgetRef ref) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.purchase}/purchase-orders/$poId/approve');
      ref.invalidate(purchaseOrdersProvider);
      if (!context.mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Approved — the order is with the supplier.'),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      // The server's own message names both figures and the currency, which is the only useful
      // thing to show someone whose authority fell short — so it is surfaced rather than replaced.
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            friendlyError(e, fallback: 'Could not approve this order.'),
          ),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }
}

/// Rejects a purchase order that is waiting for someone's spend authority.
///
/// The reason is required by the server and required here, for the same purpose: a rejection sends
/// the order back to DRAFT for the buyer to correct, and "no" with no explanation leaves them with
/// work to do and no idea what to change.
class _RejectPoDialog extends ConsumerStatefulWidget {
  final String poId;
  const _RejectPoDialog({required this.poId});

  @override
  ConsumerState<_RejectPoDialog> createState() => _RejectPoDialogState();
}

class _RejectPoDialogState extends ConsumerState<_RejectPoDialog> {
  final _reasonCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final reason = _reasonCtrl.text.trim();
    if (reason.isEmpty) {
      setState(() => _error = 'Say why, so the buyer knows what to change.');
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
            '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/reject',
            data: {'reason': reason},
          );
      if (!mounted) return;
      ref.invalidate(purchaseOrdersProvider);
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Rejected — the order is back with the buyer.'),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not reject this order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Reject purchase order'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'The order goes back to DRAFT so it can be corrected and resubmitted. The '
              'rejection stays in its approval history either way.',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _reasonCtrl,
              autofocus: true,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Reason',
                hintText: 'e.g. get a second quote first',
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
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: const Text('Reject'),
        ),
      ],
    );
  }
}

/// Abandons the undelivered balance of a partly received purchase order.
///
/// A reason is required for the same purpose it is on a cancellation: without
/// one, a short-closed order is indistinguishable next quarter from one the
/// supplier fulfilled, and the supplier is the party that has to answer for it.
class _CloseShortDialog extends ConsumerStatefulWidget {
  final String poId;
  const _CloseShortDialog({required this.poId});

  @override
  ConsumerState<_CloseShortDialog> createState() => _CloseShortDialogState();
}

class _CloseShortDialogState extends ConsumerState<_CloseShortDialog> {
  final _reasonCtrl = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_reasonCtrl.text.trim().isEmpty) {
      setState(() => _error = 'Say why the balance is being abandoned.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/purchase-orders/${widget.poId}/close',
            data: {'reason': _reasonCtrl.text.trim()},
          );
      ref.invalidate(purchaseOrdersProvider);
      ref.invalidate(purchaseOrderProgressProvider(widget.poId));
      if (!mounted) return;
      Navigator.pop(context);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not close the order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Close short'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(_error!, style: TextStyle(color: cs.error)),
              ),
            Text(
              'The undelivered balance will be written off and the order marked '
              'CLOSED. What has already arrived stays received — this is not a '
              'cancellation.',
              style: TextStyle(color: cs.outline, fontSize: 13),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _reasonCtrl,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Reason *'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: Text(_saving ? 'Closing…' : 'Close short'),
        ),
      ],
    );
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
    if (_variantId == null ||
        qty == null ||
        qty <= 0 ||
        price == null ||
        price <= 0) {
      setState(() => _error = 'Pick a variant and enter qty + unit price.');
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
        _error = friendlyError(e, fallback: 'Could not add line.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Dialog.fullscreen(
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.close),
            tooltip: 'Cancel',
            onPressed: _loading ? null : () => Navigator.pop(context),
          ),
          title: const Text('Add PO line'),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Center(
                child: FilledButton(
                  onPressed: _loading ? null : _submit,
                  child: _loading
                      ? SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: cs.onPrimary,
                          ),
                        )
                      : const Text('Add line'),
                ),
              ),
            ),
          ],
        ),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 480),
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
                      child: Text(
                        _error!,
                        style: TextStyle(color: cs.onErrorContainer),
                      ),
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
                          keyboardType: const TextInputType.numberWithOptions(
                            decimal: true,
                          ),
                          decoration: const InputDecoration(
                            labelText: 'Unit cost',
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    initialValue: _vatCode,
                    decoration: const InputDecoration(labelText: 'VAT code'),
                    items: const [
                      DropdownMenuItem(
                        value: 'STANDARD',
                        child: Text('Standard'),
                      ),
                      DropdownMenuItem(
                        value: 'REDUCED',
                        child: Text('Reduced'),
                      ),
                      DropdownMenuItem(value: 'ZERO', child: Text('Zero')),
                      DropdownMenuItem(value: 'EXEMPT', child: Text('Exempt')),
                    ],
                    onChanged: (v) => setState(() => _vatCode = v!),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The returns raised against an order, each with its debit note and, once the
/// supplier has answered, its credit note (07.8). A manager records the credit
/// note here; nothing edits or deletes a return.
class _VendorReturnsSection extends ConsumerWidget {
  const _VendorReturnsSection({required this.poId, this.currency});
  final String poId;
  final String? currency;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final returns = ref.watch(vendorReturnsProvider(poId));
    return returns.when(
      loading: () => const SizedBox.shrink(),
      error: (e, _) => Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(
          friendlyError(e, fallback: 'Could not load returns.'),
          style: TextStyle(color: cs.error, fontSize: 12),
        ),
      ),
      data: (rows) => rows.isEmpty
          ? const SizedBox.shrink()
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Divider(),
                Text(
                  'Returned to vendor',
                  style: Theme.of(context).textTheme.labelLarge,
                ),
                for (final r in rows)
                  ListTile(
                    key: Key('vendor-return-${r.id}'),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(
                      r.credited
                          ? Icons.check_circle_outline
                          : Icons.undo_outlined,
                      color: r.credited ? cs.primary : cs.tertiary,
                    ),
                    title: Text(
                      '${r.debitNoteNumber} · ${vendorReturnReasons[r.reason] ?? r.reason}',
                    ),
                    subtitle: Text(
                      [
                        for (final l in r.lines)
                          '${_trim(l.qty)} × ${_trim(l.unitPrice)}',
                        r.credited
                            ? 'credit note ${r.creditNoteNumber} · ${r.creditNoteDate}'
                            : "awaiting the supplier's credit note",
                      ].join(' · '),
                    ),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          AppFormat.money(
                            r.grossAmount,
                            currencyCode: currency,
                          ),
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        if (!r.credited)
                          TextButton(
                            key: Key('vendor-return-credit-${r.id}'),
                            onPressed: () => showDialog(
                              context: context,
                              builder: (_) => _RecordCreditNoteDialog(
                                ret: r,
                                currency: currency,
                              ),
                            ),
                            child: const Text('Credit note'),
                          ),
                      ],
                    ),
                  ),
              ],
            ),
    );
  }
}

/// Sends goods back against a received order (07.8): what and how many, and
/// why. The server prices the debit note at the order's own prices and refuses
/// more than was received less what already went back.
class _ReturnToVendorDialog extends ConsumerStatefulWidget {
  final String poId;
  final String? currency;
  const _ReturnToVendorDialog({required this.poId, this.currency});

  @override
  ConsumerState<_ReturnToVendorDialog> createState() =>
      _ReturnToVendorDialogState();
}

class _ReturnToVendorDialogState extends ConsumerState<_ReturnToVendorDialog> {
  final Map<String, double> _qty = {};
  String _reason = 'DAMAGED';
  final _notes = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _notes.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final lines = [
      for (final e in _qty.entries)
        if (e.value > 0) {'variantId': e.key, 'qty': e.value},
    ];
    if (lines.isEmpty) {
      setState(() => _error = 'Enter at least one quantity to send back.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/vendor-returns',
            data: {
              'poId': widget.poId,
              'reason': _reason,
              if (_notes.text.trim().isNotEmpty) 'notes': _notes.text.trim(),
              'lines': lines,
            },
          );
      if (!mounted) return;
      ref.invalidate(vendorReturnsProvider(widget.poId));
      ref.invalidate(purchaseOrderProgressProvider(widget.poId));
      Navigator.pop(context);
      final number = (resp.data['data'] as Map?)?['debitNoteNumber'];
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Goods returned — debit note $number raised.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not raise the return.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final progressAsync = ref.watch(purchaseOrderProgressProvider(widget.poId));
    return AlertDialog(
      title: const Text('Return to vendor'),
      content: SizedBox(
        width: 460,
        child: progressAsync.when(
          loading: () => const SizedBox(
            height: 120,
            child: LoadingView(label: 'Loading…'),
          ),
          error: (e, _) => SizedBox(
            height: 120,
            child: ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load what was received.',
              ),
              onRetry: () =>
                  ref.invalidate(purchaseOrderProgressProvider(widget.poId)),
            ),
          ),
          data: (progress) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  key: const Key('rtv-error'),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _error!,
                    style: TextStyle(color: cs.onErrorContainer),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              DropdownButtonFormField<String>(
                key: const Key('rtv-reason'),
                initialValue: _reason,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Reason'),
                items: [
                  for (final e in vendorReturnReasons.entries)
                    DropdownMenuItem(value: e.key, child: Text(e.value)),
                ],
                onChanged: (v) => setState(() => _reason = v ?? 'DAMAGED'),
              ),
              const SizedBox(height: 8),
              Text(
                'Quantity to send back',
                style: Theme.of(context).textTheme.labelLarge,
              ),
              const SizedBox(height: 4),
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 220),
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final p in progress)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        child: Row(
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    _short(p.variantId, 14),
                                    style: const TextStyle(
                                      fontFamily: 'monospace',
                                      fontSize: 12,
                                    ),
                                  ),
                                  Text(
                                    'received ${_trim(p.qtyReceived)} · returned ${_trim(p.qtyReturned)} · '
                                    'up to ${_trim(p.qtyReturnable)} can go back',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: cs.outline,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            SizedBox(
                              width: 90,
                              child: TextField(
                                key: Key('rtv-qty-${p.variantId}'),
                                enabled: p.qtyReturnable > 0,
                                keyboardType:
                                    const TextInputType.numberWithOptions(
                                      decimal: true,
                                    ),
                                decoration: const InputDecoration(
                                  isDense: true,
                                  hintText: '0',
                                ),
                                onChanged: (v) =>
                                    _qty[p.variantId] = double.tryParse(v) ?? 0,
                              ),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
              TextField(
                key: const Key('rtv-notes'),
                controller: _notes,
                decoration: const InputDecoration(
                  labelText: 'Notes',
                  hintText: 'e.g. three cases crushed in transit',
                ),
              ),
              const SizedBox(height: 8),
              Text(
                "The debit note is priced at the order's prices. Stock leaves the store when the return is raised; the purchase order itself is unchanged.",
                style: TextStyle(fontSize: 12, color: cs.outline),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton.icon(
          key: const Key('rtv-submit'),
          onPressed: _loading ? null : _submit,
          icon: const Icon(Icons.undo_outlined, size: 18),
          label: const Text('Send back'),
        ),
      ],
    );
  }
}

/// Records the supplier's credit note against a return, closing it.
class _RecordCreditNoteDialog extends ConsumerStatefulWidget {
  final VendorReturn ret;
  final String? currency;
  const _RecordCreditNoteDialog({required this.ret, this.currency});

  @override
  ConsumerState<_RecordCreditNoteDialog> createState() =>
      _RecordCreditNoteDialogState();
}

class _RecordCreditNoteDialogState
    extends ConsumerState<_RecordCreditNoteDialog> {
  final _number = TextEditingController();
  final _date = TextEditingController(
    text: DateTime.now().toIso8601String().split('T').first,
  );
  late final TextEditingController _amount = TextEditingController(
    text: widget.ret.grossAmount.toStringAsFixed(2),
  );
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _number.dispose();
    _date.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.purchase}/vendor-returns/${widget.ret.id}/credit',
            data: {
              'creditNoteNumber': _number.text.trim(),
              'creditNoteDate': _date.text.trim(),
              if (_amount.text.trim().isNotEmpty)
                'amount': double.tryParse(_amount.text.trim()),
            },
          );
      if (!mounted) return;
      ref.invalidate(vendorReturnsProvider(widget.ret.poId));
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            'Credit note recorded against ${widget.ret.debitNoteNumber}.',
          ),
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(
          e,
          fallback: 'Could not record the credit note.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Credit note for ${widget.ret.debitNoteNumber}'),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              key: const Key('credit-number'),
              controller: _number,
              decoration: const InputDecoration(
                labelText: 'Credit note number',
              ),
            ),
            TextField(
              key: const Key('credit-date'),
              controller: _date,
              decoration: const InputDecoration(
                labelText: 'Credit note date',
                helperText: 'YYYY-MM-DD',
              ),
            ),
            TextField(
              key: const Key('credit-amount'),
              controller: _amount,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              decoration: InputDecoration(
                labelText: 'Amount credited',
                helperText:
                    'The debit note asked for ${AppFormat.money(widget.ret.grossAmount, currencyCode: widget.currency)}',
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: const Key('credit-error'),
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
          key: const Key('credit-submit'),
          onPressed: _loading ? null : _submit,
          child: const Text('Record'),
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
      await ref
          .read(apiClientProvider)
          .dio
          .post(
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
        _error = friendlyError(e, fallback: 'Could not record receipt.');
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
          loading: () => const SizedBox(
            height: 120,
            child: LoadingView(label: 'Loading…'),
          ),
          error: (e, _) => SizedBox(
            height: 120,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load PO lines.'),
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
                  child: Text(
                    _error!,
                    style: TextStyle(color: cs.onErrorContainer),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              Text(
                'Confirm received quantities',
                style: Theme.of(context).textTheme.labelLarge,
              ),
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
                                  Text(
                                    _short(l.variantId, 14),
                                    style: const TextStyle(
                                      fontFamily: 'monospace',
                                      fontSize: 12,
                                    ),
                                  ),
                                  Text(
                                    'ordered ${l.qty.toStringAsFixed(0)}',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: cs.outline,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            SizedBox(
                              width: 90,
                              child: TextFormField(
                                initialValue: l.qty.toStringAsFixed(0),
                                keyboardType: TextInputType.number,
                                decoration: const InputDecoration(
                                  labelText: 'Received',
                                  isDense: true,
                                ),
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
                  final lines = ref
                      .read(purchaseOrderLinesProvider(widget.poId))
                      .value;
                  if (lines != null) _submit(lines);
                },
          child: _loading
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
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
    final cs = Theme.of(context).colorScheme;
    Color bg;
    Color fg;
    switch (status.toUpperCase()) {
      case 'DRAFT':
        bg = cs.surfaceContainerHighest;
        fg = cs.onSurfaceVariant;
        break;
      case 'SUBMITTED':
        bg = context.status.info;
        fg = context.status.onInfo;
        break;
      case 'PENDING_APPROVAL':
      // Amber for the same reason PARTIALLY_RECEIVED is: this is a state somebody has to act on,
      // not one to observe. A grey badge would read as "in progress" when it means "stopped".
      case 'PARTIALLY_RECEIVED':
        // Amber rather than the generic default: something is still owed, and that is a state a
        // buyer is meant to act on rather than merely observe.
        bg = context.status.warning.withValues(alpha: 0.18);
        fg = context.status.warning;
        break;
      case 'RECEIVED':
      case 'CLOSED':
        bg = cs.secondaryContainer;
        fg = cs.onSecondaryContainer;
        break;
      default:
        bg = context.status.warning.withValues(alpha: 0.18);
        fg = context.status.warning;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        status,
        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg),
      ),
    );
  }
}

String _short(String s, [int n = 8]) =>
    s.length > n ? '${s.substring(0, n)}…' : s;

/// A unit price at its own precision, with trailing zeroes removed.
///
/// Deliberately not [AppFormat.money]: that rounds to the currency's minor unit, which is right for
/// a total and wrong for a unit price. Buying 1,000 screws at 0.0125 each is an ordinary trade
/// price, and showing it as 0.01 misreports the line by 25% — the same defect SJ-D25 removed from
/// the column type.
String _trim(double v) {
  final s = v.toStringAsFixed(4);
  return s.contains('.')
      ? s.replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '')
      : s;
}
