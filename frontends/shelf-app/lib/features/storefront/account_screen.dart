import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_error.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;

// The shopper's own account at this shop (12.10): the profile the shop holds
// for them and the addresses they keep here. Everything is keyed on the login
// the token carries, so the screen can reach no record but the shopper's own —
// and the shop's record is per shop: the same person is a different customer
// at a different tenant.

/// The record this shop holds for the signed-in shopper.
class MyCustomer {
  final String id;
  final String email;
  final String? phone;
  final String firstName;
  final String lastName;

  const MyCustomer({
    required this.id,
    required this.email,
    this.phone,
    required this.firstName,
    required this.lastName,
  });

  factory MyCustomer.fromJson(Map<String, dynamic> j) => MyCustomer(
        id: j['id'] as String? ?? '',
        email: j['email'] as String? ?? '',
        phone: j['phone'] as String?,
        firstName: j['firstName'] as String? ?? '',
        lastName: j['lastName'] as String? ?? '',
      );

  String get fullName => '$firstName $lastName'.trim();
}

/// One address in the shopper's book.
class SavedAddress {
  final String id;
  final String type;
  final String line1;
  final String? line2;
  final String? city;
  final String? state;
  final String country;
  final String? pincode;
  final bool isDefault;

  const SavedAddress({
    required this.id,
    required this.type,
    required this.line1,
    this.line2,
    this.city,
    this.state,
    required this.country,
    this.pincode,
    this.isDefault = false,
  });

  factory SavedAddress.fromJson(Map<String, dynamic> j) => SavedAddress(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? 'HOME',
        line1: j['line1'] as String? ?? '',
        line2: j['line2'] as String?,
        city: j['city'] as String?,
        state: j['state'] as String?,
        country: j['country'] as String? ?? '',
        pincode: j['pincode'] as String?,
        isDefault: j['isDefault'] as bool? ?? false,
      );

  /// The body the API takes, for a replace that changes one thing.
  Map<String, dynamic> toBody({bool? isDefault}) => {
        'type': type,
        'line1': line1,
        'line2': line2,
        'city': city,
        'state': state,
        'country': country,
        'pincode': pincode,
        'isDefault': isDefault ?? this.isDefault,
      };

  String get oneLine => [
        line1,
        if (line2 != null && line2!.isNotEmpty) line2,
        if (city != null && city!.isNotEmpty) city,
        if (pincode != null && pincode!.isNotEmpty) pincode,
        country,
      ].join(', ');
}

/// The shop's record for the signed-in shopper; null when signed out or when
/// this shop holds none yet (a 404, which is not an error — it is a shopper who
/// has never bought here).
final myCustomerProvider = FutureProvider.autoDispose<MyCustomer?>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get('/${ApiConstants.customer}/customers/me');
    return MyCustomer.fromJson(resp.data['data'] as Map<String, dynamic>);
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return null;
    rethrow;
  }
});

/// The shopper's address book at this shop; empty when signed out or when the
/// shop holds no record yet.
final myAddressesProvider = FutureProvider.autoDispose<List<SavedAddress>>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return const [];
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get('/${ApiConstants.customer}/customers/me/addresses');
    final rows = (resp.data['data'] as List?) ?? const [];
    return rows.map((e) => SavedAddress.fromJson(e as Map<String, dynamic>)).toList();
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return const [];
    rethrow;
  }
});

/// The address types a shopper can file an address under.
const addressTypes = <String, String>{'HOME': 'Home', 'WORK': 'Work', 'OTHER': 'Other'};

class StorefrontAccountScreen extends ConsumerStatefulWidget {
  const StorefrontAccountScreen({super.key});

  @override
  ConsumerState<StorefrontAccountScreen> createState() => _StorefrontAccountScreenState();
}

class _StorefrontAccountScreenState extends ConsumerState<StorefrontAccountScreen> {
  bool _busy = false;

  void _refresh() {
    ref.invalidate(myCustomerProvider);
    ref.invalidate(myAddressesProvider);
  }

