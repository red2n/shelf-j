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
