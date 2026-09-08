package com.shelfj.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminAuthorizationFilterTest {

  private final AdminAuthorizationFilter filter = new AdminAuthorizationFilter();
  private final TenantContext ctx = new TenantContext();

  AdminAuthorizationFilterTest() {
    filter.ctx = ctx;
  }

  @Test
  void exactCartPathIsOpenForGuests() throws Exception {
    // No staff role at all — a guest/customer managing their own cart.
    assertNotAborted(invoke("POST", "/cart/items"));
  }

  @Test
  void cartSubPathIsOpenForGuests() throws Exception {
    assertNotAborted(invoke("DELETE", "/cart/items/123"));
  }

  @Test
  void lookalikeCartPathRequiresStaffRole() throws Exception {
    // Regression guard: a prefix match on "/cart" would have let an unrelated future
    // "/cart-something" route silently inherit the open-mutation carve-out meant only for the
    // shopping cart. It must fall back to the default-deny staff-role requirement instead.
    assertAborted(invoke("POST", "/cart-something"), 403);
  }

  @Test
  void lookalikeCartPathPassesWithStaffRole() throws Exception {
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("POST", "/cart-something"));
  }

  // ── SJ-D11: reads default-deny, like mutations always have ──────────────────

  /**
   * The gap that prompted the read tier, sampled across all eight services that had one. The filter
   * now gates 38 non-{@code /admin/} GETs; they were not equally exposed beforehand, and the
   * difference is worth keeping straight:
   *
   * <ul>
   *   <li><b>24 were open to any authenticated caller in the tenant</b>, a signed-in storefront
   *       CUSTOMER included — every path in this test except the two noted below. The service layer
   *       took {@code TenantContext} only to read {@code tenantId} off it, so tenant isolation held
   *       and nothing else did.
   *   <li><b>8 already had object-level authorization</b> ({@code /customers/{id}} and its
   *       sub-resources, {@code /payments/{id}}, {@code /payments/by-order/…}). A signed-in
   *       customer reading someone else's record already got a 404 — but a caller with no principal
   *       at all fell through their "no userId, no roles means a trusted service-to-service lookup"
   *       branch and was served. That branch is now unreachable from outside the mesh, which is why
   *       {@code CustomerClient} and {@code OrderClient} stamp a role on their internal reads.
   *   <li><b>6 already required a role of their own</b> — the two pricing reads SJ-D10 closed,
   *       {@code /pos/parked-sales}, and {@code /platform/tenants}. For those this tier is only
   *       belt-and-braces.
   * </ul>
   */
  @Test
  void businessReadsAreDeniedWithoutAStaffRole() throws Exception {
    for (String path :
        new String[] {
          // Open: the customer list and the email/phone lookup behind it.
          "/customers",
          "/customers/lookup",
          // Object-level guarded, but served to a caller with no principal at all.
          "/customers/abc/loyalty",
          "/payments/abc",
          // Open: the tenant-wide order book, and the till's gift card and layaway balances.
          "/orders",
          "/gift-cards/GC-1234",
          "/layaways/abc",
          // Open: who is signed in at which till.
          "/auth/pos/sessions",
          // Open: stock held for other people's in-flight checkouts.
          "/inventory/reservations",
          // Open: the tenant's own commercial position — what it charges and what it pays.
          "/price-lists",
          "/vat-rates",
          "/suppliers",
          "/purchase-orders",
          "/goods-receipts",
          "/nominal-ledger",
          // Role-guarded already; this tier is belt-and-braces.
          "/vat-return"
        }) {
      assertAborted(invoke("GET", path), 403);
    }
  }

  @Test
  void theSameReadsPassForStaff() throws Exception {
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("GET", "/customers"));
    assertNotAborted(invoke("GET", "/orders"));
    assertNotAborted(invoke("GET", "/suppliers"));
  }

  /** A CUSTOMER is not staff — that is the whole point, since storefront tokens carry it. */
  @Test
  void aCustomerRoleIsNotStaff() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/customers"), 403);
    assertAborted(invoke("GET", "/orders"), 403);
  }

  /** Everything the storefront actually calls must still work with no role whatsoever. */
  @Test
  void theStorefrontReadSurfaceStaysOpen() throws Exception {
    for (String path :
        new String[] {
          "/catalog/categories",
          "/catalog/products",
          "/catalog/products/abc",
          "/catalog/products/abc/variants",
          "/catalog/products/abc/image",
          "/storefront/config",
          "/storefront/stores",
          "/storefront/active",
          "/inventory/availability",
          "/orders/mine",
          "/orders/abc",
          "/orders/abc/history",
          "/orders/abc/returns",
          "/promotions",
          "/auth/me",
          "/cart",
          "/cart/items",
          "/onboarding/status",
          "/fulfilment/resolve"
        }) {
      assertNotAborted(invoke("GET", path));
    }
  }

  /**
   * The same lookalike trap the cart carve-out has: a bare prefix match would hand an unrelated
   * future route the storefront's open-read exemption.
   */
  @Test
  void lookalikePublicPathsDoNotInheritTheExemption() throws Exception {
    assertAborted(invoke("GET", "/catalog-exports"), 403);
    assertAborted(invoke("GET", "/storefront-admin"), 403);
    // Anything new under /orders/ that is not one of the four object-level-authorized shapes
    // stays denied, so a future sub-resource cannot inherit the exemption by accident.
    assertAborted(invoke("GET", "/orders/abc/audit-trail"), 403);
    assertAborted(invoke("GET", "/orders/abc/history/all"), 403);
  }

  /**
   * A readiness probe that starts returning 403 takes every replica out of rotation. Helidon
   * usually serves these outside JAX-RS, but the filter must not be the thing that finds out.
   */
  @Test
  void probesAndMetricsAreNeverDenied() throws Exception {
    assertNotAborted(invoke("GET", "/health"));
    assertNotAborted(invoke("GET", "/health/ready"));
    assertNotAborted(invoke("GET", "/metrics"));
    assertNotAborted(invoke("GET", "/openapi"));
  }

  /** CORS preflight carries no credentials by design; denying it breaks every browser client. */
  @Test
  void corsPreflightIsNotDenied() throws Exception {
    assertNotAborted(invoke("OPTIONS", "/customers"));
  }

  // ── Payment intents (PSP integration) ──────────────────────────────────────

  /**
   * A shopper opens an intent at the same point in checkout that POST /payments/online already
   * serves, and polls it after being sent away for SCA. Both are reachable with no staff role;
   * payment-svc verifies the order against order-svc rather than trusting the caller.
   */
  @Test
  void aShopperCanOpenAndPollTheirOwnPaymentIntent() throws Exception {
    assertNotAborted(invoke("POST", "/payments/intents"));
    assertNotAborted(invoke("GET", "/payments/intents/abc"));
  }

  /**
   * The provider has to reach this with no JWT and no tenant. It is authenticated by the signature
   * over the raw body instead — see PaymentProvider.verifyWebhook.
   */
  @Test
  void providerWebhooksAreReachableWithoutAnyRole() throws Exception {
    assertNotAborted(invoke("POST", "/payments/webhooks/stripe"));
    assertNotAborted(invoke("POST", "/payments/webhooks/razorpay"));
  }

  /** Taking the money is the business's act, not the shopper's. */
  @Test
  void capturingAnIntentRequiresStaff() throws Exception {
    assertAborted(invoke("POST", "/payments/intents/abc/capture"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("POST", "/payments/intents/abc/capture"), 403);
  }

  /** The same lookalike trap as /catalog and /orders: a prefix match would hand these away. */
  @Test
  void paymentLookalikePathsDoNotInheritTheExemption() throws Exception {
    // Not the exempted shape: only GET /payments/intents/{id} is open.
    assertAborted(invoke("GET", "/payments/intents"), 403);
    assertAborted(invoke("GET", "/payments/intents/abc/audit"), 403);
    // A route merely starting with the same characters is a different route.
    assertAborted(invoke("POST", "/payments/intents-bulk"), 403);
    assertAborted(invoke("POST", "/payments/webhooks-replay"), 403);
  }

  // ── Staff-operable admin surfaces (STOREKEEPER inventory + CASHIER till) ──

  @Test
  void storekeeperCanReceiveStockUnderAdminInventory() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("POST", "/admin/inventory/receive"));
  }

  @Test
  void storekeeperCanReadInventoryLevels() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/levels"));
  }

  @Test
  void customerCannotReadAdminInventory() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/inventory/levels"), 403);
  }

  @Test
  void unauthenticatedCannotReadAdminInventory() throws Exception {
    assertAborted(invoke("GET", "/admin/inventory/levels"), 403);
  }

  // ── SJ-D19: the reports subtree is carved out of the staff tier ───────────

  /**
   * The whole point of the carve-out. Every report under {@code /admin/inventory/reports} answered
   * a CASHIER with 200 on the running stack, because it sits inside the warehouse subtree that
   * STOREKEEPER and CASHIER legitimately share. Stock valuation, cost of goods sold and a shrinkage
   * report naming which colleague wrote off what are not warehouse work.
   */
  @Test
  void staffCannotReadInventoryReports() throws Exception {
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      ctx.set(null, null, Set.of(role), null, null);
      for (String report :
          new String[] {"valuation", "shrinkage", "low-stock", "stock-turn", "dead-stock"}) {
        assertAborted(invoke("GET", "/admin/inventory/reports/" + report), 403);
      }
    }
  }

  @Test
  void managementCanReadInventoryReports() throws Exception {
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports/valuation"));
    ctx.set(null, null, Set.of("OWNER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports/stock-turn"));
  }

  /**
   * The lookalike trap, pinned the way SJ-D11 pinned {@code /catalog-exports}: a path that merely
   * starts with the same characters is a different path, and must keep the staff access the
   * warehouse subtree grants it.
   */
  @Test
  void aPathMerelyStartingWithReportsKeepsItsStaffAccess() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports-config"));
    assertNotAborted(invoke("POST", "/admin/inventory/reportable-items"));
  }

  @Test
  void cashierCanOpenTillSession() throws Exception {
    // Resource layer allows CASHIER on open; the filter must not management-block first.
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("POST", "/admin/cash/till-sessions"));
  }

  @Test
  void cashierCanReadTillSession() throws Exception {
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(
        invoke("GET", "/admin/cash/till-sessions/00000000-0000-0000-0000-000000000001"));
  }

  @Test
  void storekeeperCanReadStoresForInventoryUi() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/stores"));
    assertNotAborted(invoke("GET", "/admin/stores/abc/zones"));
    assertNotAborted(invoke("GET", "/admin/tenant"));
    assertNotAborted(invoke("GET", "/admin/products/variants/resolve"));
  }

  @Test
  void storekeeperCannotMutateStores() throws Exception {
    // Creating/updating stores stays management-only.
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertAborted(invoke("POST", "/admin/stores"), 403);
    assertAborted(invoke("PUT", "/admin/stores/abc"), 403);
  }

  @Test
  void storekeeperCannotAccessManagementAdminPaths() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertAborted(invoke("GET", "/admin/staff"), 403);
    assertAborted(invoke("GET", "/admin/reports/sales/summary"), 403);
    assertAborted(invoke("POST", "/admin/products"), 403);
  }

  @Test
  void managerStillHasFullAdminAccess() throws Exception {
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/staff"));
    assertNotAborted(invoke("POST", "/admin/inventory/receive"));
    assertNotAborted(invoke("POST", "/admin/cash/till-sessions"));
  }

  private Response.StatusType invoke(String method, String path) throws Exception {
    AbortCapture capture = new AbortCapture();
    var req = requestContext(method, path, capture);
    filter.filter(req);
    return capture.status;
  }

  private static void assertAborted(Response.StatusType status, int expectedCode) {
    assertTrue(status != null, "expected the filter to abort the request");
    assertEquals(expectedCode, status.getStatusCode());
  }

  private static void assertNotAborted(Response.StatusType status) {
    assertFalse(status != null, "expected the filter to let the request through");
  }

  private static final class AbortCapture {
    Response.StatusType status;
  }

  /**
   * Hand-rolled {@code ContainerRequestContext}/{@code UriInfo} stub: {@link
   * AdminAuthorizationFilter#filter} only calls {@code getUriInfo().getPath()}, {@code
   * getMethod()}, and {@code abortWith(Response)}, so every other method is unimplemented — pulling
   * in a mocking framework for three methods isn't worth the dependency.
   */
  private static jakarta.ws.rs.container.ContainerRequestContext requestContext(
      String method, String path, AbortCapture capture) {
    jakarta.ws.rs.core.UriInfo uriInfo =
        (jakarta.ws.rs.core.UriInfo)
            Proxy.newProxyInstance(
                AdminAuthorizationFilterTest.class.getClassLoader(),
                new Class<?>[] {jakarta.ws.rs.core.UriInfo.class},
                (proxy, m, args) -> {
                  // Real JAX-RS UriInfo.getPath() returns the path with NO leading slash — the
                  // filter itself prepends one (see its "normalize" comment), so the stub must
                  // match that contract or every path gets double-slashed and fails to match.
                  if ("getPath".equals(m.getName()) && m.getParameterCount() == 0) {
                    return path.startsWith("/") ? path.substring(1) : path;
                  }
                  if ("getRequestUri".equals(m.getName())) return URI.create("http://x" + path);
                  throw new UnsupportedOperationException(m.getName());
                });
    return (jakarta.ws.rs.container.ContainerRequestContext)
        Proxy.newProxyInstance(
            AdminAuthorizationFilterTest.class.getClassLoader(),
            new Class<?>[] {jakarta.ws.rs.container.ContainerRequestContext.class},
            (proxy, m, args) -> {
              switch (m.getName()) {
                case "getUriInfo":
                  return uriInfo;
                case "getMethod":
                  return method;
                case "abortWith":
                  capture.status = ((Response) args[0]).getStatusInfo();
                  return null;
                default:
                  throw new UnsupportedOperationException(m.getName());
              }
            });
  }
}
