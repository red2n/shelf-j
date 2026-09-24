import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_shell.dart';

// ---------------------------------------------------------------------------
// Prices shown in another currency (03.x): the picker appears only when the
// shop keeps a rate, choosing a currency is remembered for every price, and a
// resolved price carries the figure the shopper sees beside the one they pay.
// ---------------------------------------------------------------------------

ShopCurrencies _shop({bool withDollar = true}) => ShopCurrencies(
      home: 'GBP',
      currencies: withDollar ? const ['GBP', 'USD'] : const ['GBP'],
      rates: withDollar ? const {'USD': 0.8} : const {},
    );

Future<ProviderContainer> _pump(WidgetTester tester, ShopCurrencies shop) async {
  final container = ProviderContainer(overrides: [
    storefrontCurrenciesProvider.overrideWith((ref) async => shop),
  ]);
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(home: Scaffold(appBar: AppBar(actions: const [CurrencyPicker()]))),
  ));
  await tester.pumpAndSettle();
  return container;
}

void main() {
  testWidgets('no picker when the shop keeps no rate', (tester) async {
    await _pump(tester, _shop(withDollar: false));
    expect(find.byKey(const Key('currency-picker')), findsNothing);
  });

  testWidgets('with a rate the picker shows the home currency and remembers the choice', (tester) async {
    final container = await _pump(tester, _shop());
    expect(find.byKey(const Key('currency-picker')), findsOneWidget);
    expect(find.text('GBP'), findsOneWidget);
    await tester.tap(find.byKey(const Key('currency-picker')));
    await tester.pumpAndSettle();
    expect(find.text('GBP (you pay in GBP)'), findsOneWidget);
    await tester.tap(find.textContaining("USD, shown at the shop's rate"));
    await tester.pumpAndSettle();
    expect(container.read(displayCurrencyProvider), 'USD');
    expect(find.text('USD'), findsOneWidget);
    // Back to the shop's own currency clears the choice rather than storing the home code.
    await tester.tap(find.byKey(const Key('currency-picker')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('GBP (you pay in GBP)'));
    await tester.pumpAndSettle();
    expect(container.read(displayCurrencyProvider), isNull);
  });

  test('a resolved price carries what the shopper sees beside what they pay', () {
    final p = ResolvedPrice.fromJson({
      'unitPrice': 100.0,
      'totalWithVat': 120.0,
      'currency': 'GBP',
      'display': {'currency': 'USD', 'rate': 0.8, 'unitPrice': 125.0, 'totalWithVat': 150.0},
    });
    expect(p.display!.rate, 0.8);
    expect(p.shownLine, '≈ USD 150.00');
    final plain = ResolvedPrice.fromJson({'unitPrice': 1, 'totalWithVat': 1.2, 'currency': 'GBP'});
    expect(plain.display, isNull);
    expect(plain.shownLine, '');
    final same = ResolvedPrice.fromJson({
      'unitPrice': 1,
      'totalWithVat': 1.2,
      'currency': 'GBP',
      'display': {'currency': 'GBP', 'rate': 1, 'unitPrice': 1, 'totalWithVat': 1.2},
    });
    expect(same.shownLine, '');
  });

  test('the shop converts a home figure it already holds, and refuses one it has no rate for', () {
    final shop = ShopCurrencies.fromJson({
      'home': 'GBP',
      'currencies': ['GBP', 'USD'],
      'rates': [
        {'currency': 'USD', 'rate': 0.8}
      ],
    });
    expect(shop.shown(80, 'USD'), 100);
    expect(shop.shown(80, 'GBP'), 80);
    expect(shop.shown(80, 'EUR'), isNull);
  });
}
