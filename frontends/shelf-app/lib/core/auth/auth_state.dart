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

  const AuthAuthenticated({
    required this.accessToken,
    required this.refreshToken,
    required this.userId,
    this.tenantId,
    required this.roles,
    this.email,
  });

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
