import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/pos/cart_screen.dart';
import 'package:shelf_app/features/pos/pos_providers.dart';
import 'package:shelf_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// SJ-D6: order-svc now honours a POS discount instead of discarding it, and
// refuses one that arrives without a reason (ORDER_DISCOUNT_REASON_REQUIRED).
// The till therefore has to collect the reason at the moment the discount is
// applied — if it does not, the sale fails at tender time, after the customer
// has been told a price. These tests pin that contract at the dialog, which is
// the only place the reason can still be asked for.
// ---------------------------------------------------------------------------

/// A no-op session notifier — overrides restore() so the real constructor
/// never reaches for the network to restore a clocked-in session.
class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

Widget _scope(List<Override> overrides) => ProviderScope(
      overrides: [
        posStoresProvider.overrideWith((ref) async => const []),
        posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
        ...overrides,
      ],
      child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
    );

/// One £5.00 line, so the subtotal under test is exactly 5.00.
PosLine _line() => const PosLine(
      variantId: 'v-1',
      sku: 'SKU-1',
      name: 'Product 1',
      qty: 1,
      unitPrice: 5.0,
      currency: 'GBP',
    );

void _useNarrowViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

ProviderContainer _container(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first));

/// Builds the screen with one line already scanned and opens the discount dialog.
Future<void> _openDiscountDialog(WidgetTester tester) async {
  _useNarrowViewport(tester);
  await tester.pumpWidget(_scope(const []));
  await tester.pumpAndSettle();
  _container(tester).read(posCartProvider.notifier).loadLines([_line()]);
  await tester.pump();

  await tester.tap(find.text('Add discount'));
  await tester.pumpAndSettle();
  expect(find.text('Order discount'), findsOneWidget);
}

/// The dialog's two fields, in order: amount, then reason.
Finder _field(int index) => find
    .descendant(of: find.byType(AlertDialog), matching: find.byType(TextField))
    .at(index);

bool _applyEnabled(WidgetTester tester) =>
    tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Apply')).onPressed != null;

void main() {
  group('PosCartScreen — order discount', () {
    testWidgets('Apply stays disabled until both an amount and a reason are given',
        (tester) async {
      await _openDiscountDialog(tester);

      // Nothing entered yet.
      expect(_applyEnabled(tester), isFalse);

      // An amount alone is not enough — this is the case that used to reach the
      // server and come back a 400.
      await tester.enterText(_field(0), '2.50');
      await tester.pumpAndSettle();
      expect(_applyEnabled(tester), isFalse);

      // A reason alone is not enough either.
      await tester.enterText(_field(0), '');
      await tester.enterText(_field(1), 'damaged packaging');
      await tester.pumpAndSettle();
      expect(_applyEnabled(tester), isFalse);

      // Both present — now it may be applied.
      await tester.enterText(_field(0), '2.50');
      await tester.pumpAndSettle();
      expect(_applyEnabled(tester), isTrue);
    });

    testWidgets('a whitespace-only reason does not count as a reason', (tester) async {
      await _openDiscountDialog(tester);
      await tester.enterText(_field(0), '2.50');
      await tester.enterText(_field(1), '   ');
      await tester.pumpAndSettle();

      // The server trims before validating, so the till must too — otherwise a
      // cashier can satisfy this dialog with a space and still be refused.
      expect(_applyEnabled(tester), isFalse);
    });

    testWidgets('applying stores the amount and the reason together', (tester) async {
      await _openDiscountDialog(tester);
      await tester.enterText(_field(0), '2.50');
      await tester.enterText(_field(1), '  damaged packaging  ');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();

      final container = _container(tester);
      expect(container.read(posDiscountProvider), 2.50);
      // Trimmed, because that is what tender_screen sends and what the server stores.
      expect(container.read(posDiscountReasonProvider), 'damaged packaging');
    });

    testWidgets('the discount is clamped to the subtotal', (tester) async {
      await _openDiscountDialog(tester);
      await tester.enterText(_field(0), '999');
      await tester.enterText(_field(1), 'goodwill');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();

      // Subtotal is 5.00; the server refuses a discount above it outright
      // (ORDER_DISCOUNT_EXCEEDS_SUBTOTAL), so the till never offers to send one.
      expect(_container(tester).read(posDiscountProvider), 5.0);
    });

    testWidgets('Clear removes the reason as well as the amount', (tester) async {
      await _openDiscountDialog(tester);
      await tester.enterText(_field(0), '2.50');
      await tester.enterText(_field(1), 'damaged packaging');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Discount'));
      await tester.pumpAndSettle();
      // Scoped to the dialog: the sale pane has its own "Clear" (clear the whole sale).
      await tester.tap(find.descendant(
          of: find.byType(AlertDialog), matching: find.text('Clear')));
      await tester.pumpAndSettle();

      final container = _container(tester);
      expect(container.read(posDiscountProvider), 0);
      // A stale reason left behind would be attributed to whatever discount came
      // next, in an append-only table that exists to say who authorised what.
      expect(container.read(posDiscountReasonProvider), isEmpty);
    });
  });
}
