import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/router.dart';
import 'package:storeql_app/features/admin/admin_shell.dart';

// ---------------------------------------------------------------------------
// A storekeeper's back-office menu and the router agree: every page the menu
// offers a storekeeper is one the router lets them open, and the pages whose
// reads are management-only (age checks, shelf space) are offered to neither.
// The obligations register is a staff read, so a storekeeper may open it.
// ---------------------------------------------------------------------------

void main() {
  test('every page the menu offers a storekeeper, the router lets them open', () {
    expect(storekeeperAdminRoutes, isNotEmpty);
    for (final route in storekeeperAdminRoutes) {
      expect(storekeeperAdminAllowed(route), isTrue, reason: '$route is in the menu but the router refuses it');
    }
  });

  test('management-only pages are not offered, and the staff-readable register is', () {
    expect(storekeeperAdminRoutes, isNot(contains('/admin/age-checks')));
    expect(storekeeperAdminRoutes, isNot(contains('/admin/shelf-space')));
    expect(storekeeperAdminAllowed('/admin/age-checks'), isFalse);
    expect(storekeeperAdminAllowed('/admin/shelf-space'), isFalse);
    expect(storekeeperAdminRoutes, contains('/admin/obligations'));
    expect(storekeeperAdminAllowed('/admin/obligations'), isTrue);
  });
}
