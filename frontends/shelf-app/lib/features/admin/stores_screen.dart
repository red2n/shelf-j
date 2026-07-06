import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

class StoresScreen extends ConsumerWidget {
  const StoresScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storesProvider);
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
          child: Row(
            children: [
              Text('Stores', style: Theme.of(context).textTheme.headlineMedium),
              const Spacer(),
              FilledButton.icon(
                onPressed: () => _showAddStoreDialog(context, ref),
                icon: const Icon(Icons.add_business),
                label: const Text('Add Store'),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                onPressed: () => ref.invalidate(storesProvider),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: storesAsync.when(
            loading: () => const LoadingView(label: 'Loading stores…'),
            error: (e, _) => ErrorView(
              message: 'Could not load stores.\n$e',
              onRetry: () => ref.invalidate(storesProvider),
            ),
            data: (stores) {
              if (stores.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.store_outlined, size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text('No stores yet',
                          style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      Text('Add a store or warehouse to start managing inventory.',
                          style: Theme.of(context)
                              .textTheme
                              .bodyMedium
                              ?.copyWith(color: cs.outline)),
                      const SizedBox(height: 24),
                      OutlinedButton.icon(
                        onPressed: () => _showAddStoreDialog(context, ref),
                        icon: const Icon(Icons.add_business),
                        label: const Text('Add Store'),
                      ),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                itemCount: stores.length,
                separatorBuilder: (_, __) => const SizedBox(height: 4),
                itemBuilder: (context, i) {
                  final s = stores[i];
                  final isWarehouse = s.type.toUpperCase() == 'WAREHOUSE';
                  final active = s.status.toUpperCase() == 'ACTIVE';
                  final location = [s.city, s.country]
                      .where((e) => e != null && e.isNotEmpty)
                      .join(', ');
                  return Card(
                    child: ListTile(
                      onTap: () => _showEditStoreDialog(context, ref, s),
                      leading: CircleAvatar(
                        backgroundColor: cs.primaryContainer,
                        child: Icon(
                          isWarehouse ? Icons.warehouse_outlined : Icons.store_outlined,
                          color: cs.onPrimaryContainer,
                        ),
                      ),
                      title: Text(s.name,
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text([
                        s.code,
                        if (location.isNotEmpty) location,
                        s.showPrices ? 'Prices shown' : 'Catalog mode',
                      ].join(' · ')),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          TextButton.icon(
                            onPressed: () => _showZonesDialog(context, ref, s),
                            icon: const Icon(Icons.grid_view_outlined, size: 18),
                            label: const Text('Zones'),
                          ),
                          const SizedBox(width: 4),
                          Tooltip(
                            message:
                                active ? 'Tap to deactivate' : 'Tap to activate',
                            child: InkWell(
                              borderRadius: BorderRadius.circular(12),
                              onTap: () =>
                                  _toggleStoreStatus(context, ref, s, active),
                              child: Container(
                                padding: const EdgeInsets.symmetric(
                                    horizontal: 8, vertical: 3),
                                decoration: BoxDecoration(
                                  color: active
                                      ? Colors.green.shade100
                                      : cs.errorContainer,
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Text(
                                  s.status,
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.bold,
                                    color: active
                                        ? Colors.green.shade800
                                        : cs.onErrorContainer,
                                  ),
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Icon(Icons.edit_outlined, size: 18, color: cs.outline),
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

  void _showAddStoreDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (_) => _AddStoreDialog(
        onCreated: () => ref.invalidate(storesProvider),
      ),
    );
  }

  void _showEditStoreDialog(BuildContext context, WidgetRef ref, StoreInfo store) {
    showDialog(
      context: context,
      builder: (_) => _EditStoreDialog(
        store: store,
        onSaved: () => ref.invalidate(storesProvider),
      ),
    );
  }

  void _showZonesDialog(BuildContext context, WidgetRef ref, StoreInfo store) {
    showDialog(
      context: context,
      builder: (_) => _ZonesDialog(store: store),
    );
  }

  /// Activate / deactivate a store. Deactivating is consequential (it hides the
  /// store from the storefront and POS), so we confirm first.
  Future<void> _toggleStoreStatus(
      BuildContext context, WidgetRef ref, StoreInfo store, bool active) async {
    final next = active ? 'INACTIVE' : 'ACTIVE';
    if (active) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text('Deactivate ${store.name}?'),
          content: const Text(
              'The store will stop accepting online orders and POS sales until '
              'reactivated. Existing data is kept.'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('Deactivate')),
          ],
        ),
      );
      if (ok != true) return;
    }
    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/admin/stores/${store.id}/status',
        data: {'status': next},
      );
      ref.invalidate(storesProvider);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Store ${next == 'ACTIVE' ? 'activated' : 'deactivated'}.')),
      );
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not update store status: $e')),
      );
    }
  }
}

/// Lists, creates, edits and activates/deactivates the zones (aisles/racks)
/// within a store. Stock batches are pinned to a (store, zone).
class _ZonesDialog extends ConsumerWidget {
  final StoreInfo store;
  const _ZonesDialog({required this.store});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final zonesAsync = ref.watch(zonesProvider(store.id));
    return AlertDialog(
      title: Row(
        children: [
          Expanded(child: Text('Zones · ${store.name}')),
          FilledButton.icon(
            onPressed: () => _showZoneForm(context, ref, null),
            icon: const Icon(Icons.add, size: 18),
            label: const Text('Add zone'),
          ),
        ],
      ),
      content: SizedBox(
        width: 460,
        height: 420,
        child: zonesAsync.when(
          loading: () => const LoadingView(label: 'Loading zones…'),
          error: (e, _) => ErrorView(
            message: 'Could not load zones.\n$e',
            onRetry: () => ref.invalidate(zonesProvider(store.id)),
          ),
          data: (zones) {
            if (zones.isEmpty) {
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.grid_view_outlined, size: 56, color: cs.outlineVariant),
                    const SizedBox(height: 12),
                    const Text('No zones yet'),
                    const SizedBox(height: 4),
                    Text('Add aisles, racks, cold-rooms or back-store areas\n'
                        'so received stock can be pinned to a location.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: cs.outline, fontSize: 12)),
                  ],
                ),
              );
            }
            return ListView.separated(
              itemCount: zones.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final z = zones[i];
                final active = z.status.toUpperCase() == 'ACTIVE';
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.shelves, color: cs.primary),
                  title: Text(z.name,
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                  subtitle: Text('${z.code} · ${z.type}'),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(z.status,
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                            color: active ? Colors.green.shade700 : cs.error,
                          )),
                      IconButton(
                        tooltip: 'Edit',
                        icon: const Icon(Icons.edit_outlined, size: 18),
                        onPressed: () => _showZoneForm(context, ref, z),
                      ),
                      IconButton(
                        tooltip: active ? 'Deactivate' : 'Activate',
                        icon: Icon(
                          active ? Icons.toggle_on : Icons.toggle_off_outlined,
                          color: active ? Colors.green.shade700 : cs.outline,
                        ),
                        onPressed: () => _toggleStatus(context, ref, z, active),
                      ),
                    ],
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

  void _showZoneForm(BuildContext context, WidgetRef ref, ZoneInfo? zone) {
    showDialog(
      context: context,
      builder: (_) => _ZoneFormDialog(
        storeId: store.id,
        zone: zone,
        onSaved: () => ref.invalidate(zonesProvider(store.id)),
      ),
    );
  }

  Future<void> _toggleStatus(
      BuildContext context, WidgetRef ref, ZoneInfo zone, bool active) async {
    final next = active ? 'INACTIVE' : 'ACTIVE';
    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/admin/stores/${store.id}/zones/${zone.id}/status',
        data: {'status': next},
      );
      ref.invalidate(zonesProvider(store.id));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not update zone status: $e')),
      );
    }
  }
}

class _ZoneFormDialog extends ConsumerStatefulWidget {
  final String storeId;
  final ZoneInfo? zone;
  final VoidCallback onSaved;
  const _ZoneFormDialog({
    required this.storeId,
    required this.zone,
    required this.onSaved,
  });

  @override
  ConsumerState<_ZoneFormDialog> createState() => _ZoneFormDialogState();
}

class _ZoneFormDialogState extends ConsumerState<_ZoneFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _codeCtrl;
  late String _type;
  bool _loading = false;
  String? _error;

  static const _types = [
    'AISLE',
    'RACK',
    'SHELF',
    'COLD_ROOM',
    'BACK_STORE',
    'RECEIVING',
    'DISPLAY',
  ];

  bool get _isEdit => widget.zone != null;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.zone?.name ?? '');
    _codeCtrl = TextEditingController(text: widget.zone?.code ?? '');
    final t = widget.zone?.type.toUpperCase() ?? 'AISLE';
    _type = _types.contains(t) ? t : 'AISLE';
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final base = '/${ApiConstants.tenant}/admin/stores/${widget.storeId}/zones';
    final body = {
      'name': _nameCtrl.text.trim(),
      'code': _codeCtrl.text.trim().toUpperCase(),
      'type': _type,
    };
    try {
      final dio = ref.read(apiClientProvider).dio;
      if (_isEdit) {
        await dio.put('$base/${widget.zone!.id}', data: body);
      } else {
        await dio.post(base, data: body);
      }
      if (!mounted) return;
      widget.onSaved();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_isEdit ? 'Zone updated.' : 'Zone created.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        final s = e.toString();
        _error = s.contains('409')
            ? 'A zone with this code already exists in this store.'
            : s.contains('400')
                ? 'Please check the fields and try again.'
                : 'Could not save zone: $s';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(_isEdit ? 'Edit zone' : 'Add zone'),
      content: SizedBox(
        width: 380,
        child: Form(
          key: _formKey,
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
                  child: Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Zone name *',
                  hintText: 'e.g. Aisle 4 / Cold Room A',
                  prefixIcon: Icon(Icons.shelves),
                ),
                validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _codeCtrl,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(
                  labelText: 'Code *',
                  hintText: 'A4',
                  prefixIcon: Icon(Icons.tag),
                ),
                validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                value: _type,
                decoration: const InputDecoration(labelText: 'Type'),
                items: _types
                    .map((t) => DropdownMenuItem(
                        value: t, child: Text(t.replaceAll('_', ' '))))
                    .toList(),
                onChanged: (v) => setState(() => _type = v!),
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
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : Text(_isEdit ? 'Save' : 'Create'),
        ),
      ],
    );
  }
}

