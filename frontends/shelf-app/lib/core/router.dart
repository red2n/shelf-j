import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'auth/auth_notifier.dart';
import 'auth/auth_state.dart';
import '../features/auth/login_screen.dart';
import '../features/onboarding/onboarding_wizard.dart';
import '../features/admin/admin_shell.dart';
import '../features/admin/dashboard_screen.dart';
import '../features/admin/inventory_screen.dart';
import '../features/admin/orders_screen.dart';
import '../features/admin/reports_screen.dart';
import '../features/admin/staff_screen.dart';
import '../features/pos/pos_shell.dart';
import '../features/pos/cart_screen.dart';
import '../features/pos/tender_screen.dart';
import '../features/storefront/storefront_shell.dart';
import '../features/storefront/product_list_screen.dart';
import '../features/storefront/product_detail_screen.dart';
import '../features/storefront/cart_screen.dart';
import '../features/storefront/orders_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    initialLocation: '/login',
    // _AuthListenable tells go_router to re-run redirect whenever auth changes
    refreshListenable: _AuthListenable(ref),
    redirect: (context, state) {
      final authAsync = ref.read(authNotifierProvider);
      if (authAsync.isLoading) return null;

      final auth = authAsync.valueOrNull ?? const AuthUnauthenticated();
      final loc = state.matchedLocation;

      if (auth is AuthUnauthenticated) {
        return loc == '/login' ? null : '/login';
      }

      if (auth is AuthAuthenticated) {
        // send logged-in users away from login/root
        if (loc == '/login' || loc == '/') return auth.homeRoute;
        // force incomplete-onboarding users to the wizard
        if (auth.needsOnboarding && loc != '/onboarding') return '/onboarding';
        // once onboarded, keep them out of the wizard
        if (!auth.needsOnboarding && loc == '/onboarding') return auth.homeRoute;
        // block cashier-only users from the admin area
        if (loc.startsWith('/admin') && !auth.isAdmin) return auth.homeRoute;
        // block pure customers from pos and admin
        if (loc.startsWith('/pos') && !auth.isCashier && !auth.isAdmin) {
          return auth.homeRoute;
        }
      }
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
      GoRoute(path: '/onboarding', builder: (_, __) => const OnboardingWizard()),

      // ── Admin shell ────────────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            AdminShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/admin', redirect: (_, __) => '/admin/dashboard'),
          GoRoute(path: '/admin/dashboard', builder: (_, __) => const DashboardScreen()),
          GoRoute(path: '/admin/inventory', builder: (_, __) => const InventoryScreen()),
          GoRoute(path: '/admin/orders', builder: (_, __) => const AdminOrdersScreen()),
          GoRoute(path: '/admin/reports', builder: (_, __) => const ReportsScreen()),
          GoRoute(path: '/admin/staff', builder: (_, __) => const StaffScreen()),
        ],
      ),

      // ── POS shell ──────────────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            PosShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/pos', redirect: (_, __) => '/pos/cart'),
          GoRoute(path: '/pos/cart', builder: (_, __) => const PosCartScreen()),
          GoRoute(path: '/pos/tender', builder: (_, __) => const TenderScreen()),
        ],
      ),

      // ── Storefront shell ───────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            StorefrontShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/store', redirect: (_, __) => '/store/products'),
          GoRoute(path: '/store/products', builder: (_, __) => const ProductListScreen()),
          GoRoute(
            path: '/store/products/:id',
            builder: (_, state) =>
                ProductDetailScreen(productId: state.pathParameters['id']!),
          ),
          GoRoute(path: '/store/cart', builder: (_, __) => const StorefrontCartScreen()),
          GoRoute(path: '/store/orders', builder: (_, __) => const StorefrontOrdersScreen()),
        ],
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      body: Center(child: Text('Page not found: ${state.uri}')),
    ),
  );

  ref.onDispose(router.dispose);
  return router;
});

class _AuthListenable extends ChangeNotifier {
  _AuthListenable(Ref ref) {
    ref.listen<AsyncValue<AuthState>>(
      authNotifierProvider,
      (_, __) => notifyListeners(),
    );
  }
}