  void _say(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _run(Future<void> Function(Dio dio) work, {required String fallback}) async {
    setState(() => _busy = true);
    try {
      await work(ref.read(storefrontDioProvider));
      _refresh();
    } catch (e) {
      _say(friendlyError(e, fallback: fallback));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _claim() => _run(
        (dio) async {
          await dio.post('/${ApiConstants.customer}/customers/me');
          _say('Your account at this shop is set up.');
        },
        fallback: 'Could not set up your account just now.',
      );

  Future<void> _saveProfile(Map<String, dynamic> body) => _run(
        (dio) async {
          await dio.put('/${ApiConstants.customer}/customers/me', data: body);
          _say('Saved.');
        },
        fallback: 'Could not save your details.',
      );

  Future<void> _addAddress() async {
    final body = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => const AddressDialog(),
    );
    if (body == null) return;
    await _run(
      (dio) async {
        await dio.post('/${ApiConstants.customer}/customers/me/addresses', data: body);
        _say('Address added.');
      },
      fallback: 'Could not add that address.',
    );
  }

  Future<void> _editAddress(SavedAddress a) async {
    final body = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => AddressDialog(existing: a),
    );
    if (body == null) return;
    await _run(
      (dio) async {
        await dio.put('/${ApiConstants.customer}/customers/me/addresses/${a.id}', data: body);
        _say('Address updated.');
      },
      fallback: 'Could not update that address.',
    );
  }

  Future<void> _makeDefault(SavedAddress a) => _run(
        (dio) async {
          await dio.put('/${ApiConstants.customer}/customers/me/addresses/${a.id}',
              data: a.toBody(isDefault: true));
          _say('Default address changed.');
        },
        fallback: 'Could not change your default address.',
      );

  Future<void> _removeAddress(SavedAddress a) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove this address?'),
        content: Text(a.oneLine),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
        ],
      ),
    );
    if (sure != true) return;
    await _run(
      (dio) async {
        await dio.delete('/${ApiConstants.customer}/customers/me/addresses/${a.id}');
        _say('Address removed.');
      },
      fallback: 'Could not remove that address.',
    );
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(storefrontAuthProvider);
    if (!auth.isSignedIn) return const _SignInFirst();
    final customer = ref.watch(myCustomerProvider);
    final addresses = ref.watch(myAddressesProvider);
    final theme = Theme.of(context);

    return RefreshIndicator(
      onRefresh: () async => _refresh(),
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('My account', style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            'What this shop holds about you, and the addresses you keep here. '
            'Each shop keeps its own record of you.',
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          customer.when(
            loading: () => const Padding(
              padding: EdgeInsets.symmetric(vertical: 24),
              child: Center(child: CircularProgressIndicator()),
            ),
            error: (e, _) => _InlineError(
              message: friendlyError(e, fallback: 'Could not load your account.'),
              onRetry: _refresh,
            ),
            data: (c) => c == null
                ? _NoRecordCard(busy: _busy, onClaim: _claim)
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      ProfileCard(
                        key: ValueKey('profile-${c.id}'),
                        customer: c,
                        busy: _busy,
                        onSave: _saveProfile,
                      ),
                      const SizedBox(height: 24),
                      Row(
                        children: [
                          Expanded(
                              child: Text('My addresses', style: theme.textTheme.titleLarge)),
                          FilledButton.tonalIcon(
                            key: const Key('account-add-address'),
                            onPressed: _busy ? null : _addAddress,
                            icon: const Icon(Icons.add_location_alt_outlined),
                            label: const Text('Add address'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      addresses.when(
                        loading: () => const Padding(
                          padding: EdgeInsets.symmetric(vertical: 24),
                          child: Center(child: CircularProgressIndicator()),
                        ),
                        error: (e, _) => _InlineError(
                          message: friendlyError(e, fallback: 'Could not load your addresses.'),
                          onRetry: _refresh,
                        ),
                        data: (rows) => rows.isEmpty
                            ? Padding(
                                padding: const EdgeInsets.symmetric(vertical: 16),
                                child: Text(
                                  'No addresses yet. Save one here and checkout will offer it.',
                                  style: theme.textTheme.bodyMedium?.copyWith(
                                      color: theme.colorScheme.onSurfaceVariant),
                                ),
                              )
                            : Card(
                                child: Column(
                                  children: [
                                    for (final a in rows)
                                      ListTile(
                                        leading: Icon(a.type == 'WORK'
                                            ? Icons.work_outline
                                            : Icons.home_outlined),
                                        title: Text(a.oneLine),
                                        subtitle: Text(addressTypes[a.type] ?? a.type),
                                        trailing: Row(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            if (a.isDefault)
                                              const Chip(
                                                  label: Text('Default'),
                                                  visualDensity: VisualDensity.compact),
                                            PopupMenuButton<String>(
                                              key: Key('address-menu-${a.id}'),
                                              tooltip: 'Address actions',
                                              onSelected: (v) => switch (v) {
                                                'edit' => _editAddress(a),
                                                'default' => _makeDefault(a),
                                                _ => _removeAddress(a),
                                              },
                                              itemBuilder: (_) => [
                                                const PopupMenuItem(
                                                    value: 'edit', child: Text('Edit')),
                                                if (!a.isDefault)
                                                  const PopupMenuItem(
                                                      value: 'default',
                                                      child: Text('Make default')),
                                                const PopupMenuItem(
                                                    value: 'remove', child: Text('Remove')),
                                              ],
                                            ),
                                          ],
                                        ),
                                      ),
                                  ],
                                ),
                              ),
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}

/// The profile the shop holds: the name and phone are the shopper's to change;
/// the email is the login and is shown, not edited.
class ProfileCard extends StatefulWidget {
  const ProfileCard({super.key, required this.customer, required this.busy, required this.onSave});
  final MyCustomer customer;
  final bool busy;
  final Future<void> Function(Map<String, dynamic> body) onSave;

  @override
  State<ProfileCard> createState() => _ProfileCardState();
}

class _ProfileCardState extends State<ProfileCard> {
  final _form = GlobalKey<FormState>();
  late final _first = TextEditingController(text: widget.customer.firstName);
  late final _last = TextEditingController(text: widget.customer.lastName);
  late final _phone = TextEditingController(text: widget.customer.phone ?? '');

  @override
  void dispose() {
    _first.dispose();
    _last.dispose();
    _phone.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('My details', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              TextFormField(
                key: const Key('profile-first'),
                controller: _first,
                maxLength: 100,
                decoration: const InputDecoration(labelText: 'First name', counterText: ''),
                validator: (v) => (v ?? '').trim().isEmpty ? 'Required' : null,
              ),
              TextFormField(
                key: const Key('profile-last'),
                controller: _last,
                maxLength: 100,
                decoration: const InputDecoration(labelText: 'Last name', counterText: ''),
                validator: (v) => (v ?? '').trim().isEmpty ? 'Required' : null,
              ),
              TextFormField(
                key: const Key('profile-phone'),
                controller: _phone,
                maxLength: 32,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(labelText: 'Phone', counterText: ''),
              ),
              const SizedBox(height: 8),
              TextField(
                enabled: false,
                controller: TextEditingController(text: widget.customer.email),
                decoration: const InputDecoration(
                  labelText: 'Email',
                  helperText: 'Your email is your sign-in and cannot be changed here.',
                ),
              ),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  key: const Key('profile-save'),
                  onPressed: widget.busy
                      ? null
                      : () {
                          if (!(_form.currentState?.validate() ?? false)) return;
                          final phone = _phone.text.trim();
                          widget.onSave({
                            'firstName': _first.text.trim(),
                            'lastName': _last.text.trim(),
                            'phone': phone.isEmpty ? null : phone,
                          });
                        },
                  child: const Text('Save'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Add or edit one address. Returns the body to send, or null when dismissed.
/// The lines are bounded here to what the server accepts, so nothing is sent
/// to be refused.
class AddressDialog extends StatefulWidget {
  const AddressDialog({super.key, this.existing});
  final SavedAddress? existing;

  @override
  State<AddressDialog> createState() => _AddressDialogState();
}

class _AddressDialogState extends State<AddressDialog> {
  final _form = GlobalKey<FormState>();
  late String _type = widget.existing?.type ?? 'HOME';
  late final _line1 = TextEditingController(text: widget.existing?.line1 ?? '');
  late final _line2 = TextEditingController(text: widget.existing?.line2 ?? '');
  late final _city = TextEditingController(text: widget.existing?.city ?? '');
  late final _state = TextEditingController(text: widget.existing?.state ?? '');
  late final _country = TextEditingController(text: widget.existing?.country ?? '');
  late final _pincode = TextEditingController(text: widget.existing?.pincode ?? '');
  late bool _default = widget.existing?.isDefault ?? false;

  @override
  void dispose() {
    for (final c in [_line1, _line2, _city, _state, _country, _pincode]) {
      c.dispose();
    }
    super.dispose();
  }

  String? _blank(String? v) => (v ?? '').trim().isEmpty ? null : v!.trim();

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.existing == null ? 'Add address' : 'Edit address'),
      content: SizedBox(
        width: 440,
        child: Form(
          key: _form,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                DropdownButtonFormField<String>(
                  key: const Key('address-type'),
                  initialValue: addressTypes.containsKey(_type) ? _type : 'OTHER',
                  decoration: const InputDecoration(labelText: 'Type'),
                  items: [
                    for (final e in addressTypes.entries)
                      DropdownMenuItem(value: e.key, child: Text(e.value)),
                  ],
                  onChanged: (v) => setState(() => _type = v ?? 'HOME'),
                ),
                TextFormField(
                  key: const Key('address-line1'),
                  controller: _line1,
                  maxLength: 120,
                  decoration: const InputDecoration(labelText: 'Address line 1', counterText: ''),
                  validator: (v) => (v ?? '').trim().isEmpty ? 'Required' : null,
                ),
                TextFormField(
                  key: const Key('address-line2'),
                  controller: _line2,
                  maxLength: 120,
                  decoration:
                      const InputDecoration(labelText: 'Address line 2 (optional)', counterText: ''),
                ),
                Row(children: [
                  Expanded(
                    child: TextFormField(
                      key: const Key('address-city'),
                      controller: _city,
                      maxLength: 120,
                      decoration: const InputDecoration(labelText: 'City', counterText: ''),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      key: const Key('address-pincode'),
                      controller: _pincode,
                      maxLength: 20,
                      decoration: const InputDecoration(labelText: 'Postcode', counterText: ''),
                    ),
                  ),
                ]),
                Row(children: [
                  Expanded(
                    child: TextFormField(
                      key: const Key('address-state'),
                      controller: _state,
                      maxLength: 120,
                      decoration:
                          const InputDecoration(labelText: 'County / state', counterText: ''),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      key: const Key('address-country'),
                      controller: _country,
                      maxLength: 120,
                      decoration: const InputDecoration(labelText: 'Country', counterText: ''),
                      validator: (v) => (v ?? '').trim().isEmpty ? 'Required' : null,
                    ),
                  ),
                ]),
                SwitchListTile(
                  key: const Key('address-default'),
                  contentPadding: EdgeInsets.zero,
                  value: _default,
                  onChanged: (v) => setState(() => _default = v),
                  title: const Text('Use as my default address'),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('address-save'),
          onPressed: () {
            if (!(_form.currentState?.validate() ?? false)) return;
            Navigator.pop(context, {
              'type': _type,
              'line1': _line1.text.trim(),
              'line2': _blank(_line2.text),
              'city': _blank(_city.text),
              'state': _blank(_state.text),
              'country': _country.text.trim(),
              'pincode': _blank(_pincode.text),
              'isDefault': _default,
            });
          },
          child: Text(widget.existing == null ? 'Add' : 'Save'),
        ),
      ],
    );
  }
}

class _NoRecordCard extends StatelessWidget {
  const _NoRecordCard({required this.busy, required this.onClaim});
  final bool busy;
  final VoidCallback onClaim;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'This shop holds no record for you yet. One is created the first time '
              'you buy here — or set it up now to keep your details and addresses ready.',
            ),
            const SizedBox(height: 12),
            FilledButton(
              key: const Key('account-claim'),
              onPressed: busy ? null : onClaim,
              child: const Text('Set up my account'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SignInFirst extends StatelessWidget {
  const _SignInFirst();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.account_circle_outlined, size: 48),
            const SizedBox(height: 12),
            const Text(
              'Sign in to see your details and the addresses you keep at this shop.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => showDialog<void>(
                  context: context, builder: (_) => const StorefrontAuthDialog()),
              child: const Text('Sign in'),
            ),
          ],
        ),
      ),
    );
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.error_outline),
        title: Text(message),
        trailing: TextButton(onPressed: onRetry, child: const Text('Retry')),
      ),
    );
  }
}
