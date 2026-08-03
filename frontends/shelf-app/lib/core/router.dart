import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'auth/auth_notifier.dart';
import 'auth/auth_state.dart';
import '../features/auth/login_screen.dart';
import '../features/platform/platform_login_screen.dart';
import '../features/onboarding/onboarding_wizard.dart';
import '../features/admin/admin_shell.dart';
import '../features/admin/admin_screens.dart' deferred as admin_lib;
import '../features/platform/platform_shell.dart';
import '../features/platform/platform_screens.dart' deferred as platform_lib;
import '../features/pos/pos_shell.dart';
import '../features/pos/pos_screens.dart' deferred as pos_lib;
import '../features/storefront/storefront_shell.dart';
import '../shared/widgets/deferred_widget.dart';
import '../features/storefront/storefront_screens.dart' deferred as storefront_lib;

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    initialLocation: '/login',
    // _AuthListenable tells go_router to re-run redirect whenever auth changes
    refreshListenable: _AuthListenable(ref),
    redirect: (context, state) {
      final authAsync = ref.read(authNotifierProvider);
      if (authAsync.isLoading) return null;

      final auth = authAsync.value ?? const AuthUnauthenticated();
      final loc = state.matchedLocation;

      // Public storefront — accessible to EVERYONE (guests and any signed-in
      // user), so a storefront deep-link is never hijacked by the login /
      // onboarding / platform-admin redirects below. The storefront has its own
      // (separate) customer session and tenant-from-URL context.
      if (loc.startsWith('/store')) return null;

      if (auth is AuthUnauthenticated) {
        if (loc == '/login' || loc == '/platform/login') return null;
        // /platform/* (other than the login page) has no unauthenticated access —
        // bounce to the platform login, not the store/POS one.
        return loc.startsWith('/platform') ? '/platform/login' : '/login';
      }

      if (auth is AuthAuthenticated) {
        // send logged-in users away from login/root
        if (loc == '/login' || loc == '/platform/login' || loc == '/') {
          return auth.homeRoute;
        }
        // force incomplete-onboarding users to the wizard
        if (auth.needsOnboarding && loc != '/onboarding') return '/onboarding';
        // once onboarded, keep them out of the wizard
        if (!auth.needsOnboarding && loc == '/onboarding') return auth.homeRoute;
        // PLATFORM_ADMIN: only allowed in /platform/*
        if (auth.isPlatformAdmin && !loc.startsWith('/platform')) {
          return auth.homeRoute;
        }
        // Tenant admins/cashiers: blocked from the platform area
        if (!auth.isPlatformAdmin && loc.startsWith('/platform')) {
          return auth.homeRoute;
        }
        // Admin shell: OWNER/MANAGER (full) or STOREKEEPER (inventory-focused).
        // Storekeeper deep-links into management-only screens are bounced home
        // by AdminShell's restricted destinations list + API 403s as belt-and-
        // suspenders; the gate here only checks shell entry.
        if (loc.startsWith('/admin') && !auth.canAccessAdmin) {
          return auth.homeRoute;
        }
        // Storekeeper-only: keep them on inventory (and store list for zone
        // context). Other /admin/* routes redirect home so the nav can't be
        // bypassed via URL.
        if (loc.startsWith('/admin') &&
            auth.isStorekeeper &&
            !auth.isManager &&
            !_storekeeperAdminAllowed(loc)) {
          return auth.homeRoute;
        }
        // POS: cashiers and managers. Storekeepers stay on admin inventory.
        if (loc.startsWith('/pos') && !auth.isCashier && !auth.isManager) {
          return auth.homeRoute;
        }
      }
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (_, _) => const LoginScreen()),
      GoRoute(path: '/platform/login', builder: (_, _) => const PlatformLoginScreen()),
      GoRoute(path: '/onboarding', builder: (_, _) => const OnboardingWizard()),

      // ── Platform admin shell (PLATFORM_ADMIN only) ─────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            PlatformShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/platform', redirect: (_, _) => '/platform/overview'),
          GoRoute(
            path: '/platform/overview',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.PlatformDashboardScreen(),
            ),
          ),
          GoRoute(
            path: '/platform/tenants',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.TenantsScreen(),
            ),
          ),
        ],
      ),

      // ── Admin shell ────────────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            AdminShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/admin', redirect: (_, _) => '/admin/dashboard'),
          GoRoute(
            path: '/admin/dashboard',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.DashboardScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/catalog',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.CatalogScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/inventory',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.InventoryScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/stores',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.StoresScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/orders',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.AdminOrdersScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/procurement',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.ProcurementScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/pricing',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.PricingScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/reports',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.ReportsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/customers',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.CustomersScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/sales',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.SalesScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/staff',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.StaffScreen(),
            ),
          ),
        ],
      ),

      // ── POS shell ──────────────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            PosShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/pos', redirect: (_, _) => '/pos/cart'),
          GoRoute(
            path: '/pos/cart',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.PosCartScreen(),
            ),
          ),
          GoRoute(
            path: '/pos/tender',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.TenderScreen(),
            ),
          ),
          GoRoute(
            path: '/pos/cash',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.CashScreen(),
            ),
          ),
        ],
      ),

      // ── Storefront shell ───────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            StorefrontShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/store', redirect: (_, _) => '/store/products'),
          GoRoute(
            path: '/store/products',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.ProductListScreen(),
            ),
          ),
          GoRoute(
            path: '/store/products/:id',
            builder: (_, state) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.ProductDetailScreen(
                productId: state.pathParameters['id']!,
              ),
            ),
          ),
          GoRoute(
            path: '/store/cart',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontCartScreen(),
            ),
          ),
          GoRoute(
            path: '/store/orders',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontOrdersScreen(),
            ),
          ),
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

/// Paths a storekeeper-only user may open inside the admin shell.
bool _storekeeperAdminAllowed(String loc) {
  return loc.startsWith('/admin/inventory') || loc.startsWith('/admin/stores');
}

class _AuthListenable extends ChangeNotifier {
  _AuthListenable(Ref ref) {
    ref.listen<AsyncValue<AuthState>>(
      authNotifierProvider,
      (_, _) => notifyListeners(),
    );
  }
}
