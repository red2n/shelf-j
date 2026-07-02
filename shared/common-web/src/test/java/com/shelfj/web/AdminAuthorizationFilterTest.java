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
