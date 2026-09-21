import 'package:intl/intl.dart';

import 'l10n/app_locales.dart';

/// Locale-aware formatting for money and dates.
///
/// There is no default currency (SJ-D53). A constant pound-sterling code here
/// once turned any amount whose currency was unknown into pounds, on a platform
/// whose tenants trade in yen, rupees and dinars. An amount with no currency is
/// formatted as a plain number with two decimals, which is visibly incomplete
/// rather than quietly wrong. The locale is the one the app is running in
/// unless a caller passes another.
class AppFormat {
  AppFormat._();

  /// The locale the app runs in, or the app's own UI fallback when none is set
  /// (a plain unit test, a background isolate).
  static String get _locale =>
      Intl.defaultLocale ?? AppLocales.fallback.toLanguageTag().replaceAll('-', '_');

  /// Money in the given ISO-4217 [currencyCode], formatted for [locale], with
  /// the right symbol, grouping and minor units, e.g. `£1,234.50` or `¥3,702`.
  /// Without a currency, the amount alone: `1,234.50`.
  static String money(num amount, {String? currencyCode, String? locale}) {
    final code = currencyCode?.trim() ?? '';
    if (code.isEmpty) {
      return NumberFormat.decimalPatternDigits(
        locale: locale ?? _locale,
        decimalDigits: 2,
      ).format(amount);
    }
    return NumberFormat.simpleCurrency(
      locale: locale ?? _locale,
      name: code,
    ).format(amount);
  }

  /// The symbol a currency is written with in [locale] (`£`, `¥`, `₹`), or the
  /// code itself when the locale has none; empty for no currency.
  static String currencySymbol(String? currencyCode, {String? locale}) {
    final code = currencyCode?.trim() ?? '';
    if (code.isEmpty) return '';
    return NumberFormat.simpleCurrency(
      locale: locale ?? _locale,
      name: code,
    ).currencySymbol;
  }

  /// A locale-formatted date, e.g. `23 Jun 2026`. Accepts an ISO-8601 string;
  /// returns the input unchanged if it can't be parsed, or '' when null/blank.
  static String date(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return DateFormat.yMMMd(locale ?? _locale).format(dt);
  }

  /// A locale-formatted date + time, e.g. `23 Jun 2026, 14:05`.
  static String dateTime(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return DateFormat.yMMMd(locale ?? _locale).add_Hm().format(dt);
  }

  static DateTime? _parse(String? iso) {
    if (iso == null || iso.isEmpty) return null;
    return DateTime.tryParse(iso)?.toLocal();
  }
}
