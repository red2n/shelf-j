/// Barrel for the platform-console shell's screens, imported as a single
/// `deferred as` library from `core/router.dart` so a storefront/admin/POS
/// visit never downloads the platform-admin code.
library;

export 'billing_screen.dart';
export 'plans_screen.dart';
export 'platform_dashboard_screen.dart';
export 'security_incidents_screen.dart';
export 'tenants_screen.dart';
