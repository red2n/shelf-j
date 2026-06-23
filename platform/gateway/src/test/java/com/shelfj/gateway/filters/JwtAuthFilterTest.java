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
            .withSubject("11111111-1111-1111-1111-111111111111")
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
        "11111111-1111-1111-1111-111111111111", headers.getFirst("X-User-Id"));
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
            .withSubject("11111111-1111-1111-1111-111111111111")
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
            .withSubject("11111111-1111-1111-1111-111111111111")
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
