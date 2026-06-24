import 'package:flutter/widgets.dart';

/// Locales the app ships with.
///
/// Shelf-J launches **UK-first**: English (United Kingdom) is the default and the
/// resolution fallback. Beyond that we cover the largest non-English-speaking
/// communities in the UK — ranked by "main language other than English" in the
/// 2021 England & Wales census: Polish, Romanian, Punjabi, Urdu, Bengali,
/// Gujarati, Arabic.
///
/// Urdu and Arabic are right-to-left; Flutter mirrors the whole layout
/// automatically when one of those locales resolves. All eight ship with
/// `flutter_localizations`, so built-in Material/Cupertino widgets (date pickers,
/// dialogs, etc.) and number/date formatting are localized today; app-specific
/// strings are migrated to ARB separately (see AUDIT U6 follow-up).
class AppLocales {
  AppLocales._();

  /// Default + resolution fallback (must stay first in [supported]).
  static const Locale fallback = Locale('en', 'GB');

  static const List<Locale> supported = [
    Locale('en', 'GB'), // English (UK) — default
    Locale('pl'), // Polish — largest non-English main language in the UK
    Locale('ro'), // Romanian
    Locale('pa'), // Punjabi
    Locale('ur'), // Urdu (RTL)
    Locale('bn'), // Bengali
    Locale('gu'), // Gujarati
    Locale('ar'), // Arabic (RTL)
  ];
}
