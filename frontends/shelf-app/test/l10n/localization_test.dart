import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/l10n/gen/app_localizations.dart';

Future<AppLocalizations> _localizationsFor(WidgetTester tester, Locale locale) async {
  late AppLocalizations l;
  await tester.pumpWidget(MaterialApp(
    locale: locale,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Builder(builder: (context) {
      l = AppLocalizations.of(context);
      return const SizedBox.shrink();
    }),
  ));
  return l;
}

void main() {
  testWidgets('English locale resolves the base strings', (tester) async {
    final l = await _localizationsFor(tester, const Locale('en'));
    expect(l.actionSignIn, 'Sign in');
  });

  testWidgets('Polish locale resolves translated strings', (tester) async {
    final l = await _localizationsFor(tester, const Locale('pl'));
    expect(l.actionSignIn, 'Zaloguj się');
  });

  testWidgets('an untranslated locale falls back to English', (tester) async {
    // Romanian is a stub ARB — strings fall back to the English template.
    final l = await _localizationsFor(tester, const Locale('ro'));
    expect(l.actionSignIn, 'Sign in');
  });
}
