import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shelf_app/features/storefront/cart_screen.dart';
import 'package:shelf_app/features/storefront/storefront_providers.dart';

// ── Test doubles ─────────────────────────────────────────────────────────────

/// Extends the real notifier so the type matches the provider exactly.
/// Setting [state] synchronously in the constructor body wins the race against
/// the async `_load()` (which reads null from storage in tests and returns
/// early without touching state).
class _FakeAuthNotifier extends StorefrontAuthNotifier {
  _FakeAuthNotifier({bool signedIn = false}) {
    if (signedIn) {
      state = const StorefrontAuthState(
          accessToken: 'tok', refreshToken: 'ref', email: 'test@example.com');
    }
    // signedIn=false: leave state as the default StorefrontAuthState() set by super.
  }
}

/// Extends the real notifier so the type matches the provider exactly.
/// The parent _load() reads null from storage in tests and returns early,
/// leaving our synchronously-set [initial] state intact.
class _FakeOrdersNotifier extends StorefrontOrdersNotifier {
  _FakeOrdersNotifier(List<StorefrontOrderRecord> initial) {
    state = initial;
  }

  @override
  Future<void> add(StorefrontOrderRecord record) async {
    state = [record, ...state]; // skip _persist() — no real storage in tests
  }
}

// ── Provider overrides ────────────────────────────────────────────────────────

Override _configOverride({bool showPrices = false}) =>
    storefrontConfigProvider.overrideWith(
        (ref) async => StorefrontConfig(showPrices: showPrices, storeName: 'Test Store'));

Override _signedIn() => storefrontAuthProvider
    .overrideWith((ref) => _FakeAuthNotifier(signedIn: true));

Override _guest() => storefrontAuthProvider
    .overrideWith((ref) => _FakeAuthNotifier(signedIn: false));

Override _serverOrders(List<ServerOrderSummary> orders) =>
    serverOrdersProvider.overrideWith((ref) async => orders);

Override _serverError() =>
    serverOrdersProvider.overrideWith((ref) async => throw Exception('network error'));

Override _localOrders(List<StorefrontOrderRecord> orders) =>
    storefrontOrdersProvider.overrideWith((ref) => _FakeOrdersNotifier(orders));

/// Dio that immediately rejects every request so tests don't block on network.
Override _failingDio() => storefrontDioProvider.overrideWith((ref) {
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
      dio.interceptors.add(InterceptorsWrapper(
        onRequest: (options, handler) => handler.reject(
          DioException(
              requestOptions: options,
              type: DioExceptionType.connectionError,
              message: 'test network error'),
        ),
      ));
      return dio;
    });

// ── Factories ─────────────────────────────────────────────────────────────────

ServerOrderSummary _serverOrder({
  String id = 'aaaabbbb-cccc-dddd-eeee-ffffffffffff',
  String status = 'PENDING',
  Duration ago = const Duration(minutes: 30),
}) =>
    ServerOrderSummary(
      id: id,
      storeId: 'store-1',
      fulfilmentType: 'PICKUP',
      status: status,
      total: 19.99,
      currency: 'GBP',
      placedAt: DateTime.now().subtract(ago),
    );

StorefrontOrderRecord _localOrder({
  String orderId = 'local-order-001',
  Duration ago = const Duration(hours: 1),
}) =>
    StorefrontOrderRecord(
      orderId: orderId,
      total: 12.0,
      currency: 'GBP',
      itemCount: 2,
      placedAt: DateTime.now().subtract(ago),
      storeName: 'Test Store',
      fulfilmentType: 'PICKUP',
    );

CartLine _line() => CartLine(
      variantId: 'v1',
      productName: 'Widget A',
      sku: 'SKU-001',
      unitPrice: 0,
      currency: '',
    );

// ── Harness ───────────────────────────────────────────────────────────────────

Widget _scope(List<Override> overrides) => ProviderScope(
      overrides: overrides,
      child: const MaterialApp(home: Scaffold(body: StorefrontCartScreen())),
    );

/// GoRouter harness for tests that exercise context.go() navigation.
Widget _routerScope(List<Override> overrides) {
  final router = GoRouter(
    initialLocation: '/store/cart',
    routes: [
      GoRoute(
        path: '/store/cart',
        builder: (_, __) => const Scaffold(body: StorefrontCartScreen()),
      ),
      GoRoute(
        path: '/store/orders',
        builder: (_, __) => const Scaffold(body: Text('Orders page')),
      ),
      GoRoute(
        path: '/store/products',
        builder: (_, __) => const Scaffold(body: Text('Products page')),
      ),
    ],
  );
  return ProviderScope(
    overrides: overrides,
    child: MaterialApp.router(routerConfig: router),
  );
}

