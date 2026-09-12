package com.shelfj.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.gateway.GatewayConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext requestContext;
  @Mock UriInfo uriInfo;
  @Mock TenantStatusGate tenantStatusGate;

  private JwtAuthFilter filter;
  private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

  @BeforeEach
  void setUp() {
    lenient().when(config.jwtSecret()).thenReturn("unit-test-secret-of-at-least-32-chars!!");
    lenient().when(config.jwtIssuer()).thenReturn("shelfj");
    lenient().when(tenantStatusGate.isActive(any())).thenReturn(true);
    filter = new JwtAuthFilter();
    filter.config = config;
    filter.tenantStatusGate = tenantStatusGate;
    filter.init();
    lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
    lenient().when(requestContext.getHeaders()).thenReturn(headers);
  }

  // ── Payment provider webhooks ──────────────────────────────────────────────
  // These bypass token validation entirely, so the shape they match has to be exact. The provider
  // calls them from its own infrastructure with no JWT and no storefront header; authentication is
  // the provider's signature over the raw body, checked inside payment-svc.

  @Test
  void providerWebhookBypassesTokenValidation() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/stripe");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedProviderWebhookAlsoBypasses() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/v1/payment-svc/payments/webhooks/stripe");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  /** A GET on the webhook path is not a delivery, so it gets no exemption. */
  @Test
  void webhookExemptionIsPostOnly() throws IOException {
    when(requestContext.getMethod()).thenReturn("GET");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/stripe");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  /**
   * The exemption is for exactly one segment after /webhooks/. Anything deeper, or a route that
   * merely starts with the same characters, still needs a token — the same trap the public-suffix
   * test above guards.
   */
  @Test
  void pathsBeyondTheWebhookShapeStillRequireAToken() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/stripe/replay");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void webhookLookalikePathStillRequiresAToken() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks-replay");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  /** A bare /webhooks/ with no provider names nothing, so it is not a delivery. */
  @Test
  void webhookWithNoProviderRequiresAToken() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void publicLoginPathBypassesTokenValidation() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/login");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedPublicLoginPathBypassesTokenValidation() throws IOException {
    // /api/v1/... must hit the same public whitelist as the unversioned alias.
    when(uriInfo.getPath()).thenReturn("api/v1/iam-svc/auth/login");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedStorefrontCatalogResolvesTenant() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/v1/product-svc/catalog/products");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void pathMerelyEmbeddingPublicSuffixStillRequiresToken() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/product-svc/x/iam-svc/auth/login");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void openApiSpecBypassesTokenValidation() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/openapi");
    when(requestContext.getMethod()).thenReturn("GET");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void openApiSpecRequiresGet() throws IOException {
    // Same path, wrong verb — must not be treated as the public spec endpoint.
    when(uriInfo.getPath()).thenReturn("api/order-svc/openapi");
    when(requestContext.getMethod()).thenReturn("POST");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void pathEmbeddingOpenApiSuffixStillRequiresToken() throws IOException {
    // Trailing-segment match only: a deeper path that happens to end in a different segment
    // after "openapi" must not slip through.
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders/openapi");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void protectedPathWithoutTokenIsRejected() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void garbageTokenIsRejected() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer not-a-jwt");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void signedInCustomerGetsTenantFromStorefrontHeaderOnMyOrders() throws IOException {
    // A customer token carries identity but no tenant claim.
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withIssuer("shelfj")
            .withSubject("01a090ae-611e-700b-bde4-50df0324c37c")
            .withClaim("type", "CUSTOMER")
            .withArrayClaim("roles", new String[] {"CUSTOMER"})
            .sign(
                com.auth0.jwt.algorithms.Algorithm.HMAC256(
                    "unit-test-secret-of-at-least-32-chars!!"));
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders/mine");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
    org.junit.jupiter.api.Assertions.assertEquals(
        "01a090ae-611e-700b-bde4-50df0324c37c", headers.getFirst("X-User-Id"));
  }

  private String customerToken() {
    return com.auth0
        .jwt
        .JWT
        .create()
        .withIssuer("shelfj")
        .withSubject("01a090ae-611e-700b-bde4-50df0324c37c")
        .withClaim("type", "CUSTOMER")
        .withArrayClaim("roles", new String[] {"CUSTOMER"})
        .sign(
            com.auth0.jwt.algorithms.Algorithm.HMAC256("unit-test-secret-of-at-least-32-chars!!"));
  }

  @Test
  void customerTokenOpensItsOwnOrderById() throws IOException {
    // A shopper could list their orders through /orders/mine and open none of them: no tenant was
    // derived for the read by id. The id-shaped self-reads now get the storefront tenant too;
    // order-svc's object-level check decides whose order it is.
    for (String path :
        new String[] {
          "api/order-svc/orders/01a09509-72ec-72e9-9f08-94a93df26a36",
          "api/order-svc/orders/01a09509-72ec-72e9-9f08-94a93df26a36/history",
          "api/order-svc/orders/01a09509-72ec-72e9-9f08-94a93df26a36/fiscal-receipt"
        }) {
      headers.clear();
      when(uriInfo.getPath()).thenReturn(path);
      when(requestContext.getMethod()).thenReturn("GET");
      when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + customerToken());
      when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");
      filter.filter(requestContext);
      org.junit.jupiter.api.Assertions.assertEquals(
          "tenant-abc", headers.getFirst("X-Tenant-Id"), path);
    }
  }

  @Test
  void customerTokenGetsNoTenantForALiteralOrderChild() throws IOException {
    // /orders/export names a subject and is staff-only; a literal must never pass as an order id
    // here any more than it does in the downstream read allowlist.
    for (String path : new String[] {"api/order-svc/orders/export", "api/order-svc/orders/abc"}) {
      headers.clear();
      when(uriInfo.getPath()).thenReturn(path);
      when(requestContext.getMethod()).thenReturn("GET");
      when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + customerToken());
      // lenient: the point is that the filter never asks for the storefront header on these paths.
      org.mockito.Mockito.lenient()
          .when(requestContext.getHeaderString("X-Storefront-Tenant"))
          .thenReturn("tenant-abc");
      filter.filter(requestContext);
      org.junit.jupiter.api.Assertions.assertNull(headers.getFirst("X-Tenant-Id"), path);
    }
  }

  @Test
  void customerTokenCannotNameTenantForAdminOrderList() throws IOException {
    // The storefront-tenant fallback must be scoped to whitelisted customer paths — a customer
    // token hitting the admin order list must NOT get a tenant stamped from the storefront header,
    // or it could read another business's full order book.
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withIssuer("shelfj")
            .withSubject("01a090ae-611e-700b-bde4-50df0324c37c")
            .withArrayClaim("roles", new String[] {"CUSTOMER"})
            .sign(
                com.auth0.jwt.algorithms.Algorithm.HMAC256(
                    "unit-test-secret-of-at-least-32-chars!!"));
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    lenient().when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Tenant-Id"));
  }

  @Test
  void guestCanReadActivePromotionsWithStorefrontTenant() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/pricing-svc/promotions");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void signedInCustomerCanStillBrowseCatalogAfterCheckout() throws IOException {
    // Regression: a signed-in customer's Dio client attaches its Bearer token to every request,
    // including plain catalog browsing. That irrelevant token must not force JWT verification and
    // reject the request for lacking a tenant claim — these paths stay public regardless of caller.
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withIssuer("shelfj")
            .withSubject("01a090ae-611e-700b-bde4-50df0324c37c")
            .withClaim("type", "CUSTOMER")
            .withArrayClaim("roles", new String[] {"CUSTOMER"})
            .sign(
                com.auth0.jwt.algorithms.Algorithm.HMAC256(
                    "unit-test-secret-of-at-least-32-chars!!"));
    when(uriInfo.getPath()).thenReturn("api/product-svc/catalog/products");
    when(requestContext.getMethod()).thenReturn("GET");
    // Present but must never be consulted: this path stays public regardless of caller identity.
    lenient().when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void staffBearerTokenResolvesTenantOnStorefrontPublicPathWithoutHeader() throws IOException {
    // Regression: an authenticated staff caller (e.g. the admin console checking inventory
    // availability, or POS clock-in listing stores via the same cashier-safe endpoint) has no
    // storefront context and sends no X-Storefront-Tenant — it must fall through to normal Bearer
    // verification instead of being silently left tenant-less (previously surfaced downstream as
    // a blanket 401 NO_TENANT on every call).
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withIssuer("shelfj")
            .withSubject("01a090ae-611e-700f-b645-a14095230b77")
            .withClaim("type", "STAFF")
            .withClaim("tenant", "tenant-xyz")
            .withArrayClaim("roles", new String[] {"OWNER"})
            .sign(
                com.auth0.jwt.algorithms.Algorithm.HMAC256(
                    "unit-test-secret-of-at-least-32-chars!!"));
    when(uriInfo.getPath()).thenReturn("api/inventory-svc/inventory/availability");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-xyz", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void suspendedTenantStorefrontRequestIsBlocked() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/product-svc/catalog/products");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("dead-tenant");
    when(tenantStatusGate.isActive("dead-tenant")).thenReturn(false);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Tenant-Id"));
  }

  @Test
  void clientSuppliedIdentityHeadersAreStrippedEvenOnPublicPaths() throws IOException {
    headers.putSingle("X-Tenant-Id", "spoofed");
    headers.putSingle("X-User-Id", "spoofed");
    headers.putSingle("X-Roles", "PLATFORM_ADMIN");
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/login");

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Tenant-Id"));
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-User-Id"));
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Roles"));
  }
}
