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
 *         <li>{@code /admin/inventory/**} (receive, adjust, levels, batches, planning, …), but
 *             <b>not</b> {@code /admin/inventory/reports/**}, which is management-only — see {@link
 *             #requiresStaffAdmin}
 *         <li>{@code /admin/cash/**} (till open/close, drops, pay-in/out — resource layer still
 *             enforces finer rules, e.g. Z-report stays MANAGER+)
 *         <li>Read support for those UIs: {@code GET /admin/tenant}, {@code GET /admin/stores…},
 *             {@code GET /admin/products/variants/resolve}
 *       </ul>
 *       Without this tier, STOREKEEPER could not receive stock and CASHIER could not open a till,
 *       even though the resource classes intentionally allow those roles.
 *   <li>Every other mutating request requires any staff role — i.e. not a plain {@code CUSTOMER}.
 *   <li><b>Every other read requires any staff role too</b>, unless it is on the open-read
 *       allowlist ({@link #isOpenRead}). Reads were previously left entirely to each resource to
 *       remember, and 24 of them forgot — serving a tenant's customer list, order book, supplier
 *       terms, price lists and nominal ledger to any caller holding a token for that tenant, a
 *       signed-in storefront shopper included (SJ-D11, generalising the two endpoints SJ-D10 closed
 *       by hand). Denying by default makes forgetting fail closed.
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
          "/auth/change-password",
          // The account holder deleting their own login — same object-level rule as changing its
          // password: the user id comes from the verified token, never from the request.
          "/auth/delete-account");

  @Inject TenantContext ctx;

  /**
   * Evaluates the request path/method against the management, staff-admin, and default-deny rules
   * described in the class doc, aborting with {@code 403} when the caller's roles (from {@link
   * TenantContext}, populated earlier by {@link TenantContextFilter}) don't satisfy them.
   *
   * @param req the incoming request; aborted in place via {@link ContainerRequestContext#abortWith}
   *     rather than by throwing, so this method never signals failure via its return
   * @throws IOException never thrown by this implementation; declared by {@link
   *     ContainerRequestFilter}
   */
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
    if (isMutating(method)) {
      if (!isOpenMutation(path) && !hasAny(STAFF_ROLES)) {
        req.abortWith(forbidden());
      }
      return;
    }
    // Reads, default-deny, mirroring mutations above. Only GET/HEAD: OPTIONS is CORS preflight and
    // carries no credentials by design, so denying it would break every browser client.
    if (("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
        && !isInfrastructure(path)
        && !isOpenRead(path)
        && !hasAny(STAFF_ROLES)) {
      req.abortWith(forbidden());
    }
  }

  /**
   * Probes, metrics and the API description. Served by Helidon rather than JAX-RS in most
   * configurations, so this filter usually never sees them — but a readiness probe that starts
   * returning 403 takes every replica out of rotation, which is too expensive a way to find out.
   *
   * @param path the service-local request path
   * @return {@code true} if this is an infrastructure endpoint, never business data
   */
  private static boolean isInfrastructure(String path) {
    return pathEqualsOrUnder(path, "/health")
        || pathEqualsOrUnder(path, "/metrics")
        || pathEqualsOrUnder(path, "/openapi");
  }

  /**
   * The order reads that carry their own object-level authorization: {@code /orders/mine} and the
   * id-addressed {@code /orders/{id}}, {@code /orders/{id}/history}, {@code /orders/{id}/returns}
   * and {@code /orders/{id}/fiscal-receipt}.
   *
   * <p>Matched by shape rather than by prefix, so anything else added under {@code /orders/} later
   * is denied until someone decides what it should be — the point of this whole change is that
   * forgetting fails closed.
   *
   * @param path the service-local request path
   * @return {@code true} for exactly those five shapes
   */
  private static boolean looksLikeUuid(String segment) {
    return segment.length() == 36
        && segment
            .chars()
            .allMatch(
                c ->
                    c == '-'
                        || (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F'));
  }

  private static boolean isOrderSelfRead(String path) {
    if (!path.startsWith("/orders/")) return false;
    String rest = path.substring("/orders/".length());
    if (rest.isEmpty()) return false;
    int slash = rest.indexOf('/');
    String first = slash < 0 ? rest : rest.substring(0, slash);
    // Only an id-shaped segment is a self-read. The order resource also has literal children —
    // /orders/mine, and /orders/export, which names a subject and is staff-only — and a literal
    // matched here would sail past the role check straight to a resource that expects the filter
    // to have done its job. A negative test found exactly that on /orders/export before it shipped.
    if (!looksLikeUuid(first)) return "mine".equals(first) && slash < 0;
    if (slash < 0) return true;
    String tail = rest.substring(slash + 1);
    // fiscal-receipt: the till prints the legal receipt number from here, and a customer may read
    // the number on their own order. Same object-level check as the order read it sits under.
    return "history".equals(tail) || "returns".equals(tail) || "fiscal-receipt".equals(tail);
  }

  /**
   * {@code GET /payments/intents/{id}} and nothing else under it.
   *
   * @param path the service-local request path
   * @return {@code true} for exactly that shape
   */
  private static boolean isPaymentIntentSelfRead(String path) {
    if (!path.startsWith("/payments/intents/")) return false;
    String rest = path.substring("/payments/intents/".length());
    return !rest.isEmpty() && rest.indexOf('/') < 0;
  }

  /**
   * Reads reachable without a staff role. The counterpart of {@link #isOpenMutation}, and curated
   * the same way: every entry is either something the public storefront genuinely needs, or a
   * service-to-service read that carries no identity headers.
   *
   * <p>An entry here means "any authenticated caller in the tenant may read this". Anything
   * carrying another customer's PII, a supplier's terms, or the tenant's own commercial position
   * must not be on this list — the eight services that had such data readable are exactly what
   * prompted the default-deny above.
   *
   * <p>Deliberately absent, and the asymmetry is the point: {@code /customers/{id}} and its
   * sub-resources carry object-level authorization every bit as strong as the {@code /orders/{id}}
   * carve-out below — {@code CustomerService.requireReadAccess} serves a customer their own record
   * and 404s them on anyone else's. They are still denied here, because no client asks for them:
   * the storefront has no account self-service screen, so an entry would widen the surface for
   * nobody. When that screen is built, add the shapes here rather than loosening the guard in
   * customer-svc, which is already correct.
   *
   * @param path the service-local request path
   * @return {@code true} if {@code path} may be read without a staff role
   */
  private static boolean isOpenRead(String path) {
    return
    // ── public storefront ────────────────────────────────────────────────
    // The shopper-facing catalogue: categories, product search, one product, its variants and
    // its image. Public by definition — this is the shop window.
    pathEqualsOrUnder(path, "/catalog")
        // Per-store storefront configuration and the list of stores a shopper may buy from,
        // plus the transact-or-not flow guard. Also read service-to-service by payment-svc.
        || pathEqualsOrUnder(path, "/storefront")
        // Stock display on a product page. Availability only — no cost, no batch, no location.
        || "/inventory/availability".equals(path)
        // /orders/mine and the id-addressed order reads. These are NOT unguarded: order-svc
        // applies object-level authorization to each — the owning customer gets their order, a
        // different customer in the same tenant gets 404 rather than 403, so the endpoint is not
        // an existence oracle either. That is strictly stronger than the role check this filter
        // would apply, and a blanket staff requirement here would stop a shopper reading their own
        // order. GET /orders, the tenant-wide list, has no such check and is deliberately excluded.
        || isOrderSelfRead(path)
        // A shopper polling their own payment intent after being sent away for SCA — without this
        // they cannot learn whether the payment they just completed succeeded. Not unguarded:
        // PaymentIntentService applies the same object-level check as the tender reads, resolving
        // ownership through order-svc, and answers 404 rather than 403 so intent ids cannot be
        // probed. Matched by shape, like the order reads, so a sub-resource added later stays
        // denied until someone decides what it should be.
        || isPaymentIntentSelfRead(path)
        // The shopper's own customer record, and the data export art.20 entitles them to. Both
        // resolve the caller's login from the verified token and can reach no other record; the
        // asymmetry with /customers/{id} above is deliberate — this shape cannot name a subject.
        || "/customers/me".equals(path)
        || "/customers/me/export".equals(path)
        || "/customers/me/marketing".equals(path)
        // Storefront promotions, the read side of what /prices/resolve already exposes.
        || "/promotions".equals(path)
        // The caller's own principal — it describes the caller, so it leaks nothing new.
        || "/auth/me".equals(path)
        // Own cart, mirroring the /cart mutation carve-out. Object-level authorization (owning
        // session or customer) lives in CartService, not here.
        || pathEqualsOrUnder(path, "/cart")
        // Onboarding progress, read by a freshly registered user who is not OWNER yet — the same
        // race the /onboarding mutation carve-outs exist for.
        || "/onboarding/status".equals(path)
        // ── service-to-service ───────────────────────────────────────────
        // order-svc asks tenant-svc which store serves a pincode. No identity headers, and the
        // answer (which shop delivers where) is storefront-public anyway.
        || "/fulfilment/resolve".equals(path);
  }

  /**
   * @param allowed roles that would satisfy the check
   * @return {@code true} if the current caller ({@link TenantContext#roles()}) has at least one
   *     role in {@code allowed}
   */
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

  /**
   * @param method the HTTP method of the request
   * @return {@code true} for POST/PUT/PATCH/DELETE (case-insensitive); {@code false} for GET/HEAD
   *     and anything else
   */
  private static boolean isMutating(String method) {
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method)
        || "DELETE".equalsIgnoreCase(method);
  }

  /**
   * @param path the service-local request path (after {@link #stripGatewayPrefix})
   * @return {@code true} if {@code path} is on the explicit allowlist of mutations reachable
   *     without any staff role (bootstrap/identity/guest-checkout flows — see the exhaustive
   *     rationale on each branch below)
   */
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
        // A signed-in shopper claiming the customer record this shop holds for them. Identity
        // comes from the verified token and nothing else — the caller cannot name another login,
        // another email or another tenant — so the only record reachable here is their own. It is
        // also how order-svc makes the link at checkout, forwarding the shopper's own identity.
        || "/customers/me".equals(path)
        // The shopper's own preference centre. Same shape and same reason as /customers/me: the
        // login comes from the token, so the only preferences reachable are the caller's.
        || "/customers/me/marketing".equals(path)
        // The opt-out link in a marketing message. Deliberately unauthenticated: the token is the
        // capability, and PECR reg.23 asks for a simple means of refusing — one that works from a
        // forwarded email, on a device that was never signed in, for a customer who has no
        // password at all. It can only ever withdraw permission, never grant it.
        || "/marketing/unsubscribe".equals(path)
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
        // Opening a payment intent: the same shopper, at the same point in checkout, as
        // /payments/online above. payment-svc verifies the order against order-svc before it
        // authorises anything — the caller's claim about amount and ownership is never trusted.
        // Capturing is NOT here: POST /payments/intents/{id}/capture stays staff-gated, because
        // taking the money is the business's act, not the shopper's.
        || "/payments/intents".equals(path)
        // Provider webhooks. Necessarily public — the provider has to reach it, and carries no JWT
        // and no tenant. It is authenticated instead by the provider's signature over the raw
        // body, verified in PaymentProvider.verifyWebhook before anything else happens. That check
        // is the entire security boundary of this path: without it, anyone could mark any order
        // paid by POSTing a plausible body.
        || path.startsWith("/payments/webhooks/")
        // Internal checkout stock hold: order-svc calls inventory-svc service-to-service (only
        // X-Tenant-Id, no staff role) to hold stock when ANY caller places an ONLINE order —
        // mirrors /prices/resolve. A customer with no staff role can already tie up stock for the
        // hold TTL via POST /orders itself (an existing open mutation), so this carve-out grants no
        // capability beyond what placing an order already permits.
        || "/inventory/reservations".equals(path)
        || (path.startsWith("/inventory/reservations/") && path.endsWith("/release"));
  }

  /**
   * @param path the service-local request path
   * @param method the HTTP method
   * @return {@code true} if this request requires a {@link #MANAGEMENT_ROLES} role
   */
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
    // Reporting is not warehouse work, and this exclusion has to come first because it carves a
    // hole out of the far broader rule below it.
    //
    // Found by driving the running stack (SJ-D19): every report under /admin/inventory/reports
    // answered a CASHIER with 200 — stock valuation, cost of goods sold, and a shrinkage report
    // naming which colleague wrote off what. Each one had inherited the staff tier from the
    // warehouse subtree it happens to sit in, and each resource's own javadoc claimed the
    // opposite: "under /admin/ so the filter gates them by path". That sentence was true of the
    // prefix and false of this one, which is precisely the SJ-D10 shape — an authorisation claim
    // that reads as correct and is never executed.
    //
    // The whole subtree goes to management rather than a per-report list. A storekeeper checking
    // low stock is a plausible future screen and it does not exist: the only caller of any of
    // these is the admin reports screen, which already carries reports a cashier gets 403 from.
    // An allowlist entry for nobody is surface for nobody (SJ-D11's reasoning for /customers/{id},
    // applied again). Whoever builds that screen adds the carve-out deliberately, here, rather
    // than discovering the gate never existed.
    if (pathEqualsOrUnder(path, "/admin/inventory/reports")) return false;
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

  /**
   * @param path the service-local request path
   * @param prefix the path prefix to test against, e.g. {@code "/admin/inventory"}
   * @return {@code true} if {@code path} equals {@code prefix} or is nested under it
   */
  private static boolean pathEqualsOrUnder(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /**
   * @return a {@code 403 FORBIDDEN} envelope response
   */
  private static Response forbidden() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            ApiResponse.error(ErrorBody.of("FORBIDDEN", "Insufficient role for this operation")))
        .build();
  }
}
