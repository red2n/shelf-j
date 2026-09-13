import 'package:flutter/material.dart';

import '../../core/reference/iso_reference.dart';

/// A value the list does not carry — a supplier trading in a currency outside
/// it, a store in an unlisted zone — is kept as an option, so editing a record
/// never silently changes it.
List<DropdownMenuItem<String>> _withCurrent(
  List<DropdownMenuItem<String>> items,
  String? current,
  Iterable<String> known,
) => [
  ...items,
  if (current != null && current.isNotEmpty && !known.contains(current))
    DropdownMenuItem(value: current, child: Text(current)),
];

String? _required(String? v, String what) =>
    v == null || v.isEmpty ? 'Choose a $what' : null;

/// An ISO 4217 currency, required. Starts empty unless given a value.
class CurrencyField extends StatelessWidget {
  const CurrencyField({
    super.key,
    required this.value,
    required this.onChanged,
    this.label = 'Currency',
    this.enabled = true,
  });

  final String? value;
  final ValueChanged<String> onChanged;
  final String label;
  final bool enabled;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
    key: ValueKey('currency-$value'),
    initialValue: value == null || value!.isEmpty ? null : value,
    isExpanded: true,
    decoration: InputDecoration(labelText: label),
    items: _withCurrent(
      [
        for (final e in isoCurrencies.entries)
          DropdownMenuItem(value: e.key, child: Text('${e.key} — ${e.value}')),
      ],
      value,
      isoCurrencies.keys,
    ),
    validator: (v) => _required(v, 'currency'),
    onChanged: enabled ? (v) => onChanged(v!) : null,
  );
}

/// An ISO 3166 country, required. Starts empty unless given a value.
class CountryField extends StatelessWidget {
  const CountryField({
    super.key,
    required this.value,
    required this.onChanged,
    this.label = 'Country',
  });

  final String? value;
  final ValueChanged<String> onChanged;
  final String label;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
    key: ValueKey('country-$value'),
    initialValue: value == null || value!.isEmpty ? null : value,
    isExpanded: true,
    decoration: InputDecoration(labelText: label),
    items: _withCurrent(
      [
        for (final e in isoCountries.entries)
          DropdownMenuItem(
            value: e.key,
            child: Text('${e.value.$1} (${e.key})'),
          ),
      ],
      value,
      isoCountries.keys,
    ),
    validator: (v) => _required(v, 'country'),
    onChanged: (v) => onChanged(v!),
  );
}

/// An IANA time zone, required. Starts empty unless given a value.
class TimezoneField extends StatelessWidget {
  const TimezoneField({
    super.key,
    required this.value,
    required this.onChanged,
    this.label = 'Time zone',
  });

  final String? value;
  final ValueChanged<String> onChanged;
  final String label;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
    key: ValueKey('timezone-$value'),
    initialValue: value == null || value!.isEmpty ? null : value,
    isExpanded: true,
    decoration: InputDecoration(labelText: label),
    items: _withCurrent(
      [
        for (final z in ianaTimezones)
          DropdownMenuItem(value: z, child: Text(z)),
      ],
      value,
      ianaTimezones,
    ),
    validator: (v) => _required(v, 'time zone'),
    onChanged: (v) => onChanged(v!),
  );
}

/// The currency a country trades in, to suggest once the country is chosen.
String? currencyOfCountry(String? country) =>
    country == null ? null : isoCountries[country]?.$2;
