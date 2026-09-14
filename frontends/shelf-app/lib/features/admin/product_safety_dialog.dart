import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';

// ---------------------------------------------------------------------------
// Product safety information (01.12).
//
// Where EU product safety law binds a business (Regulation (EU) 2023/988
// art.19), an online offer must show the manufacturer, the person responsible
// for the product in the EU when the manufacturer is outside it, and any
// warnings. product-svc asks the jurisdiction rules whether it binds, refuses
// to offer a product online without it, and says what is still missing; this
// file is where a business states it.
// ---------------------------------------------------------------------------

/// What each missing item asks for, in words a shop owner uses.
const safetyItemLabels = {
  'MANUFACTURER_NAME': "the manufacturer's name",
  'MANUFACTURER_ADDRESS': "the manufacturer's postal address",
  'MANUFACTURER_CONTACT': "the manufacturer's e-mail or web address",
  'MANUFACTURER_COUNTRY': "the manufacturer's country",
  'RESPONSIBLE_PERSON_NAME': "the EU responsible person's name",
  'RESPONSIBLE_PERSON_ADDRESS': "the EU responsible person's postal address",
  'RESPONSIBLE_PERSON_CONTACT': "the EU responsible person's e-mail or web address",
  'WARNINGS': 'the warnings, or that none apply',
};

String _safetyPath(String productId) =>
    '/${ApiConstants.product}/admin/products/$productId/safety-information';

class ProductSafetyInfo {
  final bool recorded;
  final String? manufacturerName;
  final String? manufacturerAddress;
  final String? manufacturerContact;
  final String? manufacturerCountry;
  final String? responsiblePersonName;
  final String? responsiblePersonAddress;
  final String? responsiblePersonContact;
  final String? warnings;
  final bool noWarnings;
  final bool required;
  final List<String> missing;

  const ProductSafetyInfo({
    required this.recorded,
    this.manufacturerName,
    this.manufacturerAddress,
    this.manufacturerContact,
    this.manufacturerCountry,
    this.responsiblePersonName,
    this.responsiblePersonAddress,
    this.responsiblePersonContact,
    this.warnings,
    required this.noWarnings,
    required this.required,
    required this.missing,
  });

  factory ProductSafetyInfo.fromJson(Map<String, dynamic> j) => ProductSafetyInfo(
        recorded: j['recorded'] as bool? ?? false,
        manufacturerName: j['manufacturerName'] as String?,
        manufacturerAddress: j['manufacturerAddress'] as String?,
        manufacturerContact: j['manufacturerContact'] as String?,
        manufacturerCountry: j['manufacturerCountry'] as String?,
        responsiblePersonName: j['responsiblePersonName'] as String?,
        responsiblePersonAddress: j['responsiblePersonAddress'] as String?,
        responsiblePersonContact: j['responsiblePersonContact'] as String?,
        warnings: j['warnings'] as String?,
        noWarnings: j['noWarnings'] as bool? ?? false,
        required: j['required'] as bool? ?? false,
        missing: [for (final m in (j['missing'] as List?) ?? const []) m as String],
      );
}

class MissingSafetyItem {
  final String productId;
  final String name;
  final List<String> missing;

  const MissingSafetyItem(
      {required this.productId, required this.name, required this.missing});

  factory MissingSafetyItem.fromJson(Map<String, dynamic> j) => MissingSafetyItem(
        productId: j['productId'] as String? ?? '',
        name: j['name'] as String? ?? '',
        missing: [for (final m in (j['missing'] as List?) ?? const []) m as String],
      );
}

final productSafetyProvider = FutureProvider.autoDispose
    .family<ProductSafetyInfo, String>((ref, productId) async {
  final resp = await ref.read(apiClientProvider).dio.get(_safetyPath(productId));
  return ProductSafetyInfo.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final missingSafetyProvider =
    FutureProvider.autoDispose<List<MissingSafetyItem>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.product}/admin/products/safety-information/missing');
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      MissingSafetyItem.fromJson(j as Map<String, dynamic>)
  ];
});

final _email = RegExp(r'''^[^\s@<>"':/]+@[^\s@<>"':/]+\.[^\s@<>"':/]{2,}$''');
final _https = RegExp(r'''^https://[^\s<>"']+\.[^\s<>"']+$''');
final _countryCode = RegExp(r'^[A-Za-z]{2}$');

String? _contactError(String? v) {
  final t = v?.trim() ?? '';
  if (t.isEmpty) return null;
  return _email.hasMatch(t) || _https.hasMatch(t)
      ? null
      : 'An e-mail address or an https:// link';
}

String? _countryError(String? v) {
  final t = v?.trim() ?? '';
  if (t.isEmpty) return null;
  return _countryCode.hasMatch(t) ? null : 'A two-letter country code, such as DE';
}

