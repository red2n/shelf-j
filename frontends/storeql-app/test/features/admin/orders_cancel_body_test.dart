import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D49: the cancel dialog calls its reason optional, but it always sent one —
// an empty string — and the server refuses a body whose reason is blank. No
// reason now means no body, which the server accepts as "no reason given".
// ---------------------------------------------------------------------------

void main() {
  test('no reason is no body, not an empty reason', () {
    expect(cancelBody(null), isNull);
    expect(cancelBody(''), isNull);
    expect(cancelBody('   '), isNull);
  });

  test('a reason is sent trimmed', () {
    expect(cancelBody('  customer request '), {'reason': 'customer request'});
  });
}
