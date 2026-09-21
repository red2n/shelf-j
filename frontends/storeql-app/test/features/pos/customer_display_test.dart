import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/customer_display.dart';
import 'package:storeql_app/features/pos/customer_display_channel.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';

// ---------------------------------------------------------------------------
// The customer-facing display: what the till tells it, and what it shows —
// idle with the store's name, the sale as it is rung up with the deposit and
// any discount as their own rows, then what was paid and the change, held over
// the till clearing its cart until the next customer's first line.
// ---------------------------------------------------------------------------

class _FakeChannel implements CustomerDisplayChannel {
  final controller = StreamController<Map<String, dynamic>>.broadcast();
  final posted = <Map<String, dynamic>>[];
  int opened = 0;

  @override
  bool get supported => true;
  @override
  void post(Map<String, dynamic> message) => posted.add(message);
  @override
  Stream<Map<String, dynamic>> get messages => controller.stream;
  @override
  void openWindow() => opened++;
}

const _cola = PosLine(
  variantId: 'v-cola',
  sku: 'COLA',
  name: 'Cola 500 ml',
  qty: 3,
  unitPrice: 1.5,
  currency: 'EUR',
  depositMaterial: 'PET',
  depositVolumeMl: 500,
  depositEach: 0.25,
);
const _crisps = PosLine(
  variantId: 'v-crisps',
  sku: 'CRISPS',
  name: 'Crisps',
  qty: 2,
  unitPrice: 1.0,
  currency: 'EUR',
);

Future<_FakeChannel> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 900);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final channel = _FakeChannel();
  addTearDown(channel.controller.close);
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[customerDisplayChannelProvider.overrideWithValue(channel)],
    child: const MaterialApp(home: CustomerDisplayScreen()),
  ));
  await tester.pump();
  return channel;
}

void main() {
  test('the till tells the display the lines, the discount, the deposit and the total', () {
    final m = customerDisplaySale(
        storeName: 'Berlin Mitte', currency: 'EUR', lines: const [_cola, _crisps], discount: 1.0);
    expect(m['type'], 'sale');
    expect((m['lines'] as List).length, 2);
    expect((m['lines'] as List).first['qty'], '3');
    expect(m['subtotal'], closeTo(6.5, 1e-9));
    expect(m['discount'], closeTo(1.0, 1e-9));
    expect(m['deposit'], closeTo(0.75, 1e-9));
    expect(m['total'], closeTo(6.25, 1e-9), reason: 'goods less the discount plus the deposit');
  });

  test('an empty cart is idle, and a discount never exceeds the goods', () {
    expect(customerDisplaySale(storeName: 'S', currency: '', lines: const [], discount: 0)['type'], 'idle');
    final m = customerDisplaySale(storeName: 'S', currency: 'EUR', lines: const [_crisps], discount: 99);
    expect(m['discount'], closeTo(2.0, 1e-9));
    expect(m['total'], closeTo(0, 1e-9));
  });

  testWidgets('idle, then the sale as it is rung up, with deposit and discount as their own rows',
      (tester) async {
    final channel = await _pump(tester);
    expect(find.byKey(const Key('display-idle')), findsOneWidget);
    expect(find.text('Welcome'), findsOneWidget);

    channel.controller.add(customerDisplaySale(
        storeName: 'Berlin Mitte', currency: 'EUR', lines: const [_cola, _crisps], discount: 1.0));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('display-sale')), findsOneWidget);
    expect(find.text('3 × Cola 500 ml'), findsOneWidget);
    expect(find.text('EUR 4.50'), findsOneWidget);
    expect(find.byKey(const Key('display-deposit')), findsOneWidget);
    expect(find.text('EUR 0.75'), findsOneWidget);
    expect(find.byKey(const Key('display-discount')), findsOneWidget);
    expect(find.text('EUR 6.25'), findsOneWidget);
  });

  testWidgets('paid shows the change and holds over the till clearing, until the next customer',
      (tester) async {
    final channel = await _pump(tester);
    channel.controller.add(customerDisplayPaid(
        storeName: 'Berlin Mitte', currency: 'EUR', total: 6.25, paid: 10, change: 3.75));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('display-paid')), findsOneWidget);
    expect(find.text('Thank you'), findsOneWidget);
    expect(find.byKey(const Key('display-change')), findsOneWidget);
    expect(find.text('EUR 3.75'), findsOneWidget);

    // The till clears its cart the moment the sale completes: the display keeps the change up.
    channel.controller.add(customerDisplaySale(storeName: 'Berlin Mitte', currency: '', lines: const [], discount: 0));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('display-paid')), findsOneWidget);

    // The next customer's first line takes over at once.
    channel.controller.add(customerDisplaySale(
        storeName: 'Berlin Mitte', currency: 'EUR', lines: const [_crisps], discount: 0));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('display-sale')), findsOneWidget);
    await tester.pump(customerDisplayPaidHold + const Duration(seconds: 1));
    expect(find.byKey(const Key('display-sale')), findsOneWidget, reason: 'the hold timer does not wipe a sale in progress');
  });

  testWidgets('with nothing following, a paid screen goes idle with the store name after the hold',
      (tester) async {
    final channel = await _pump(tester);
    channel.controller.add(customerDisplayPaid(
        storeName: 'Berlin Mitte', currency: 'EUR', total: 2, paid: 2, change: 0));
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('display-change')), findsNothing, reason: 'no change, no change row');
    await tester.pump(customerDisplayPaidHold + const Duration(seconds: 1));
    expect(find.byKey(const Key('display-idle')), findsOneWidget);
    expect(find.text('Berlin Mitte'), findsOneWidget);
  });
}
