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
 * Enforces role-based access control on paths that require elevated privileges.
 *
 * <p>Role model (seeded by iam-svc V1): {@code PLATFORM_ADMIN}, {@code OWNER}, {@code MANAGER},
 * {@code STOREKEEPER}, {@code CASHIER}, {@code CUSTOMER}.
 *
 * <ul>
 *   <li>Paths containing {@code /admin/} and POST to {@code .../refunds} or {@code .../void}
 *       require a management role ({@code PLATFORM_ADMIN} / {@code OWNER} / {@code MANAGER}).
 *   <li>POST to {@code .../confirm} or {@code .../fulfil} requires any staff role (management plus
 *       {@code STOREKEEPER} / {@code CASHIER}) — i.e. not a plain {@code CUSTOMER}.
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

  @Inject TenantContext ctx;

  @Override
  public void filter(ContainerRequestContext req) throws IOException {
    // UriInfo.getPath() has no leading slash; normalize so "/admin/" matches top-level paths.
    String path = "/" + req.getUriInfo().getPath();
    String method = req.getMethod();

    if (requiresManagement(path, method) && !hasAny(MANAGEMENT_ROLES)) {
      req.abortWith(forbidden());
    } else if (requiresStaff(path, method) && !hasAny(STAFF_ROLES)) {
      req.abortWith(forbidden());
    }
  }

  private boolean hasAny(Set<String> allowed) {
    for (String r : ctx.roles()) {
      if (allowed.contains(r)) return true;
    }
    return false;
  }

  private static boolean requiresManagement(String path, String method) {
    // Bootstrap carve-out: tenant creation is performed by a freshly registered user who has
    // no tenant or staff role yet (the OWNER role is granted by the TenantCreated event).
    if (path.endsWith("/admin/tenant") && "POST".equalsIgnoreCase(method)) return false;
    if (path.contains("/admin/")) return true;
    if (path.endsWith("/refunds") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/void") && "POST".equalsIgnoreCase(method)) return true;
    return false;
  }

  private static boolean requiresStaff(String path, String method) {
    if (path.endsWith("/confirm") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/fulfil") && "POST".equalsIgnoreCase(method)) return true;
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
