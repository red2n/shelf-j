import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/storefront/cart_screen.dart';
import 'package:shelf_app/features/storefront/storefront_providers.dart';
import 'package:shelf_app/features/storefront/storefront_widgets.dart';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Minimal harness: ProviderScope overrides + MaterialApp + Scaffold so widgets
/// can resolve Theme, MediaQuery, Overlay (needed by SnackBars), etc.
Widget _scope(Widget child, {List<Override> overrides = const []}) =>
    ProviderScope(
      overrides: overrides,
      child: MaterialApp(home: Scaffold(body: child)),
    );

CartLine _pricedLine({
  String variantId = 'v1',
  double unitPrice = 9.99,
  String currency = 'GBP',
}) =>
    CartLine(
      variantId: variantId,
      productName: 'Widget A',
      sku: 'SKU-001',
      unitPrice: unitPrice,
      currency: currency,
    );

CartLine _catalogLine({String variantId = 'v1'}) => CartLine(
      variantId: variantId,
      productName: 'Widget A',
      sku: 'SKU-001',
      unitPrice: 0,
      currency: '',
    );

/// Override storefrontConfigProvider with an immediately-resolved value.
Override _configOverride({required bool showPrices, String storeName = 'Test Store'}) =>
    storefrontConfigProvider.overrideWith(
        (ref) async => StorefrontConfig(showPrices: showPrices, storeName: storeName));

/// Override storefrontConfigProvider with a future that never completes,
/// simulating the loading state.
Override _configLoading() =>
    storefrontConfigProvider.overrideWith(
        (ref) => Completer<StorefrontConfig>().future);

/// Seeds the [cartProvider] after the widget tree is built.
/// Requires a descendant of ProviderScope in the tree — uses StorefrontCartScreen.
void _seedCart(WidgetTester tester, List<CartLine> lines) {
  // containerOf needs a *descendant* of ProviderScope, not the scope itself.
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  final notifier = ProviderScope.containerOf(el).read(cartProvider.notifier);
  for (final l in lines) {
    notifier.add(l);
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

void main() {
  // ── Cart screen: price visibility ──────────────────────────────────────────

  group('StorefrontCartScreen — price visibility', () {
    testWidgets('shows price in priced mode (showPrices = true)', (tester) async {
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_configOverride(showPrices: true)],
      ));
      _seedCart(tester, [_pricedLine()]);
      await tester.pumpAndSettle();

      // Total row visible
      expect(find.text('Total (incl. VAT)'), findsOneWidget);
      // Price amounts visible in cart line subtitle and button
      expect(find.textContaining('9.99'), findsWidgets);
      expect(find.textContaining('Pay GBP'), findsOneWidget);
    });

    testWidgets('hides all prices in catalog mode (showPrices = false)', (tester) async {
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_configOverride(showPrices: false)],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      // No currency or price amount should appear anywhere
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.textContaining('0.00'), findsNothing);

      // Total row must NOT be visible
      expect(find.text('Total (incl. VAT)'), findsNothing);

      // Pay now / Pay later toggle must NOT be visible
      expect(find.text('Pay now'), findsNothing);
      expect(find.text('Pay later'), findsNothing);

      // Checkout button must say "Place order", not "Pay GBP …"
      expect(find.text('Place order'), findsOneWidget);
    });

    testWidgets('hides price even when cart items carry a non-zero unitPrice',
        (tester) async {
      // Regression: items added before config loaded (race window) still had
      // real prices stored in the CartLine. The UI must not show them.
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_configOverride(showPrices: false)],
      ));
      _seedCart(tester, [_pricedLine(unitPrice: 19.99, currency: 'GBP')]);
      await tester.pumpAndSettle();

      expect(find.textContaining('19.99'), findsNothing);
      expect(find.text('Total (incl. VAT)'), findsNothing);
      expect(find.text('Place order'), findsOneWidget);
    });
  });

  // ── Cart screen: delivery validation feedback ──────────────────────────────

  group('StorefrontCartScreen — delivery form validation', () {
    testWidgets('shows snackbar when checkout tapped with empty delivery address',
        (tester) async {
      // Use a taller viewport so the checkout panel fits without clipping.
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_configOverride(showPrices: false)],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      // Switch to DELIVERY mode
      await tester.tap(find.text('Deliver to home'));
      await tester.pumpAndSettle();

      // Delivery address form is now visible
      expect(find.text('Address line 1'), findsOneWidget);

      // Scroll the checkout panel until the button is visible, then tap it.
      await tester.ensureVisible(find.text('Place order'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Place order'));
      await tester.pumpAndSettle();

      // The "fill in address" snackbar must appear
      expect(
        find.text('Please fill in all delivery address fields.'),
        findsOneWidget,
      );
    });

    testWidgets('filled delivery form shows no "Required" errors and no address snackbar',
        (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_configOverride(showPrices: false)],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      // Switch to DELIVERY
      await tester.tap(find.text('Deliver to home'));
      await tester.pumpAndSettle();

      // Fill all required fields
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Address line 1'), '1 High St');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'City'), 'London');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Postal code'), 'EC1A 1BB');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Recipient name'), 'Jane Doe');
      await tester.enterText(
          find.widgetWithText(TextFormField, 'Recipient phone'), '07700900000');
      await tester.pump();

      // No "Required" inline errors should be visible — form is fully filled.
      expect(find.text('Required'), findsNothing);
      // The address-missing snackbar should not appear (it only fires on checkout).
      expect(
        find.text('Please fill in all delivery address fields.'),
        findsNothing,
      );
    });

    testWidgets('collect-from-store shows no address form', (tester) async {
      await tester.pumpWidget(_scope(
        const StorefrontCartScreen(),
        overrides: [_configOverride(showPrices: false)],
      ));
      _seedCart(tester, [_catalogLine()]);
      await tester.pumpAndSettle();

      // PICKUP is the default — no delivery address form should be visible.
      expect(find.text('Address line 1'), findsNothing);
      expect(find.text('Recipient name'), findsNothing);
      // Checkout button is visible in collect mode.
      expect(find.text('Place order'), findsOneWidget);
    });
  });

  // ── OfferPriceAdd: loading-state placeholder ───────────────────────────────

  group('OfferPriceAdd — config loading state', () {
    testWidgets('shows placeholder while config is loading', (tester) async {
      const product = StoreProduct(id: 'p1', name: 'Widget A');
      await tester.pumpWidget(_scope(
        const OfferPriceAdd(product: product),
        overrides: [_configLoading()],
      ));
      // Single pump — the future never resolves, so loading state persists.
      await tester.pump();

      expect(find.text('…'), findsOneWidget);
      // Add-to-cart button must not be available while mode is unknown
      expect(find.byIcon(Icons.add_shopping_cart), findsNothing);
    });

    testWidgets('shows add-to-cart in catalog mode once config resolves',
        (tester) async {
      const product = StoreProduct(id: 'p1', name: 'Widget A');
      // Override availability so variant 'v1' is treated as in-stock.
      await tester.pumpWidget(_scope(
        const OfferPriceAdd(product: product),
        overrides: [
          _configOverride(showPrices: false),
          storefrontAvailabilityProvider
              .overrideWith((ref) async => {'v1': true}),
          productFirstVariantProvider('p1').overrideWith(
            (ref) async => const StoreVariant(id: 'v1', sku: 'SKU-001'),
          ),
        ],
      ));
      await tester.pumpAndSettle();

      // Catalog mode: no price text, but add-to-cart icon present
      expect(find.textContaining('GBP'), findsNothing);
      expect(find.byIcon(Icons.add_shopping_cart), findsOneWidget);
    });
  });
}
