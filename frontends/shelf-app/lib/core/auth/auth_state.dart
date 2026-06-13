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
      roles.contains('STORE_ADMIN') || roles.contains('OWNER');
  bool get isCashier => roles.contains('CASHIER');
  bool get isCustomer => roles.contains('CUSTOMER');

  // OWNER/ADMIN with no tenantId haven't completed onboarding yet
  bool get needsOnboarding =>
      tenantId == null && (isAdmin || roles.isEmpty);

  String get homeRoute {
    if (needsOnboarding) return '/onboarding';
    if (isAdmin) return '/admin/dashboard';
    if (isCashier) return '/pos/cart';
    return '/store/products';
  }
}