class _EditStoreDialog extends ConsumerStatefulWidget {
  final StoreInfo store;
  final VoidCallback onSaved;
  const _EditStoreDialog({required this.store, required this.onSaved});

  @override
  ConsumerState<_EditStoreDialog> createState() => _EditStoreDialogState();
}

class _EditStoreDialogState extends ConsumerState<_EditStoreDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _line1Ctrl;
  late final TextEditingController _cityCtrl;
  late final TextEditingController _stateCtrl;
  late final TextEditingController _countryCtrl;
  late final TextEditingController _pincodeCtrl;
  late final TextEditingController _timezoneCtrl;
  late bool _showPrices;
  late List<String> _payMethods;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final s = widget.store;
    _nameCtrl = TextEditingController(text: s.name);
    _line1Ctrl = TextEditingController(text: s.line1 ?? '');
    _cityCtrl = TextEditingController(text: s.city ?? '');
    _stateCtrl = TextEditingController(text: s.state ?? '');
    _countryCtrl = TextEditingController(text: s.country ?? '');
    _pincodeCtrl = TextEditingController(text: s.pincode ?? '');
    _timezoneCtrl = TextEditingController(text: s.timezone ?? 'UTC');
    _showPrices = s.showPrices;
    _payMethods = [...s.enabledPaymentMethods];
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _line1Ctrl.dispose();
    _cityCtrl.dispose();
    _stateCtrl.dispose();
    _countryCtrl.dispose();
    _pincodeCtrl.dispose();
    _timezoneCtrl.dispose();
    super.dispose();
  }

  String? _orNull(String v) => v.trim().isEmpty ? null : v.trim();

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final s = widget.store;
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.tenant}/admin/stores/${s.id}',
        data: {
          'name': _nameCtrl.text.trim(),
          'line1': _orNull(_line1Ctrl.text),
          // preserved (not editable in this dialog)
          'line2': s.line2,
          'city': _orNull(_cityCtrl.text),
          'state': _orNull(_stateCtrl.text),
          'country': _orNull(_countryCtrl.text),
          'pincode': _orNull(_pincodeCtrl.text),
          'geoLat': s.geoLat,
          'geoLng': s.geoLng,
          'timezone': _orNull(_timezoneCtrl.text) ?? 'UTC',
          'businessHours': s.businessHours,
          'showPrices': _showPrices,
          'enabledPaymentMethods': _payMethods,
        },
      );
      if (!mounted) return;
      widget.onSaved();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Store updated.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = e.toString().contains('400')
            ? 'Please check the fields and try again.'
            : 'Could not update store: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final s = widget.store;
    return AlertDialog(
      title: Text('Edit ${s.name}'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
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
                    child:
                        Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                  ),
                  const SizedBox(height: 12),
                ],
                // Code & type are immutable after creation.
                InputDecorator(
                  decoration: const InputDecoration(
                    labelText: 'Code · Type',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  child: Text('${s.code} · ${s.type}'),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store name *',
                    prefixIcon: Icon(Icons.store),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _line1Ctrl,
                  decoration: const InputDecoration(
                    labelText: 'Address line 1',
                    prefixIcon: Icon(Icons.location_on_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _cityCtrl,
                        decoration: const InputDecoration(labelText: 'City'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _stateCtrl,
                        decoration: const InputDecoration(labelText: 'State'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _countryCtrl,
                        decoration:
                            const InputDecoration(labelText: 'Country (code)'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _pincodeCtrl,
                        decoration:
                            const InputDecoration(labelText: 'Pincode / ZIP'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _timezoneCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Timezone',
                    prefixIcon: Icon(Icons.schedule),
                  ),
                ),
                const SizedBox(height: 8),
                const Divider(),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _showPrices,
                  onChanged: (v) => setState(() => _showPrices = v),
                  title: const Text('Show prices on storefront'),
                  subtitle: Text(
                    _showPrices
                        ? 'Customers see prices and can buy online.'
                        : 'Catalog mode: hide prices, show only "In stock / Out of stock". '
                            'Customers can still order.',
                    style: TextStyle(color: cs.outline, fontSize: 12),
                  ),
                ),
                const SizedBox(height: 8),
                PaymentMethodsPicker(
                  selected: _payMethods,
                  onChanged: (v) => setState(() => _payMethods = v),
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
              : const Text('Save changes'),
        ),
      ],
    );
  }
}

/// Owner-facing tender toggles (requirement: cash only / cash+card / cash+card+UPI+wallet …).
/// At least one method must stay selected — a store that accepts nothing can't sell.
class PaymentMethodsPicker extends StatelessWidget {
  final List<String> selected;
  final ValueChanged<List<String>> onChanged;
  const PaymentMethodsPicker(
      {super.key, required this.selected, required this.onChanged});

  static const _all = [
    ('CASH', 'Cash', Icons.payments_outlined),
    ('CARD', 'Card', Icons.credit_card),
    ('UPI', 'UPI', Icons.qr_code_2),
    ('WALLET', 'Wallet', Icons.account_balance_wallet_outlined),
  ];

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Accepted payment methods',
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Text(
          'Shown to customers at checkout and on the POS tender screen. '
          'Payments with a disabled method are rejected.',
          style: TextStyle(color: cs.outline, fontSize: 12),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final (code, label, icon) in _all)
              FilterChip(
                avatar: Icon(icon, size: 16),
                label: Text(label),
                selected: selected.contains(code),
                onSelected: (on) {
                  final next = [...selected];
                  if (on) {
                    if (!next.contains(code)) next.add(code);
                  } else {
                    next.remove(code);
                    if (next.isEmpty) {
                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                          content:
                              Text('At least one payment method must stay enabled.')));
                      return;
                    }
                  }
                  onChanged(next);
                },
              ),
          ],
        ),
      ],
    );
  }
}

