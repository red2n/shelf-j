import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/pos/variable_measure_barcode.dart';

// A labelling scale's barcode read exactly, or not at all. The positive cases
// are the UK price-embedded convention and a weight-embedded one; the negative
// cases are every way a scan can look like a label without being one.

String withCheck(String twelve) {
  var sum = 0;
  for (var i = 0; i < 12; i++) {
    final d = twelve.codeUnitAt(i) - 48;
    sum += i.isEven ? d : d * 3;
  }
  return twelve + ((10 - (sum % 10)) % 10).toString();
}

const ukPrice = LabelScheme(
    prefixes: ['20', '21'], itemDigits: 5, valueKind: 'PRICE', valueDecimals: 2);
const weight5 = LabelScheme(
    prefixes: ['29'], itemDigits: 5, valueKind: 'WEIGHT', valueDecimals: 3);
const ukPriceWithCheck = LabelScheme(
    prefixes: ['20'],
    itemDigits: 4,
    valueKind: 'PRICE',
    valueDecimals: 2,
    priceCheckDigit: true);

void main() {
  test('a price-embedded UK label reads the item and the price', () {
    // 20 + item 12345 + price 00450 (= £4.50) + check
    final code = withCheck('201234500450');
    final r = readVariableMeasureBarcode(code, ukPrice)!;
    expect(r.itemCode, '12345');
    expect(r.price, 4.50);
    expect(r.weight, isNull);
  });

  test('a weight-embedded label reads the weight in kilograms', () {
    // 29 + item 00042 + weight 00375 (= 0.375 kg) + check
    final code = withCheck('290004200375');
    final r = readVariableMeasureBarcode(code, weight5)!;
    expect(r.itemCode, '00042');
    expect(r.weight, 0.375);
    expect(r.price, isNull);
  });

  test('a scheme with a price check digit skips it', () {
    // 20 + item 1234 + price-check 7 + price 01250 (= £12.50) + check
    final code = withCheck('201234701250');
    final r = readVariableMeasureBarcode(code, ukPriceWithCheck)!;
    expect(r.itemCode, '1234');
    expect(r.price, 12.50);
  });

  test('the quantity a price implies is rounded to the gram', () {
    expect(quantityFromPrice(4.50, 12.00), 0.375);
    expect(quantityFromPrice(1.00, 3.00), 0.333);
    expect(quantityFromPrice(4.50, 0), isNull);
  });

  group('is not a label', () {
    test('a wrong check digit', () {
      final code = withCheck('201234500450');
      final bad = code.substring(0, 12) + ((int.parse(code[12]) + 1) % 10).toString();
      expect(readVariableMeasureBarcode(bad, ukPrice), isNull);
    });

    test('a prefix that is not this scale', () {
      expect(readVariableMeasureBarcode(withCheck('501234500450'), ukPrice), isNull);
    });

    test('an ordinary EAN-13 that happens to be valid', () {
      expect(readVariableMeasureBarcode('5012345678900', ukPrice), isNull);
    });

    test('the wrong length, letters, or empty', () {
      expect(readVariableMeasureBarcode('20123450045', ukPrice), isNull);
      expect(readVariableMeasureBarcode('2012345004501', ukPrice), isNull);
      expect(readVariableMeasureBarcode('20123AB00450', ukPrice), isNull);
      expect(readVariableMeasureBarcode('', ukPrice), isNull);
    });

    test('a zero price or weight is not a reading', () {
      expect(readVariableMeasureBarcode(withCheck('201234500000'), ukPrice), isNull);
      expect(readVariableMeasureBarcode(withCheck('290004200000'), weight5), isNull);
    });

    test('a scheme that leaves no room for a value', () {
      const broken = LabelScheme(
          prefixes: ['20'], itemDigits: 5, valueKind: 'PRICE', valueDecimals: 2, priceCheckDigit: true);
      // 2 + 5 + 1 + value + 1 = 13 leaves 4 digits: fine. Force fewer with an absurd scheme.
      const worse = LabelScheme(prefixes: ['20'], itemDigits: 9, valueKind: 'PRICE', valueDecimals: 2);
      expect(readVariableMeasureBarcode(withCheck('201234500450'), broken), isNotNull);
      expect(readVariableMeasureBarcode(withCheck('201234500450'), worse), isNull);
    });
  });

  test('a scheme is read from the register\'s JSON, or refused when it is not one', () {
    expect(
        LabelScheme.fromJson({'prefixes': ['20'], 'itemDigits': 5, 'valueKind': 'PRICE', 'valueDecimals': 2})!
            .prefixes,
        ['20']);
    expect(LabelScheme.fromJson({'prefixes': [], 'itemDigits': 5, 'valueKind': 'PRICE', 'valueDecimals': 2}), isNull);
    expect(LabelScheme.fromJson({'itemDigits': 5}), isNull);
    expect(LabelScheme.fromJson(null), isNull);
  });
}
