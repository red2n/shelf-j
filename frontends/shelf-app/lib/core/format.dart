import 'package:intl/intl.dart';

/// Locale-aware formatting for money and dates, replacing the hardcoded `'$'`
/// prefix and bare `toStringAsFixed(2)` / `substring` formatting scattered through
/// the screens. Defaults to the UK locale (`en_GB`) and GBP; pass a `locale`
/// (e.g. `Localizations.localeOf(context).toString()`) to follow the user's locale.
class AppFormat {
  AppFormat._();

  static const String _defaultLocale = 'en_GB';
  static const String defaultCurrency = 'GBP';

  /// Money in the given ISO-4217 [currencyCode] (defaults to GBP), formatted for
  /// [locale] — correct symbol, grouping and decimal places, e.g. `£1,234.50`.
  static String money(num amount, {String? currencyCode, String? locale}) {
    final code = (currencyCode == null || currencyCode.isEmpty)
        ? defaultCurrency
        : currencyCode;
    return NumberFormat.simpleCurrency(
      locale: locale ?? _defaultLocale,
      name: code,
    ).format(amount);
  }

  /// A locale-formatted date, e.g. `23 Jun 2026`. Accepts an ISO-8601 string;
  /// returns the input unchanged if it can't be parsed, or '' when null/blank.
  static String date(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return DateFormat.yMMMd(locale ?? _defaultLocale).format(dt);
  }

  /// A locale-formatted date + time, e.g. `23 Jun 2026, 14:05`.
  static String dateTime(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return DateFormat.yMMMd(locale ?? _defaultLocale).add_Hm().format(dt);
  }

  static DateTime? _parse(String? iso) {
    if (iso == null || iso.isEmpty) return null;
    return DateTime.tryParse(iso)?.toLocal();
  }
}
