package com.shelfj.web;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Set;

/**
 * Enforces role-based access control. DEFAULT-DENY for writes: every mutating request
 * (POST/PUT/PATCH/DELETE) on a business service requires a staff role unless the path is on the
 * explicit bootstrap/identity allowlist below — so a newly added endpoint ships protected instead
 * of silently open to any authenticated customer.
 *
 * <p>Role model (seeded by iam-svc V1): {@code PLATFORM_ADMIN}, {@code OWNER}, {@code MANAGER},
 * {@code STOREKEEPER}, {@code CASHIER}, {@code CUSTOMER}.
 *
 * <ul>
 *   <li>Paths containing {@code /admin/} and POST to {@code .../refunds} or {@code .../void}
 *       require a management role ({@code PLATFORM_ADMIN} / {@code OWNER} / {@code MANAGER}).
 *   <li>Every other mutating request requires any staff role (management plus {@code STOREKEEPER} /
 *       {@code CASHIER}) — i.e. not a plain {@code CUSTOMER}.
 *   <li>Open mutations (no staff role yet, or no role headers at all): the iam identity endpoints,
 *       tenant bootstrap ({@code POST /onboarding/tenants}, {@code POST /admin/tenant} — the caller
 *       only becomes OWNER via the TenantCreated event), and {@code POST /prices/resolve}
 *       (read-only price lookup order-svc performs service-to-service without identity headers).
 * </ul>
 *
 * <p>Runs at {@link Priorities#AUTHORIZATION} (2000), after {@link TenantContextFilter} (1000) has
 * populated {@link TenantContext} from the gateway-verified identity headers.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AdminAuthorizationFilter implements ContainerRequestFilter {

  private static final Set<String> MANAGEMENT_ROLES = Set.of("PLATFORM_ADMIN", "OWNER", "MANAGER");
  private static final Set<String> STAFF_ROLES =
      Set.of("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");

  /** Identity endpoints — they mint or manage credentials, reachable before any role exists. */
  private static final Set<String> IDENTITY_PATHS =
      Set.of(
          "/auth/register",
          "/auth/login",
          "/auth/refresh",
          "/auth/logout",
          "/auth/change-password");

  @Inject TenantContext ctx;

  @Override
  public void filter(ContainerRequestContext req) throws IOException {
    // UriInfo.getPath() has no leading slash; normalize so "/admin/" matches top-level paths.
    String path = stripGatewayPrefix("/" + req.getUriInfo().getPath());
    String method = req.getMethod();

    if (requiresManagement(path, method)) {
      if (!hasAny(MANAGEMENT_ROLES)) {
        req.abortWith(forbidden());
      }
      return;
    }
    if (isMutating(method) && !isOpenMutation(path) && !hasAny(STAFF_ROLES)) {
      req.abortWith(forbidden());
    }
  }

  private boolean hasAny(Set<String> allowed) {
    for (String r : ctx.roles()) {
      if (allowed.contains(r)) return true;
    }
    return false;
  }

  /**
   * This filter also runs inside the gateway (it bundles common-web), where the request path is the
   * proxy route {@code /api/{service}/{service-local path}}. Strip that prefix so the allowlists
   * match the same service-local path at the gateway and at the business service.
   */
  private static String stripGatewayPrefix(String path) {
    if (!path.startsWith("/api/")) {
      return path;
    }
    int afterService = path.indexOf('/', "/api/".length());
    return afterService >= 0 ? path.substring(afterService) : "/";
  }

  private static boolean isMutating(String method) {
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method)
        || "DELETE".equalsIgnoreCase(method);
  }

  private static boolean isOpenMutation(String path) {
    return IDENTITY_PATHS.contains(path)
        // Bootstrap carve-out: tenant creation is performed by a freshly registered user who has
        // no tenant or staff role yet (the OWNER role is granted by the TenantCreated event).
        || "/onboarding/tenants".equals(path)
        || "/admin/tenant".equals(path)
        // Internal read-only lookup: order-svc resolves prices service-to-service without
        // identity headers (it POSTs a query payload, but mutates nothing).
        || "/prices/resolve".equals(path);
  }

  private static boolean requiresManagement(String path, String method) {
    // Bootstrap carve-out — see isOpenMutation.
    if (path.endsWith("/admin/tenant") && "POST".equalsIgnoreCase(method)) return false;
    if (path.contains("/admin/")) return true;
    if (path.endsWith("/refunds") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/void") && "POST".equalsIgnoreCase(method)) return true;
    return false;
  }

  private static Response forbidden() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(ErrorBody.of("FORBIDDEN", "Insufficient role for this operation")))
        .build();
  }
}
