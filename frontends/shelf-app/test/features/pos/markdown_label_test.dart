import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/pos/markdown_label.dart';

// The till's side of a reduced-price sticker (05.4): what is one, and what it
// says. The check digit is EAN-13's, so a scanner reads it like any pack.

void main() {
  test('a sticker is prefix 21, thirteen digits, check digit right', () {
    final code = '210000100224${ean13CheckDigit('210000100224')}';
    expect(isMarkdownLabelCode(code), isTrue);
    expect(markdownLabelPrice(code), 2.24);
  });

  test('a known EAN-13 checks but is not a sticker', () {
    expect(ean13CheckDigit('501234567890'), '0');
    expect(isMarkdownLabelCode('5012345678900'), isFalse);
  });

  test(
    'a wrong check digit, another prefix or another length is not a sticker',
    () {
      final code = '210000100224${ean13CheckDigit('210000100224')}';
      final wrong = (int.parse(code[12]) + 1) % 10;
      expect(isMarkdownLabelCode('${code.substring(0, 12)}$wrong'), isFalse);
      expect(isMarkdownLabelCode('20${code.substring(2)}'), isFalse);
      expect(isMarkdownLabelCode(code.substring(0, 12)), isFalse);
      expect(isMarkdownLabelCode('21abc00100224'), isFalse);
      expect(isMarkdownLabelCode(''), isFalse);
    },
  );
}
