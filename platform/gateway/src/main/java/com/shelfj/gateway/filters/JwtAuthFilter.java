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
          "api/iam-svc/auth/refresh",
          "api/iam-svc/bootstrap/admin");

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
    ctx.getHeaders().remove(HttpHeaders.TENANT_ID);
    ctx.getHeaders().remove(HttpHeaders.USER_ID);
    ctx.getHeaders().remove(HttpHeaders.ROLES);

    String path = ctx.getUriInfo().getPath();

    // Allow public auth paths without a token.
    if (isPublic(path)) {
      return;
    }

    String authHeader = ctx.getHeaderString("Authorization");
    boolean hasBearer = authHeader != null && authHeader.startsWith("Bearer ");

    // Guest storefront access: no token needed; tenant comes from the storefront header.
    if (!hasBearer && isStorefrontPublic(normalize(path), ctx.getMethod())) {
      String storefrontTenant = ctx.getHeaderString(STOREFRONT_TENANT_HEADER);
      if (storefrontTenant != null && !storefrontTenant.isBlank()) {
        String tenant = storefrontTenant.trim();
        if (!tenantStatusGate.isActive(tenant)) {
          ctx.abortWith(tenantSuspended());
          return;
        }
        ctx.getHeaders().putSingle(HttpHeaders.TENANT_ID, tenant);
      }
      return;
    }

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
    String tenantId = jwt.getClaim("tenant").asString();
    List<String> roles = jwt.getClaim("roles").asList(String.class);

    if (userId != null) {
      ctx.getHeaders().putSingle(HttpHeaders.USER_ID, userId);
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
    if ("GET".equals(method) && "api/order-svc/orders/mine".equals(path)) {
      return true;
    }
    if ("POST".equals(method) && "api/payment-svc/payments/online".equals(path)) {
      return true;
    }
    return false;
  }

  /**
   * Whitelisted guest storefront paths (already normalized): catalog reads, price resolve, guest
   * checkout.
   */
  private static boolean isStorefrontPublic(String path, String method) {
    if ("GET".equals(method) && path.startsWith("api/product-svc/catalog")) {
      return true;
    }
    // Per-store storefront config (show-prices flag) and stock availability.
    if ("GET".equals(method) && path.startsWith("api/tenant-svc/storefront")) {
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

  private static boolean isPublic(String path) {
    // Exact match only — a substring match would let any URL that merely embeds a public
    // suffix (e.g. /api/x-svc/foo/iam-svc/auth/login) skip token validation.
    return PUBLIC_PATHS.contains(normalize(path));
  }

  private static String normalize(String path) {
    String p = path;
    while (p.startsWith("/")) p = p.substring(1);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
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