/// 800×1200 logical pixels — ensures the checkout CTA fits on screen.
void _setViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void _seedCart(WidgetTester tester, List<CartLine> lines) {
  final el = tester.element(find.byType(StorefrontCartScreen).first);
  final notifier = ProviderScope.containerOf(el).read(cartProvider.notifier);
  for (final l in lines) {
    notifier.add(l);
  }
}


/// Fills the mandatory pickup contact phone and taps the checkout CTA
/// ("Review order"); the pending-order guard runs before the review sheet.
Future<void> _tapCheckout(WidgetTester tester) async {
  await tester.enterText(
      find.widgetWithText(TextField, 'Contact phone *'), '07700900000');
  await tester.pump();
  await tester.ensureVisible(find.text('Review order'));
  await tester.tap(find.text('Review order'));
  await tester.pumpAndSettle();
}

/// Pumps the cart screen with a cart item and opens the pending-order dialog.
/// Returns only once the dialog is visible.
Future<void> _openDialog(
  WidgetTester tester, {
  List<Override> extra = const [],
  bool useRouter = false,
}) async {
  final overrides = [
    _configOverride(),
    _signedIn(),
    _serverOrders([_serverOrder()]),
    _failingDio(),
    ...extra,
  ];
  await tester.pumpWidget(useRouter ? _routerScope(overrides) : _scope(overrides));
  _seedCart(tester, [_line()]);
  await tester.pumpAndSettle();

  await _tapCheckout(tester);

  expect(find.byType(AlertDialog), findsOneWidget,
      reason: 'Dialog should be visible before exercising an action');
}

// ═════════════════════════════════════════════════════════════════════════════
// Tests
// ═════════════════════════════════════════════════════════════════════════════

