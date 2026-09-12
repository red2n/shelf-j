package com.shelfj.gateway.filters;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shelfj.gateway.GatewayConfig;
import com.shelfj.web.HttpHeaders;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Security boundary: validates the inbound JWT, then stamps verified identity headers (X-Tenant-Id,
 * X-User-Id, X-Roles) onto the request before it reaches ProxyResource.
 *
 * <p>Any client-supplied copies of those headers are removed first, so downstream services can
 * trust that only the gateway sets them (golden rule #3).
 *
 * <p>Public paths (register / login / refresh) bypass JWT validation. All other /api/** paths
 * require a valid Bearer token.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION - 1)
public class JwtAuthFilter implements ContainerRequestFilter {

  /** Exact request paths (normalized, no leading/trailing slash) that do NOT require a token. */
  private static final Set<String> PUBLIC_PATHS =
      Set.of(
          "api/iam-svc/auth/register",
          "api/iam-svc/auth/login",
          "api/iam-svc/auth/platform-login",
          "api/iam-svc/auth/refresh",
          "api/iam-svc/bootstrap/admin",
          // The opt-out link in a marketing message (PECR reg.23). Necessarily public: the person
          // clicking it may be on a device that was never signed in, may have no password at all,
          // and must not be made to prove who they are in order to be left alone. The token in the
          // link is the whole capability, and it can only ever withdraw permission.
          "api/customer-svc/marketing/unsubscribe");

  /**
   * Public storefront access (guest shopping). These tenant-scoped paths expose only public data
   * (active, sellable-online products and their prices) plus guest checkout, so they may be reached
   * without a token. The tenant is taken from {@code X-Storefront-Tenant}, which in production the
   * gateway derives from the storefront's domain/subdomain; in dev the storefront sends it directly
   * to simulate that. (Any client-supplied {@code X-Tenant-Id} is still stripped above, so this is
   * the only way a guest can name a storefront, and only for these whitelisted paths.)
   */
  static final String STOREFRONT_TENANT_HEADER = "X-Storefront-Tenant";

  @Inject GatewayConfig config;
  @Inject TenantStatusGate tenantStatusGate;

  private JWTVerifier verifier;

  @PostConstruct
  void init() {
    String secret = config.jwtSecret();
    if (secret == null || secret.trim().length() < 32) {
      throw new IllegalStateException(
          "shelfj.jwt.secret must be set and at least 32 characters; refusing to start with a"
              + " weak or missing JWT secret");
    }
    verifier = JWT.require(Algorithm.HMAC256(secret)).withIssuer(config.jwtIssuer()).build();
  }

  @Override
  public void filter(ContainerRequestContext ctx) throws IOException {
    // Always strip any client-supplied identity headers to prevent spoofing.
    // Exception: onboarding paths may provide X-Tenant-Id for tenant context when JWT has no tenant
    // claim yet.
    String path = ctx.getUriInfo().getPath();
    String normalizedPath = normalize(path);
    boolean isOnboarding = isOnboarding(normalizedPath, ctx.getMethod());

    // Preserve X-Tenant-Id for onboarding paths (user may have just created tenant and is setting
    // up stores)
    String preservedTenantId = null;
    if (isOnboarding) {
      preservedTenantId = ctx.getHeaderString(HttpHeaders.TENANT_ID);
    }

    ctx.getHeaders().remove(HttpHeaders.TENANT_ID);
    ctx.getHeaders().remove(HttpHeaders.USER_ID);
    ctx.getHeaders().remove(HttpHeaders.USER_EMAIL);
    ctx.getHeaders().remove(HttpHeaders.ROLES);
    ctx.getHeaders().remove(HttpHeaders.STORE_IDS);

    // Allow public auth paths without a token.
    if (isPublic(path)) {
      return;
    }

    // Payment provider webhooks. The provider calls these from its own infrastructure: no JWT, no
    // storefront header, and no tenant to resolve — the intent named in the body is what says
    // which tenant the event concerns. They are authenticated instead by the provider's signature
    // over the raw body, verified inside payment-svc before anything is applied. Identity headers
    // have already been stripped above, so the request reaches the service with no role, which is
    // exactly right: it should be able to do nothing except be verified.
    if (isProviderWebhook(normalizedPath, ctx.getMethod())) {
      return;
    }

    // OpenAPI contract documents are not sensitive (no tenant data) and need to be reachable by
    // an unauthenticated browser (Swagger UI) for API discovery/docs.
    if ("GET".equals(ctx.getMethod()) && isOpenApiSpec(normalizedPath)) {
      return;
    }

    // Storefront public reads: tenant comes from the storefront header, regardless of whether
    // the caller also happens to carry a customer Bearer token (e.g. a signed-in customer still
    // browsing the catalog after checkout). These paths expose nothing sensitive — anyone can
    // already reach them with no token at all — so a present-but-irrelevant Bearer must not force
    // JWT verification and reject the request for lacking a tenant claim.
    //
    // But these same paths are ALSO called by authenticated staff with no storefront context at
    // all — e.g. the admin console checking inventory availability, or POS clock-in listing
    // stores via this same cashier-safe endpoint (see posStoresProvider: "tenant is taken from
    // the authenticated staff JWT"). Without X-Storefront-Tenant, that is NOT a guest/customer
    // call — fall through to normal Bearer verification below so the tenant gets resolved from
    // the JWT instead of being silently left unset (which previously surfaced downstream as a
    // blanket 401 NO_TENANT, e.g. every product showing "unavailable" regardless of real stock).
    if (isStorefrontPublic(normalize(path), ctx.getMethod())) {
      String storefrontTenant = ctx.getHeaderString(STOREFRONT_TENANT_HEADER);
      if (storefrontTenant != null && !storefrontTenant.isBlank()) {
        String tenant = storefrontTenant.trim();
        if (!tenantStatusGate.isActive(tenant)) {
          ctx.abortWith(tenantSuspended());
          return;
        }
        ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenant);
        return;
      }
    }

    String authHeader = ctx.getHeaderString("Authorization");
    boolean hasBearer = authHeader != null && authHeader.startsWith("Bearer ");
    if (!hasBearer) {
      ctx.abortWith(unauthorized("Missing or malformed Authorization header"));
      return;
    }

    String token = authHeader.substring(7).trim();
    DecodedJWT jwt;
    try {
      jwt = verifier.verify(token);
    } catch (JWTVerificationException e) {
      ctx.abortWith(unauthorized("Invalid or expired token"));
      return;
    }

    // Stamp verified claims as trusted headers for downstream services.
    String userId = jwt.getSubject();
    String email = jwt.getClaim("email").asString();
    String tenantId = jwt.getClaim("tenant").asString();
    List<String> roles = jwt.getClaim("roles").asList(String.class);
    List<String> storeIds = jwt.getClaim("storeIds").asList(String.class);

    if (userId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.USER_ID, userId);
    }
    // The caller's own verified email. A shopper's login is global and their orders are placed at
    // a shop that holds no record of them, so without this the shop cannot email, credit or erase
    // the person who bought (SJ-D44); customer-svc matches the login to its customer record on it.
    if (email != null && !email.isBlank()) {
      ctx.getHeaders().putSingle(HttpHeaders.USER_EMAIL, email.trim());
    }
    if (tenantId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenantId);
    } else if (isStorefrontCustomer(normalize(path), ctx.getMethod())) {
      // A signed-in customer carries identity (userId) but no tenant — a customer account is global
      // and shops across storefronts. For the whitelisted storefront-customer paths only, the
      // tenant is taken from the storefront header (same trusted source as guest browsing), so the
      // order is recorded against the right business while still being tied to the authenticated
      // customerId. This fallback is deliberately scoped to those paths: it must never let a
      // customer token name a tenant for admin endpoints.
      String storefrontTenant = ctx.getHeaderString(STOREFRONT_TENANT_HEADER);
      if (storefrontTenant != null && !storefrontTenant.isBlank()) {
        String tenant = storefrontTenant.trim();
        if (!tenantStatusGate.isActive(tenant)) {
          ctx.abortWith(tenantSuspended());
          return;
        }
        ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenant);
      }
    }
    if (roles != null && !roles.isEmpty()) {
      ctx.getHeaders().putSingle(HttpHeaders.ROLES, String.join(",", roles));
    }
    if (storeIds != null && !storeIds.isEmpty()) {
      ctx.getHeaders().putSingle(HttpHeaders.STORE_IDS, String.join(",", storeIds));
    }

    // Restore preserved tenant ID for onboarding paths (flow guard: user provides tenant context)
    if (preservedTenantId != null && !preservedTenantId.isBlank() && tenantId == null) {
      ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, preservedTenantId.trim());
    }
  }

  /**
   * Onboarding paths where user may provide tenant context before it's in the JWT. These paths are
   * part of the tenant creation flow and need X-Tenant-Id for the newly created tenant.
   */
  private static boolean isOnboarding(String path, String method) {
    // POST /onboarding/stores — create store for newly created tenant
    if ("POST".equals(method) && "api/tenant-svc/onboarding/stores".equals(path)) {
      return true;
    }
    // GET /onboarding/status — check onboarding progress for tenant
    if ("GET".equals(method) && "api/tenant-svc/onboarding/status".equals(path)) {
      return true;
    }
    return false;
  }

  /**
   * Whitelisted authenticated-customer storefront paths (already normalized). These require a valid
   * customer token AND derive the tenant from the storefront header: guest checkout that a
   * signed-in customer makes (so the order links to their customerId) and the customer's own order
   * history.
   */
  private static boolean isStorefrontCustomer(String path, String method) {
    if ("POST".equals(method) && "api/order-svc/orders".equals(path)) {
      return true;
    }
    // Opening a payment intent, and polling it after the customer returns from SCA. Same shopper
    // and same point in checkout as api/payment-svc/payments/online below. Capturing is absent on
    // purpose: POST .../capture is staff-only and goes through normal Bearer verification.
    if ("POST".equals(method) && "api/payment-svc/payments/intents".equals(path)) {
      return true;
    }
    if ("GET".equals(method) && isPaymentIntentRead(path)) {
      return true;
    }
    if ("GET".equals(method) && "api/order-svc/orders/mine".equals(path)) {
      return true;
    }
    // The shopper opening one of their own orders: the id-addressed reads the downstream filter
    // already treats as self-reads, with order-svc's object-level check behind them (the owning
    // login gets the order, anyone else a 404). Until this, a customer token could list its orders
    // through /orders/mine and could not open any of them, because no tenant was derived for the
    // read by id — a gap the privacy-flow k6 suite hit the first time it drove the real door.
    if ("GET".equals(method) && isOrderSelfRead(path)) {
      return true;
    }
    if ("POST".equals(method) && "api/payment-svc/payments/online".equals(path)) {
      return true;
    }
    // The shopper's own account with one shop: the customer record their login owns there, the
    // marketing they have agreed to, and the data export art.20 entitles them to. Same shape as
    // /orders/mine — a global customer token plus the storefront's tenant header — and, like it,
    // every one of these resolves the caller from the token and can reach no other person's data.
    if (isStorefrontCustomerAccount(path, method)) {
      return true;
    }
    return false;
  }

  /**
   * The {@code customer-svc} self-service shapes a signed-in shopper reaches from a storefront.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} for the caller's own customer record, preference centre and export
   */
  private static boolean isStorefrontCustomerAccount(String path, String method) {
    boolean get = "GET".equals(method);
    return switch (path) {
      case "api/customer-svc/customers/me" -> get || "POST".equals(method);
      case "api/customer-svc/customers/me/marketing" -> get || "PUT".equals(method);
      case "api/customer-svc/customers/me/export" -> get;
      default -> false;
    };
  }

  /**
   * Whitelisted public storefront paths (already normalized): catalog reads, price resolve. Public
   * regardless of caller identity — reachable by guests, and equally by signed-in customers who
   * happen to carry a Bearer token while browsing (see the call site: this check runs before JWT
   * verification, so an irrelevant token never turns these into authenticated-only paths).
   */
  private static boolean isStorefrontPublic(String path, String method) {
    if ("GET".equals(method) && path.startsWith("api/product-svc/catalog")) {
      return true;
    }
    // Per-store storefront config (show-prices flag) and stock availability.
    if ("GET".equals(method) && path.startsWith("api/tenant-svc/storefront")) {
      return true;
    }
    // Soft delivery-coverage check before checkout (pincode → fulfilling store).
    if ("GET".equals(method) && "api/tenant-svc/fulfilment/resolve".equals(path)) {
      return true;
    }
    if ("GET".equals(method) && path.startsWith("api/inventory-svc/inventory/availability")) {
      return true;
    }
    if ("POST".equals(method) && "api/pricing-svc/prices/resolve".equals(path)) {
      return true;
    }
    // Active promotions powering the storefront offers banner (advertised, public offers).
    if ("GET".equals(method) && "api/pricing-svc/promotions".equals(path)) {
      return true;
    }
    // NOTE: order placement and payment are NOT listed here — they require a signed-in customer
    // token and are handled by isStorefrontCustomer(). Allowing unauthenticated callers to
    // supply X-Storefront-Tenant on mutating endpoints would let any party inject orders or
    // payment records into any tenant's namespace without authentication.
    return false;
  }

  /**
   * {@code POST api/payment-svc/payments/webhooks/{provider}} — exactly five segments, so nothing
   * deeper inherits the exemption.
   *
   * @param path the normalized request path
   * @param method the HTTP method
   * @return {@code true} if this is a provider webhook delivery
   */
  private static boolean isProviderWebhook(String path, String method) {
    if (!"POST".equals(method)) {
      return false;
    }
    String prefix = "api/payment-svc/payments/webhooks/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String provider = path.substring(prefix.length());
    return !provider.isEmpty() && provider.indexOf('/') < 0;
  }

  /**
   * {@code api/payment-svc/payments/intents/{id}} and nothing under it.
   *
   * @param path the normalized request path
   * @return {@code true} for exactly that shape
   */
  /**
   * {@code GET api/order-svc/orders/{uuid}} and its {@code history}, {@code returns} and {@code
   * fiscal-receipt} children — and only an id-shaped segment, so a literal such as {@code export}
   * (staff-only) can never pass as an order.
   *
   * @param path the normalized request path
   * @return whether it is a shopper's id-addressed read of one order
   */
  private static boolean isOrderSelfRead(String path) {
    String prefix = "api/order-svc/orders/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf('/');
    String id = slash < 0 ? rest : rest.substring(0, slash);
    if (id.length() != 36
        || !id.chars()
            .allMatch(
                c ->
                    c == '-'
                        || (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F'))) {
      return false;
    }
    if (slash < 0) {
      return true;
    }
    String child = rest.substring(slash + 1);
    return "history".equals(child) || "returns".equals(child) || "fiscal-receipt".equals(child);
  }

  private static boolean isPaymentIntentRead(String path) {
    String prefix = "api/payment-svc/payments/intents/";
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    return !rest.isEmpty() && rest.indexOf('/') < 0;
  }

  private static boolean isPublic(String path) {
    // Exact match only — a substring match would let any URL that merely embeds a public
    // suffix (e.g. /api/x-svc/foo/iam-svc/auth/login) skip token validation.
    return PUBLIC_PATHS.contains(normalize(path));
  }

  /**
   * {@code GET /api/{service}/openapi} — the MicroProfile OpenAPI contract document Helidon exposes
   * on every business service. Exactly three segments (the trailing-segment match keeps this from
   * also matching e.g. {@code api/order-svc/orders/openapi-discount}); the service name itself is
   * still gated by {@link com.shelfj.gateway.GatewayConfig#routableServices()} in {@code
   * ProxyResource}, so this only ever reaches a real, routable service.
   */
  private static boolean isOpenApiSpec(String normalizedPath) {
    String[] segments = normalizedPath.split("/");
    return segments.length == 3 && "api".equals(segments[0]) && "openapi".equals(segments[2]);
  }

  private static String normalize(String path) {
    String p = path;
    while (p.startsWith("/")) p = p.substring(1);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    // Collapse an optional API version segment so /api/v1/... matches the same public/storefront/
    // onboarding whitelists as the unversioned /api/... alias (golden rule #2 stays exact-match).
    p = p.replaceFirst("^api/v\\d+/", "api/");
    return p;
  }

  private static Response tenantSuspended() {
    return Response.status(Response.Status.FORBIDDEN)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.shelfj.web.ApiResponse.error(
                com.shelfj.web.ErrorBody.of(
                    "TENANT_INACTIVE", "This store is currently unavailable.")))
        .build();
  }

  private static Response unauthorized(String message) {
    return Response.status(Response.Status.UNAUTHORIZED)
        .type(MediaType.APPLICATION_JSON)
        .entity(
            com.shelfj.web.ApiResponse.error(com.shelfj.web.ErrorBody.of("UNAUTHORIZED", message)))
        .build();
  }
}