/// The fields of a statement, shared by the new-product dialog and the
/// safety-information dialog so both send the same shape.
class SafetyInformationForm {
  final manufacturerName = TextEditingController();
  final manufacturerAddress = TextEditingController();
  final manufacturerContact = TextEditingController();
  final manufacturerCountry = TextEditingController();
  final responsiblePersonName = TextEditingController();
  final responsiblePersonAddress = TextEditingController();
  final responsiblePersonContact = TextEditingController();
  final warnings = TextEditingController();
  bool noWarnings = false;

  List<TextEditingController> get _all => [
        manufacturerName,
        manufacturerAddress,
        manufacturerContact,
        manufacturerCountry,
        responsiblePersonName,
        responsiblePersonAddress,
        responsiblePersonContact,
        warnings,
      ];

  void fill(ProductSafetyInfo s) {
    manufacturerName.text = s.manufacturerName ?? '';
    manufacturerAddress.text = s.manufacturerAddress ?? '';
    manufacturerContact.text = s.manufacturerContact ?? '';
    manufacturerCountry.text = s.manufacturerCountry ?? '';
    responsiblePersonName.text = s.responsiblePersonName ?? '';
    responsiblePersonAddress.text = s.responsiblePersonAddress ?? '';
    responsiblePersonContact.text = s.responsiblePersonContact ?? '';
    warnings.text = s.warnings ?? '';
    noWarnings = s.noWarnings;
  }

  bool get isEmpty => !noWarnings && _all.every((c) => c.text.trim().isEmpty);

  static String? _value(TextEditingController c) {
    final t = c.text.trim();
    return t.isEmpty ? null : t;
  }

  Map<String, dynamic> toJson() => {
        'manufacturerName': _value(manufacturerName),
        'manufacturerAddress': _value(manufacturerAddress),
        'manufacturerContact': _value(manufacturerContact),
        'manufacturerCountry': _value(manufacturerCountry)?.toUpperCase(),
        'responsiblePersonName': _value(responsiblePersonName),
        'responsiblePersonAddress': _value(responsiblePersonAddress),
        'responsiblePersonContact': _value(responsiblePersonContact),
        'warnings': noWarnings ? null : _value(warnings),
        'noWarnings': noWarnings,
      };

  void dispose() {
    for (final c in _all) {
      c.dispose();
    }
  }
}

class SafetyInformationFields extends StatefulWidget {
  final SafetyInformationForm form;

  const SafetyInformationFields({super.key, required this.form});

  @override
  State<SafetyInformationFields> createState() => _SafetyInformationFieldsState();
}

class _SafetyInformationFieldsState extends State<SafetyInformationFields> {
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final f = widget.form;
    final hint = theme.textTheme.bodySmall
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Manufacturer', style: theme.textTheme.titleSmall),
        TextFormField(
          key: const Key('safety-manufacturer-name'),
          controller: f.manufacturerName,
          maxLength: 200,
          decoration: const InputDecoration(labelText: 'Name'),
        ),
        TextFormField(
          key: const Key('safety-manufacturer-address'),
          controller: f.manufacturerAddress,
          maxLength: 500,
          maxLines: 2,
          decoration: const InputDecoration(labelText: 'Postal address'),
        ),
        TextFormField(
          key: const Key('safety-manufacturer-contact'),
          controller: f.manufacturerContact,
          maxLength: 254,
          validator: _contactError,
          decoration: const InputDecoration(labelText: 'E-mail or web address'),
        ),
        TextFormField(
          key: const Key('safety-manufacturer-country'),
          controller: f.manufacturerCountry,
          maxLength: 2,
          textCapitalization: TextCapitalization.characters,
          validator: _countryError,
          decoration: const InputDecoration(labelText: 'Country (two-letter code)'),
        ),
        const SizedBox(height: AppSpacing.sm),
        Text('Responsible person in the EU', style: theme.textTheme.titleSmall),
        Text('Needed when the manufacturer is outside the EU.', style: hint),
        TextFormField(
          key: const Key('safety-rp-name'),
          controller: f.responsiblePersonName,
          maxLength: 200,
          decoration: const InputDecoration(labelText: 'Name'),
        ),
        TextFormField(
          key: const Key('safety-rp-address'),
          controller: f.responsiblePersonAddress,
          maxLength: 500,
          maxLines: 2,
          decoration: const InputDecoration(labelText: 'Postal address'),
        ),
        TextFormField(
          key: const Key('safety-rp-contact'),
          controller: f.responsiblePersonContact,
          maxLength: 254,
          validator: _contactError,
          decoration: const InputDecoration(labelText: 'E-mail or web address'),
        ),
        const SizedBox(height: AppSpacing.sm),
        Text('Warnings', style: theme.textTheme.titleSmall),
        CheckboxListTile(
          key: const Key('safety-no-warnings'),
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          title: const Text('No warnings apply'),
          value: f.noWarnings,
          onChanged: (v) => setState(() => f.noWarnings = v ?? false),
        ),
        TextFormField(
          key: const Key('safety-warnings'),
          controller: f.warnings,
          enabled: !f.noWarnings,
          maxLength: 4000,
          maxLines: 3,
          decoration: const InputDecoration(
              labelText: 'Warnings or safety information, as a shopper should read them'),
        ),
      ],
    );
  }
}