void main() {
  setUpAll(initializeDateFormatting);

  // ── Signed-in customer: server-backed pending order detection ──────────────

  group('Pending order guard — signed-in, server orders', () {
    testWidgets('no dialog when server order list is empty', (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope([
        _configOverride(),
        _signedIn(),
        _serverOrders([]),
        _failingDio(),
      ]));
      _seedCart(tester, [_line()]);
      await tester.pumpAndSettle();

      await _tapCheckout(tester);

      expect(find.byType(AlertDialog), findsNothing);
    });

    testWidgets('no dialog when every server order is in a terminal status',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope([
        _configOverride(),
        _signedIn(),
        _serverOrders([
          _serverOrder(id: 'id-1', status: 'COMPLETED'),
          _serverOrder(id: 'id-2', status: 'DELIVERED'),
          _serverOrder(id: 'id-3', status: 'CANCELLED'),
        ]),
        _failingDio(),
      ]));
      _seedCart(tester, [_line()]);
      await tester.pumpAndSettle();

      await _tapCheckout(tester);

      expect(find.byType(AlertDialog), findsNothing);
    });

    // Parameterised: each active status must trigger the dialog.
    for (final status in ['PENDING', 'RECEIVED', 'CONFIRMED', 'PROCESSING']) {
      testWidgets('dialog shown when most recent order has status $status',
          (tester) async {
        _setViewport(tester);
        await tester.pumpWidget(_scope([
          _configOverride(),
          _signedIn(),
          _serverOrders([_serverOrder(status: status)]),
          _failingDio(),
        ]));
        _seedCart(tester, [_line()]);
        await tester.pumpAndSettle();

        await _tapCheckout(tester);

        expect(find.byType(AlertDialog), findsOneWidget);
        expect(find.text('You have a pending order'), findsOneWidget);
      });
    }

    testWidgets('dialog displays the first 8 characters of the order ID',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope([
        _configOverride(),
        _signedIn(),
        _serverOrders([_serverOrder(id: 'abcd1234-5678-0000-0000-000000000000')]),
        _failingDio(),
      ]));
      _seedCart(tester, [_line()]);
      await tester.pumpAndSettle();

      await _tapCheckout(tester);

      // Short ID shown in body text (e.g. "Order #abcd1234 placed at …")
      expect(find.textContaining('abcd1234'), findsOneWidget);
    });

    testWidgets('most recent pending order is shown when multiple exist',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope([
        _configOverride(),
        _signedIn(),
        _serverOrders([
          // Older pending order
          _serverOrder(
              id: 'old-order-0000-0000-0000-000000000000',
              ago: const Duration(hours: 3)),
          // Newer pending order — this one should appear in the dialog
          _serverOrder(
              id: 'new-order-1111-1111-1111-111111111111',
              ago: const Duration(minutes: 15)),
        ]),
        _failingDio(),
      ]));
      _seedCart(tester, [_line()]);
      await tester.pumpAndSettle();

      await _tapCheckout(tester);

      expect(find.textContaining('new-orde'), findsOneWidget);
      expect(find.textContaining('old-orde'), findsNothing);
    });

    testWidgets('fails open — no dialog when server throws', (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope([
        _configOverride(),
        _signedIn(),
        _serverError(),
        _failingDio(),
      ]));
      _seedCart(tester, [_line()]);
      await tester.pumpAndSettle();

      await _tapCheckout(tester);

      // A status-check error must never block the customer from checking out.
      expect(find.byType(AlertDialog), findsNothing);
    });
  });

  // ── Guest customer: sign-in gate ───────────────────────────────────────────
  //
  // Checkout requires a signed-in customer before the pending-order guard ever
  // runs (the store needs a phone number on file), so a guest tapping checkout
  // gets the sign-in dialog — never the pending-order dialog.

  group('Checkout sign-in gate — guest', () {
    testWidgets('guest is prompted to sign in, not shown the pending guard',
        (tester) async {
      _setViewport(tester);
      await tester.pumpWidget(_scope([
        _configOverride(),
        _guest(),
        _localOrders([_localOrder(ago: const Duration(hours: 1))]),
        _failingDio(),
      ]));
      _seedCart(tester, [_line()]);
      await tester.pumpAndSettle();

      await _tapCheckout(tester);

      expect(find.text('Sign in'), findsWidgets);
      expect(find.text('You have a pending order'), findsNothing);
    });
  });

  // ── Dialog action: Cancel ─────────────────────────────────────────────────

  group('Pending order dialog — Cancel', () {
    testWidgets('dismisses the dialog and stays on the cart screen',
        (tester) async {
      _setViewport(tester);
      await _openDialog(tester);

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();

      expect(find.byType(AlertDialog), findsNothing);
      expect(find.byType(StorefrontCartScreen), findsOneWidget);
      // No checkout was attempted — no "Checkout failed" snackbar.
      expect(find.textContaining('Checkout failed'), findsNothing);
    });
  });

  // ── Dialog action: Place new order ────────────────────────────────────────

  group('Pending order dialog — Place new order', () {
    testWidgets('dismisses the dialog and proceeds to checkout', (tester) async {
      _setViewport(tester);
      await _openDialog(tester);

      await tester.tap(find.text('Place new order'));
      await tester.pumpAndSettle();

      // Dialog gone — the review sheet is next. Confirm it to fire checkout.
      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text('Review your order'), findsOneWidget);
      await tester.tap(find.text('Place order'));
      await tester.pumpAndSettle();

      // Failing Dio (connection error) → checkout error snackbar confirms the
      // attempt was made, routed through friendlyError's network-error copy.
      expect(find.textContaining("Can't reach the server"), findsOneWidget);
    });

    testWidgets('does not re-show the dialog on the second checkout attempt',
        (tester) async {
      _setViewport(tester);
      // Use two orders so the first trip through the guard finds one,
      // but the second order list is empty (simulating the guard returning
      // null on a re-check that's suppressed by the "place new" path).
      await _openDialog(tester);

      // Choose "Place new order" — the guard does NOT run again for this call.
      await tester.tap(find.text('Place new order'));
      await tester.pumpAndSettle();
      // Confirm the review sheet so the checkout attempt completes.
      await tester.tap(find.text('Place order'));
      await tester.pumpAndSettle();

      // Only one dialog should have appeared throughout the interaction.
      expect(find.byType(AlertDialog), findsNothing);
    });
  });

  // ── Dialog action: View pending order ─────────────────────────────────────

  group('Pending order dialog — View pending order', () {
    testWidgets('navigates to /store/orders and closes the dialog', (tester) async {
      _setViewport(tester);
      await _openDialog(tester, useRouter: true);

      await tester.tap(find.text('View pending order'));
      await tester.pumpAndSettle();

      expect(find.byType(AlertDialog), findsNothing);
      expect(find.text('Orders page'), findsOneWidget);
    });
  });

  // ── Dialog content ────────────────────────────────────────────────────────

  group('Pending order dialog — content', () {
    testWidgets('shows all three action buttons', (tester) async {
      _setViewport(tester);
      await _openDialog(tester);

      expect(find.text('Cancel'), findsOneWidget);
      expect(find.text('Place new order'), findsOneWidget);
      expect(find.text('View pending order'), findsOneWidget);
    });

    testWidgets('contains the pending-actions icon', (tester) async {
      _setViewport(tester);
      await _openDialog(tester);

      expect(find.byIcon(Icons.pending_actions_outlined), findsOneWidget);
    });
  });
}
