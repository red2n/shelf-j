import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shelf_app/core/format.dart';

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

    test('defaults to GBP when no currency is given', () {
      expect(AppFormat.money(1000), contains('£'));
      expect(AppFormat.money(1000), contains('1,000.00'));
    });

    test('blank currency falls back to the default', () {
      expect(AppFormat.money(5, currencyCode: ''), contains('£'));
    });

    test('honours a different ISO currency', () {
      expect(AppFormat.money(2.5, currencyCode: 'USD'), contains('2.50'));
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
