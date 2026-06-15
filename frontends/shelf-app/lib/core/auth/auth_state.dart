import 'package:flutter/foundation.dart';

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

  bool get isAdmin =>
      roles.contains('PLATFORM_ADMIN') ||
      roles.contains('STORE_ADMIN') ||
      roles.contains('OWNER') ||
      roles.contains('MANAGER');
  bool get isCashier => roles.contains('CASHIER');
  bool get isCustomer => roles.contains('CUSTOMER');

  // OWNER/ADMIN with no tenantId haven't completed onboarding yet.
  // PLATFORM_ADMIN is a global superuser and never needs onboarding.
  bool get needsOnboarding =>
      !roles.contains('PLATFORM_ADMIN') &&
      tenantId == null &&
      (isAdmin || roles.isEmpty);

  bool get isPlatformAdmin => roles.contains('PLATFORM_ADMIN');

  String get homeRoute {
    if (needsOnboarding) return '/onboarding';
    if (isPlatformAdmin) return '/platform/overview';
    if (isAdmin) return '/admin/dashboard';
    if (isCashier) return '/pos/cart';
    return '/store/products';
  }
}
