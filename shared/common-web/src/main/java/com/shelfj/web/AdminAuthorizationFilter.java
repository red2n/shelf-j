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

/**
 * Enforces role-based access control on paths that require elevated privileges.
 *
 * <p>Rules (evaluated in order):
 *
 * <ol>
 *   <li>Any path containing {@code /admin/} requires {@code ADMIN} or {@code STAFF} role.
 *   <li>Any POST to a path ending with {@code /refunds} requires {@code ADMIN} or {@code STAFF}.
 *   <li>Any POST/DELETE to a path ending with {@code /void} requires {@code ADMIN} or {@code
 *       STAFF}.
 * </ol>
 *
 * <p>Runs at {@link Priorities#AUTHORIZATION} (2000), after {@link TenantContextFilter} (1000) has
 * populated {@link TenantContext} from the gateway-verified identity headers.
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class AdminAuthorizationFilter implements ContainerRequestFilter {

  @Inject TenantContext ctx;

  @Override
  public void filter(ContainerRequestContext req) throws IOException {
    String path = req.getUriInfo().getPath();
    String method = req.getMethod();

    if (requiresStaff(path, method) && !ctx.hasRole("ADMIN") && !ctx.hasRole("STAFF")) {
      req.abortWith(forbidden());
    }
  }

  private static boolean requiresStaff(String path, String method) {
    if (path.contains("/admin/")) return true;
    if (path.endsWith("/refunds") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/void") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/confirm") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/fulfil") && "POST".equalsIgnoreCase(method)) return true;
    return false;
  }

  private static Response forbidden() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            "{\"error\":{\"code\":\"FORBIDDEN\","
                + "\"message\":\"Insufficient role for this operation\"}}")
        .build();
  }
}
