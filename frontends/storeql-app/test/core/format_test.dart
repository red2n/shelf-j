import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';

void main() {
  // intl locale data is initialized by flutter_localizations at runtime; load it
  // explicitly here so DateFormat(locale) works in a plain unit test.
  setUpAll(initializeDateFormatting);

  group('AppFormat.money', () {
    test('formats GBP with the pound symbol, grouping and 2 decimals', () {
      final s = AppFormat.money(1234.5, currencyCode: 'GBP');
      expect(s, contains('£'));
      expect(s, contains('1,234.50'));
    });

    test('an amount with no currency is not turned into pounds (SJ-D53)', () {
      final s = AppFormat.money(1000);
      expect(s, '1,000.00');
      expect(s, isNot(contains('£')));
      expect(AppFormat.money(5, currencyCode: ''), '5.00');
      expect(AppFormat.money(5, currencyCode: '   '), '5.00');
    });

    test('each currency keeps its own symbol and minor units', () {
      expect(AppFormat.money(2.5, currencyCode: 'USD'), contains('2.50'));
      final yen = AppFormat.money(3702, currencyCode: 'JPY');
      expect(yen, contains('3,702'));
      expect(yen, isNot(contains('.00')));
      expect(AppFormat.money(1.234, currencyCode: 'KWD'), contains('1.234'));
    });

    test('the currency symbol follows the code, and is empty without one', () {
      expect(AppFormat.currencySymbol('GBP'), '£');
      expect(AppFormat.currencySymbol('JPY'), contains('¥'));
      expect(AppFormat.currencySymbol(null), '');
      expect(AppFormat.currencySymbol(''), '');
    });
  });

  group('AppFormat.date', () {
    test('formats an ISO date the UK way (d MMM y)', () {
      expect(AppFormat.date('2026-06-23'), '23 Jun 2026');
    });

    test('returns the input unchanged when unparseable', () {
      expect(AppFormat.date('not-a-date'), 'not-a-date');
    });

    test('empty/null become empty string', () {
      expect(AppFormat.date(''), '');
      expect(AppFormat.date(null), '');
    });
  });
}