class _AddStoreDialog extends ConsumerStatefulWidget {
  final VoidCallback onCreated;
  const _AddStoreDialog({required this.onCreated});

  @override
  ConsumerState<_AddStoreDialog> createState() => _AddStoreDialogState();
}

class _AddStoreDialogState extends ConsumerState<_AddStoreDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _codeCtrl = TextEditingController();
  final _line1Ctrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _pincodeCtrl = TextEditingController();
  String _type = 'STORE';
  String _country = 'IN';
  String _timezone = 'Asia/Kolkata';
  bool _showPrices = true;
  List<String> _payMethods = ['CASH', 'CARD'];
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _codeCtrl.dispose();
    _line1Ctrl.dispose();
    _cityCtrl.dispose();
    _pincodeCtrl.dispose();
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
        '/${ApiConstants.tenant}/admin/stores',
        data: {
          'name': _nameCtrl.text.trim(),
          'code': _codeCtrl.text.trim().toUpperCase(),
          'type': _type,
          if (_line1Ctrl.text.trim().isNotEmpty) 'line1': _line1Ctrl.text.trim(),
          if (_cityCtrl.text.trim().isNotEmpty) 'city': _cityCtrl.text.trim(),
          'country': _country,
          if (_pincodeCtrl.text.trim().isNotEmpty) 'pincode': _pincodeCtrl.text.trim(),
          'timezone': _timezone,
          'showPrices': _showPrices,
          'enabledPaymentMethods': _payMethods,
        },
      );
      if (!mounted) return;
      widget.onCreated();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Store created.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = _friendly(e);
      });
    }
  }

  String _friendly(Object e) {
    final s = e.toString();
    if (s.contains('409')) return 'A store with this code already exists.';
    if (s.contains('400')) return 'Please check the fields and try again.';
    return 'Could not create store: $s';
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Add Store'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
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
                    child: Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                  ),
                  const SizedBox(height: 12),
                ],
                TextFormField(
                  controller: _nameCtrl,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(
                    labelText: 'Store name *',
                    hintText: 'e.g. Downtown Branch',
                    prefixIcon: Icon(Icons.store),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _codeCtrl,
                        textCapitalization: TextCapitalization.characters,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: 'Code *',
                          hintText: 'STR-002',
                          prefixIcon: Icon(Icons.tag),
                        ),
                        validator: (v) =>
                            v == null || v.trim().isEmpty ? 'Required' : null,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        value: _type,
                        decoration: const InputDecoration(labelText: 'Type'),
                        items: const [
                          DropdownMenuItem(value: 'STORE', child: Text('Retail Store')),
                          DropdownMenuItem(
                              value: 'WAREHOUSE', child: Text('Warehouse')),
                        ],
                        onChanged: (v) => setState(() => _type = v!),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _line1Ctrl,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(
                    labelText: 'Address line 1',
                    prefixIcon: Icon(Icons.location_on_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _cityCtrl,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(labelText: 'City'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _pincodeCtrl,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(labelText: 'Pincode / ZIP'),
                      ),
                    ),
                  ],
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
                        value: _timezone,
                        decoration: const InputDecoration(labelText: 'Timezone'),
                        items: const [
                          DropdownMenuItem(value: 'Asia/Kolkata', child: Text('IST')),
                          DropdownMenuItem(
                              value: 'America/New_York', child: Text('ET')),
                          DropdownMenuItem(value: 'Europe/London', child: Text('GMT')),
                          DropdownMenuItem(value: 'Asia/Singapore', child: Text('SGT')),
                          DropdownMenuItem(value: 'Asia/Dubai', child: Text('GST')),
                        ],
                        onChanged: (v) => setState(() => _timezone = v!),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                const Divider(),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: _showPrices,
                  onChanged: (v) => setState(() => _showPrices = v),
                  title: const Text('Show prices on storefront'),
                  subtitle: Text(
                    _showPrices
                        ? 'Customers see prices and can buy online.'
                        : 'Catalog mode: hide prices, show only "In stock / Out of stock". '
                            'Customers can still order.',
                    style: TextStyle(color: cs.outline, fontSize: 12),
                  ),
                ),
                const SizedBox(height: 8),
                PaymentMethodsPicker(
                  selected: _payMethods,
                  onChanged: (v) => setState(() => _payMethods = v),
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
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('Create store'),
        ),
      ],
    );
  }
}
