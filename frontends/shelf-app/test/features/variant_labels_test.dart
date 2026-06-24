import 'package:flutter_test/flutter_test.dart';
import 'package:shelf_app/features/admin/providers/admin_providers.dart';

void main() {
  group('variantIdsKey', () {
    test('sorts, de-dupes and drops blanks', () {
      expect(variantIdsKey(['b', 'a', 'a', '']), 'a,b');
    });
    test('empty input is empty key', () {
      expect(variantIdsKey(const []), '');
    });
  });

  group('variantDisplayName / variantSku', () {
    const labels = {
      'v1': VariantLabel(productName: 'Rice 5kg', sku: 'RICE-5KG'),
    };
    test('returns the product name + sku when resolved', () {
      expect(variantDisplayName('v1', labels), 'Rice 5kg');
      expect(variantSku('v1', labels), 'RICE-5KG');
    });
    test('falls back to a short UUID + ellipsis when unresolved', () {
      final name = variantDisplayName('123e4567-e89b', const {});
      expect(name, startsWith('123e4567'));
      expect(name, endsWith('…'));
      expect(variantSku('unknown', const {}), '');
    });
  });
}
