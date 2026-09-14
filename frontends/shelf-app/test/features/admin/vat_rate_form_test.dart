import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/admin/pricing_providers.dart';
import 'package:shelf_app/features/admin/vat_rate_form.dart';

// ---------------------------------------------------------------------------
// VAT rates as a business sets them (SJ-D56): typed as percentages, stored as
// fractions, and a warning while the standard rate is missing.
// ---------------------------------------------------------------------------

VatRate _rate(String code) => VatRate.fromJson({
      'code': code,
      'name': 'Rate $code',
      'rate': 0.2,
      'exempt': false,
      'effectiveFrom': '2020-01-01T00:00:00Z',
    });

Future<void> _pump(WidgetTester tester, List<Override> overrides, VoidCallback onAdd) async {
  tester.view.physicalSize = const Size(1200, 800);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(ProviderScope(
    overrides: overrides,
    child: MaterialApp(home: Scaffold(body: StandardVatBanner(onAdd: onAdd))),
  ));
  await tester.pump();
}

void main() {
  test('a percentage becomes the fraction pricing-svc stores', () {
    expect(vatFractionFromPercent('20'), 0.2);
    expect(vatFractionFromPercent('7.5'), 0.075);
    expect(vatFractionFromPercent(' 20 % '), 0.2);
    expect(vatFractionFromPercent('7,5'), 0.075);
    expect(vatFractionFromPercent('0'), 0.0);
    expect(vatFractionFromPercent('100'), 1.0);
  });

  test('anything that is not a percentage from 0 to 100 is no rate at all', () {
    for (final bad in ['', ' ', '101', '-1', '-0', 'twenty', '0.2.1', '1e1', 'NaN', 'Infinity', '20%%x', "20'; --"]) {
      expect(vatFractionFromPercent(bad), isNull, reason: bad);
    }
  });

  test('a stored fraction reads back as the percentage that was typed', () {
    expect(vatPercentText(0.2), '20');
    expect(vatPercentText(0.075), '7.5');
    expect(vatPercentText(0.19), '19');
    expect(vatPercentText(0), '0');
    expect(vatPercentText(1), '100');
    for (final typed in ['20', '7.5', '23', '0', '12.25']) {
      expect(vatPercentText(vatFractionFromPercent(typed)!), typed);
    }
  });

  testWidgets('with no standard rate the banner says nothing is quoted, and offers to add it', (tester) async {
    var added = 0;
    await _pump(tester, [vatRatesProvider.overrideWith((ref) async => [_rate('T5')])], () => added++);
    await tester.pump();
    expect(find.byKey(const Key('standard-vat-missing')), findsOneWidget);
    expect(find.textContaining('no price can be quoted'), findsOneWidget);
    await tester.tap(find.byKey(const Key('standard-vat-add')));
    expect(added, 1);
  });

  testWidgets('with the standard rate set, or before the rates are read, there is no banner', (tester) async {
    await _pump(tester, [vatRatesProvider.overrideWith((ref) async => [_rate('t1')])], () {});
    await tester.pump();
    expect(find.byKey(const Key('standard-vat-missing')), findsNothing);
  });

  testWidgets('a failed read of the rates is not taken to mean the rate is missing', (tester) async {
    await _pump(tester, [vatRatesProvider.overrideWith((ref) async => throw StateError('offline'))], () {});
    await tester.pump();
    await tester.pump();
    expect(find.byKey(const Key('standard-vat-missing')), findsNothing);
  });
}
