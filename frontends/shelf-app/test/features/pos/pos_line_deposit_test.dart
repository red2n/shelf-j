import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/pos/pos_providers.dart';

// ---------------------------------------------------------------------------
// The deposit on a till line (09.16): one per container, its own figure, never
// part of the price — and kept when the quantity changes.
// ---------------------------------------------------------------------------

void main() {
  const bottle = PosLine(
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
  const crisps = PosLine(
    variantId: 'v-crisps',
    sku: 'CRISPS',
    name: 'Crisps',
    qty: 2,
    unitPrice: 1.0,
    currency: 'EUR',
  );

  test('the deposit is per container and no part of the line total', () {
    expect(bottle.depositTotal, closeTo(0.75, 1e-9));
    expect(bottle.lineTotal, closeTo(4.5, 1e-9));
    expect(crisps.depositTotal, 0);
  });

  test('changing the quantity keeps the container and re-counts the deposit', () {
    final five = bottle.copyWith(qty: 5);
    expect(five.depositMaterial, 'PET');
    expect(five.depositVolumeMl, 500);
    expect(five.depositTotal, closeTo(1.25, 1e-9));
  });

  test('a container no scheme takes back carries no deposit', () {
    const jar = PosLine(
      variantId: 'v-jar',
      sku: 'JAR',
      name: 'Pickles 5 l',
      qty: 1,
      unitPrice: 9.0,
      currency: 'EUR',
      depositMaterial: 'GLASS',
      depositVolumeMl: 5000,
    );
    expect(jar.depositEach, 0);
    expect(jar.depositTotal, 0);
  });
}
