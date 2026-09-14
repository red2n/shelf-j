import 'package:flutter/foundation.dart';
import '../constants.dart';

@immutable
sealed class AuthState {
  const AuthState();
}

class AuthUnauthenticated extends AuthState {
  const AuthUnauthenticated();
}

class AuthAuthenticated extends AuthState {
  final String accessToken;
  final String refreshToken;
  final String userId;
  final String? tenantId;
  final List<String> roles;
  final String? email;

  /// Stores this holder may operate in — mirrors the JWT's `storeIds` claim. Empty means
  /// unrestricted (e.g. OWNER/PLATFORM_ADMIN), matching backend TenantContext semantics; present
  /// means store-bound staff (e.g. a CASHIER/STOREKEEPER assigned to specific stores).
  final List<String> storeIds;

  /// The token's `perms` claim (20.10), or null when the token carries none —
  /// a login with no custom role, judged by its roles' defaults. Mirrors the
  /// server's `TenantContext.permissions()`: owners hold everything, a claim
  /// narrows, no claim means the tier's defaults.
  final List<String>? permissions;

  const AuthAuthenticated({
    required this.accessToken,
    required this.refreshToken,
    required this.userId,
    this.tenantId,
    required this.roles,
    this.email,
    this.storeIds = const [],
    this.permissions,
  });

  /// What a built-in role holds by default. Kept in step with the server's
  /// permission catalogue; the app only uses it to hide controls that the
  /// server would refuse, never to allow anything.
  static const Map<String, List<String>> _tierDefaults = {
    'MANAGER': [
      'sales.void', 'sales.refund', 'till.no_sale', 'till.manage', 'stock.adjust',
      'purchasing.approve', 'purchasing.invoices.decide', 'finance.journal',
      'pricing.write', 'customers.privacy', 'staff.manage', 'finance.payments',
    ],
    'STOREKEEPER': ['stock.adjust', 'purchasing.approve'],
    'CASHIER': ['till.no_sale', 'purchasing.approve'],
  };

  /// Whether this login may take the named decision, as the server will judge
  /// it: an owner or platform admin always; otherwise the claim when there is
  /// one, else the roles' defaults.
  bool hasPermission(String code) {
    if (roles.contains(UserRoles.owner) || roles.contains(UserRoles.platformAdmin)) {
      return true;
    }
    if (permissions != null) return permissions!.contains(code);
    return roles.any((r) => (_tierDefaults[r] ?? const []).contains(code));
  }

  /// Full tenant console (OWNER/MANAGER). Storekeepers also land in admin but
  /// with a restricted nav — see [isStorekeeper] / [canAccessAdmin].
  bool get isManager =>
      roles.contains(UserRoles.owner) || roles.contains(UserRoles.manager);

  /// Warehouse operator: receive stock, view levels/batches. Not pricing/staff.
  bool get isStorekeeper => roles.contains(UserRoles.storekeeper);

  /// True when the user may enter the `/admin/*` shell at all.
  bool get canAccessAdmin => isManager || isStorekeeper;

  /// Alias for [canAccessAdmin] — used by the router and existing shells.
  bool get isAdmin => canAccessAdmin;

  bool get isCashier => roles.contains(UserRoles.cashier);
  bool get isCustomer => roles.contains(UserRoles.customer);

  // OWNER/MANAGER with no tenantId haven't completed onboarding yet.
  // PLATFORM_ADMIN is a global superuser and never needs onboarding.
  // Storekeepers are always staffed onto an existing tenant, so they never
  // self-onboard.
  bool get needsOnboarding =>
      !roles.contains(UserRoles.platformAdmin) &&
      tenantId == null &&
      (isManager || roles.isEmpty);

  bool get isPlatformAdmin => roles.contains(UserRoles.platformAdmin);

  String get homeRoute {
    if (needsOnboarding) return '/onboarding';
    if (isPlatformAdmin) return '/platform/overview';
    // Storekeeper-only staff land on Inventory — their day job — not the
    // manager dashboard (which hits management-only report APIs).
    if (isStorekeeper && !isManager) return '/admin/inventory';
    if (isManager) return '/admin/dashboard';
    if (isCashier) return '/pos/cart';
    return '/store/products';
  }
}
