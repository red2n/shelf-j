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
 *   <li>Most paths under {@code /admin/} and POST to {@code .../refunds} or {@code .../void}
 *       require a management role ({@code PLATFORM_ADMIN} / {@code OWNER} / {@code MANAGER}).
 *   <li><b>Staff-operable admin surfaces</b> — day-to-day warehouse and till work — only require
 *       any staff role ({@code STOREKEEPER}/{@code CASHIER} included):
 *       <ul>
 *         <li>{@code /admin/inventory/**} (receive, adjust, levels, batches, planning, …)
 *         <li>{@code /admin/cash/**} (till open/close, drops, pay-in/out — resource layer still
 *             enforces finer rules, e.g. Z-report stays MANAGER+)
 *         <li>Read support for those UIs: {@code GET /admin/tenant}, {@code GET /admin/stores…},
 *             {@code GET /admin/products/variants/resolve}
 *       </ul>
 *       Without this tier, STOREKEEPER could not receive stock and CASHIER could not open a till,
 *       even though the resource classes intentionally allow those roles.
 *   <li>Every other mutating request requires any staff role — i.e. not a plain {@code CUSTOMER}.
 *   <li>Open mutations (no staff role yet, or no role headers at all): the iam identity endpoints,
 *       tenant bootstrap ({@code POST /onboarding/tenants}, {@code POST /admin/tenant} — the caller
 *       only becomes OWNER via the TenantCreated event), and {@code POST /prices/resolve}/{@code
 *       POST /prices/resolve-batch} (read-only price lookups order-svc performs service-to-service
 *       without identity headers).
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
          "/auth/platform-login",
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
    // Staff-operable admin GETs + mutations (inventory, till, support reads) — not open to
    // customers, but open to STOREKEEPER/CASHIER. Must run for GETs too: non-admin GETs are
    // otherwise unauthenticated by this filter.
    if (requiresStaffAdmin(path, method)) {
      if (!hasAny(STAFF_ROLES)) {
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
        // One-shot platform bootstrap: creates the very first PLATFORM_ADMIN before any JWT exists.
        || "/bootstrap/admin".equals(path)
        // Bootstrap carve-out: tenant creation AND first-store creation are performed by a freshly
        // registered user who has no staff role yet (OWNER is granted asynchronously by the
        // TenantCreated Kafka event — the store step must not block on that race).
        || "/onboarding".equals(path)
        || "/onboarding/tenants".equals(path)
        || "/onboarding/stores".equals(path)
        || "/admin/tenant".equals(path)
        // Internal read-only lookup: order-svc resolves prices service-to-service without
        // identity headers (it POSTs a query payload, but mutates nothing). The batch form is the
        // same lookup for every order line in one call instead of one call per line.
        || "/prices/resolve".equals(path)
        || "/prices/resolve-batch".equals(path)
        // Guest storefront checkout: an online shopper places an order with no staff role.
        // Reachable only via the gateway's storefront whitelist (tenant from X-Storefront-Tenant)
        // or by an authenticated customer. POS channel orders require a staff role — enforced
        // inside OrderResource.place() after payload deserialisation.
        || "/orders".equals(path)
        // Shopping cart self-service: a guest (sessionId) or authenticated CUSTOMER manages their
        // own cart with no staff role. Object-level authorization (only the owning
        // customer/session,
        // or staff acting on a customer's behalf) is enforced inside CartService, not here.
        || "/cart".equals(path)
        || path.startsWith("/cart/")
        // Guest storefront online payment (cashless). The staff cash-tender path is POST /payments,
        // which stays role-gated; this is the customer-facing online capture only.
        || "/payments/online".equals(path)
        // Internal checkout stock hold: order-svc calls inventory-svc service-to-service (only
        // X-Tenant-Id, no staff role) to hold stock when ANY caller places an ONLINE order —
        // mirrors /prices/resolve. A customer with no staff role can already tie up stock for the
        // hold TTL via POST /orders itself (an existing open mutation), so this carve-out grants no
        // capability beyond what placing an order already permits.
        || "/inventory/reservations".equals(path)
        || (path.startsWith("/inventory/reservations/") && path.endsWith("/release"));
  }

  private static boolean requiresManagement(String path, String method) {
    // Bootstrap carve-out — see isOpenMutation.
    if (path.endsWith("/admin/tenant") && "POST".equalsIgnoreCase(method)) return false;
    // Receipt printing is a cashier action (logging a print event after completing a sale);
    // it must not be locked behind management roles even though the path is under /admin/.
    if (path.endsWith("/receipts") && "POST".equalsIgnoreCase(method)) return false;
    // Day-to-day warehouse/till surfaces — gated by requiresStaffAdmin instead.
    if (requiresStaffAdmin(path, method)) return false;
    if (path.startsWith("/admin/")) return true;
    if (path.endsWith("/refunds") && "POST".equalsIgnoreCase(method)) return true;
    if (path.endsWith("/void") && "POST".equalsIgnoreCase(method)) return true;
    return false;
  }

  /**
   * Admin paths that STOREKEEPER / CASHIER (any staff) may call. Resource methods may still impose
   * a stricter role (e.g. till close stays MANAGER+).
   */
  static boolean requiresStaffAdmin(String path, String method) {
    // Warehouse ops — the storekeeper's primary job.
    if (pathEqualsOrUnder(path, "/admin/inventory")) return true;
    // Till / cash drawer — cashiers open a session; close/drops stay stricter at resource level.
    if (pathEqualsOrUnder(path, "/admin/cash")) return true;
    // Read-only support data the inventory UI needs (tenant name, store/zone pickers, SKU labels).
    // Mutations on stores/tenant stay management-only via requiresManagement.
    if ("GET".equalsIgnoreCase(method)) {
      if ("/admin/tenant".equals(path)) return true;
      if (pathEqualsOrUnder(path, "/admin/stores")) return true;
      if ("/admin/products/variants/resolve".equals(path)) return true;
    }
    return false;
  }

  private static boolean pathEqualsOrUnder(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  private static Response forbidden() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(ErrorBody.of("FORBIDDEN", "Insufficient role for this operation")))
        .build();
  }
}
