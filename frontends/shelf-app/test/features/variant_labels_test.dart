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
    test('falls back to an ellipsis + the end of the id when unresolved', () {
      final name =
          variantDisplayName('01a0905d-7082-7518-9ec6-aee90d72a43e', const {});
      expect(name, '…0d72a43e');
      expect(variantSku('unknown', const {}), '');
    });
    // A bulk import creates many variants in the same millisecond; their ids
    // share the first eight characters, so a label cut from the front would
    // show every unresolved one as the same "01a0905d…".
    test('variants created together get different fallback labels', () {
      final a =
          variantDisplayName('01a0905d-7082-7518-9ec6-00000000a001', const {});
      final b =
          variantDisplayName('01a0905d-7082-7519-9ec6-00000000a002', const {});
      expect(a, '…0000a001');
      expect(b, '…0000a002');
    });
    test('an id shorter than the handle is shown whole', () {
      expect(variantDisplayName('v9', const {}), '…v9');
    });
  });
}
