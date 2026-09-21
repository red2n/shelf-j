import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/account_screen.dart';
import 'package:storeql_app/features/storefront/cart_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// Checkout offers the shopper's address book (12.10): the default first,
// picking one fills the delivery form, the form stays editable, and a
// shopper with no book — or no account — sees no picker at all.

class _FakeAuthNotifier extends StorefrontAuthNotifier {
  _FakeAuthNotifier({bool signedIn = true}) {
    if (signedIn) {
      state = const StorefrontAuthState(
          accessToken: 'tok', refreshToken: 'ref', email: 'shopper@example.com');
    }
  }
}

const _home = SavedAddress(
    id: 'a-1', type: 'HOME', line1: '12 High Street', line2: 'Flat 2', city: 'London',
    country: 'GB', pincode: 'EC1A 1BB', isDefault: false);
const _work = SavedAddress(
    id: 'a-2', type: 'WORK', line1: '1 Office Park', city: 'Leeds', country: 'GB',
    pincode: 'LS1 4AP', isDefault: true);
const _me = MyCustomer(
    id: 'c-1', email: 'shopper@example.com', phone: '07700900123', firstName: 'Chris',
    lastName: 'Carter');

Future<void> _pump(WidgetTester tester,
    {List<SavedAddress> addresses = const [_home, _work], bool signedIn = true}) async {
  tester.view.physicalSize = const Size(900, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      storefrontConfigProvider.overrideWith(
          (ref) async => const StorefrontConfig(showPrices: true, storeName: 'Test Store')),
      storefrontAuthProvider.overrideWith((ref) => _FakeAuthNotifier(signedIn: signedIn)),
      // Signed out, the real providers answer empty before touching the network.
      if (signedIn) myAddressesProvider.overrideWith((ref) async => addresses),
      if (signedIn) myCustomerProvider.overrideWith((ref) async => _me),
    ],
    child: const MaterialApp(home: Scaffold(body: StorefrontCartScreen())),
  ));
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  ProviderScope.containerOf(el).read(cartProvider.notifier).add(CartLine(
      variantId: 'v1', productName: 'Widget A', sku: 'SKU-001', unitPrice: 9.99, currency: 'GBP'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Deliver to home'));
  await tester.pumpAndSettle();
}

String _fieldText(WidgetTester tester, String label) {
  final field = tester.widget<TextFormField>(find.ancestor(
      of: find.text(label), matching: find.byType(TextFormField)).first);
  return field.controller!.text;
}

void main() {
  testWidgets('the book is offered, default first, and picking one fills the form',
      (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('cart-saved-address')), findsOneWidget);
    await tester.tap(find.byKey(const Key('cart-saved-address')));
    await tester.pumpAndSettle();
    // The default is listed first.
    final items = find.byType(DropdownMenuItem<String?>);
    expect(tester.getTopLeft(find.textContaining('1 Office Park').last).dy <
        tester.getTopLeft(find.textContaining('12 High Street').last).dy, isTrue);
    expect(items, findsWidgets);
    await tester.tap(find.textContaining('12 High Street').last);
    await tester.pumpAndSettle();
    expect(_fieldText(tester, 'Address line 1'), '12 High Street');
    expect(_fieldText(tester, 'Address line 2 (optional)'), 'Flat 2');
    expect(_fieldText(tester, 'City'), 'London');
    expect(_fieldText(tester, 'Postal code'), 'EC1A 1BB');
    // The recipient comes from the shop's record of the shopper when the form had none.
    expect(_fieldText(tester, 'Recipient name'), 'Chris Carter');
    expect(_fieldText(tester, 'Recipient phone'), '07700900123');
  });

  testWidgets('the form stays editable after a pick, and a typed recipient is kept',
      (tester) async {
    await _pump(tester);
    await tester.enterText(
        find.ancestor(of: find.text('Recipient name'), matching: find.byType(TextFormField)).first,
        'Sam Carter');
    await tester.tap(find.byKey(const Key('cart-saved-address')));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('1 Office Park').last);
    await tester.pumpAndSettle();
    expect(_fieldText(tester, 'Recipient name'), 'Sam Carter');
    expect(_fieldText(tester, 'City'), 'Leeds');
    await tester.enterText(
        find.ancestor(of: find.text('City'), matching: find.byType(TextFormField)).first,
        'Bradford');
    expect(_fieldText(tester, 'City'), 'Bradford');
  });

  testWidgets('no book, no picker; signed out, no picker', (tester) async {
    await _pump(tester, addresses: const []);
    expect(find.byKey(const Key('cart-saved-address')), findsNothing);
    expect(find.text('Address line 1'), findsOneWidget);
  });

  testWidgets('a signed-out shopper is shown the plain form', (tester) async {
    await _pump(tester, signedIn: false);
    expect(find.byKey(const Key('cart-saved-address')), findsNothing);
    expect(find.text('Address line 1'), findsOneWidget);
  });
}
