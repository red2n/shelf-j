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

  private JwtAuthFilter filter;
  private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

  @BeforeEach
  void setUp() {
    lenient().when(config.jwtSecret()).thenReturn("unit-test-secret-of-at-least-32-chars!!");
    lenient().when(config.jwtIssuer()).thenReturn("shelfj");
    filter = new JwtAuthFilter();
    filter.config = config;
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
