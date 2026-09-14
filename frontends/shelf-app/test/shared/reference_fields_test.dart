import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/core/reference/iso_reference.dart';
import 'package:shelf_app/shared/widgets/reference_fields.dart';

// ---------------------------------------------------------------------------
// The reference fields replace per-screen literal lists that each started on a
// country or currency nobody chose (SJ-D53). They start empty unless given a
// value, refuse to submit empty, keep a value the list does not carry, and a
// country only ever suggests a currency.
// ---------------------------------------------------------------------------

Future<GlobalKey<FormState>> _pump(WidgetTester tester, Widget field) async {
  final key = GlobalKey<FormState>();
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Form(key: key, child: field),
      ),
    ),
  );
  return key;
}

void main() {
  testWidgets('an empty currency, country or zone is refused, not defaulted', (
    tester,
  ) async {
    for (final field in <Widget>[
      CurrencyField(value: null, onChanged: (_) {}),
      CountryField(value: null, onChanged: (_) {}),
      TimezoneField(value: null, onChanged: (_) {}),
    ]) {
      final key = await _pump(tester, field);
      expect(key.currentState!.validate(), isFalse);
      await tester.pump();
    }
    expect(find.text('Choose a time zone'), findsOneWidget);
  });

  testWidgets(
    'a value the list does not carry is kept, so an edit never changes it',
    (tester) async {
      final key = await _pump(
        tester,
        CurrencyField(value: 'XTS', onChanged: (_) {}),
      );
      expect(key.currentState!.validate(), isTrue);
      expect(find.text('XTS'), findsOneWidget);
      await _pump(
        tester,
        TimezoneField(value: 'Pacific/Chatham', onChanged: (_) {}),
      );
      expect(find.text('Pacific/Chatham'), findsOneWidget);
    },
  );

  testWidgets('a yen tenant opens in yen', (tester) async {
    await _pump(tester, CurrencyField(value: 'JPY', onChanged: (_) {}));
    expect(find.text('JPY — Japanese Yen'), findsOneWidget);
    await _pump(tester, CountryField(value: 'JP', onChanged: (_) {}));
    expect(find.text('Japan (JP)'), findsOneWidget);
  });

  test('a country suggests the currency it trades in, and nothing else', () {
    expect(currencyOfCountry('JP'), 'JPY');
    expect(currencyOfCountry('KW'), 'KWD');
    expect(currencyOfCountry('DE'), 'EUR');
    expect(currencyOfCountry(null), isNull);
    expect(currencyOfCountry('ZZ'), isNull);
    for (final e in isoCountries.entries) {
      expect(isoCurrencies.containsKey(e.value.$2), isTrue, reason: e.key);
    }
  });
}