Future<bool?> showProductSafetyDialog(BuildContext context,
        {required String productId, required String productName}) =>
    showDialog<bool>(
      context: context,
      builder: (_) =>
          ProductSafetyDialog(productId: productId, productName: productName),
    );

class ProductSafetyDialog extends ConsumerStatefulWidget {
  final String productId;
  final String productName;

  const ProductSafetyDialog(
      {super.key, required this.productId, required this.productName});

  @override
  ConsumerState<ProductSafetyDialog> createState() => _ProductSafetyDialogState();
}

class _ProductSafetyDialogState extends ConsumerState<ProductSafetyDialog> {
  final _form = SafetyInformationForm();
  final _key = GlobalKey<FormState>();
  bool _filled = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _form.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final sheet = ref.watch(productSafetyProvider(widget.productId));
    return AlertDialog(
      title: Text('Safety information — ${widget.productName}'),
      content: SizedBox(
        width: 520,
        child: sheet.when(
          loading: () => const Padding(
            padding: EdgeInsets.all(AppSpacing.xl),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(friendlyError(e, fallback: 'Could not load the safety information.'),
                  key: const Key('safety-load-error')),
              TextButton(
                onPressed: () => ref.invalidate(productSafetyProvider(widget.productId)),
                child: const Text('Try again'),
              ),
            ],
          ),
          data: (s) {
            if (!_filled) {
              _form.fill(s);
              _filled = true;
            }
            return SingleChildScrollView(
              child: Form(
                key: _key,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      s.required
                          ? 'This business’s market requires this to offer the product '
                              'online (Regulation (EU) 2023/988 art.19), and the offer shows it.'
                          : 'Not required in this business’s market. Anything given here is '
                              'shown with the online offer.',
                      key: const Key('safety-required'),
                    ),
                    if (s.missing.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: AppSpacing.sm),
                        child: Text(
                          'Still missing: ${s.missing.map((m) => safetyItemLabels[m] ?? m).join('; ')}.',
                          key: const Key('safety-missing'),
                          style: TextStyle(color: theme.colorScheme.error),
                        ),
                      ),
                    const SizedBox(height: AppSpacing.md),
                    SafetyInformationFields(form: _form),
                    if (_error != null)
                      Text(_error!,
                          key: const Key('safety-error'),
                          style: TextStyle(color: theme.colorScheme.error)),
                  ],
                ),
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('safety-save'),
          onPressed: _busy || !sheet.hasValue ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }

  Future<void> _save() async {
    if (!(_key.currentState?.validate() ?? false)) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(_safetyPath(widget.productId), data: _form.toJson());
      ref.invalidate(productSafetyProvider(widget.productId));
      ref.invalidate(missingSafetyProvider);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not save the safety information.');
      });
    }
  }
}

/// Online products a business's market requires safety information for and
/// that lack some — products listed before the rule — each with a way to add
/// it. Shows nothing when there are none or the list cannot be read.
class MissingSafetyBanner extends ConsumerWidget {
  const MissingSafetyBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final items = ref.watch(missingSafetyProvider).value ?? const [];
    if (items.isEmpty) return const SizedBox.shrink();
    return Card(
      key: const Key('missing-safety-banner'),
      color: cs.errorContainer,
      child: ExpansionTile(
        iconColor: cs.onErrorContainer,
        collapsedIconColor: cs.onErrorContainer,
        title: Text(
          '${items.length} online ${items.length == 1 ? 'product lacks' : 'products lack'} '
          'the safety information EU product safety law requires',
          style: TextStyle(color: cs.onErrorContainer),
        ),
        subtitle: Text(
          'Each is refused any change while online until it is complete.',
          style: TextStyle(color: cs.onErrorContainer),
        ),
        children: [
          for (final item in items)
            ListTile(
              key: Key('missing-safety-${item.productId}'),
              title: Text(item.name, style: TextStyle(color: cs.onErrorContainer)),
              subtitle: Text(
                'Missing ${item.missing.map((m) => safetyItemLabels[m] ?? m).join('; ')}',
                style: TextStyle(color: cs.onErrorContainer),
              ),
              trailing: TextButton(
                onPressed: () => showProductSafetyDialog(context,
                    productId: item.productId, productName: item.name),
                child: const Text('Add'),
              ),
            ),
        ],
      ),
    );
  }
}
