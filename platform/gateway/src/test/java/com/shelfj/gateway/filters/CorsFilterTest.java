package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.gateway.GatewayConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CorsFilterTest {

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext requestContext;
  @Mock ContainerResponseContext responseContext;

  private CorsFilter filter;

  @BeforeEach
  void setUp() {
    filter = new CorsFilter();
    filter.config = config;
  }

  @Test
  void preflightFromAllowedOriginIsAnsweredWithCorsHeaders() throws IOException {
    when(config.corsAllowedOrigins()).thenReturn(Set.of("https://shop.example.com"));
    when(requestContext.getHeaderString("Origin")).thenReturn("https://shop.example.com");
    when(requestContext.getMethod()).thenReturn("OPTIONS");
    when(requestContext.getHeaderString("Access-Control-Request-Method")).thenReturn("POST");

    filter.filter(requestContext);

    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(requestContext).abortWith(captor.capture());
    Response resp = captor.getValue();
    assertEquals(204, resp.getStatus());
    assertEquals("https://shop.example.com", resp.getHeaderString("Access-Control-Allow-Origin"));
    assertTrue(resp.getHeaderString("Access-Control-Allow-Methods").contains("PATCH"));
    assertTrue(resp.getHeaderString("Access-Control-Allow-Headers").contains("Idempotency-Key"));
  }

  @Test
  void preflightFromUnknownOriginGetsNoCorsHeaders() throws IOException {
    when(config.corsAllowedOrigins()).thenReturn(Set.of("https://shop.example.com"));
    when(requestContext.getHeaderString("Origin")).thenReturn("https://evil.example.com");
    when(requestContext.getMethod()).thenReturn("OPTIONS");
    when(requestContext.getHeaderString("Access-Control-Request-Method")).thenReturn("POST");

    filter.filter(requestContext);

    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(requestContext).abortWith(captor.capture());
    assertNull(captor.getValue().getHeaderString("Access-Control-Allow-Origin"));
  }

  @Test
  void nonBrowserRequestPassesThroughUntouched() throws IOException {
    when(requestContext.getHeaderString("Origin")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void actualResponseToAllowedOriginCarriesCorsHeaders() throws IOException {
    when(config.corsAllowedOrigins()).thenReturn(Set.of("https://shop.example.com"));
    when(requestContext.getHeaderString("Origin")).thenReturn("https://shop.example.com");
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    when(responseContext.getHeaders()).thenReturn(headers);

    filter.filter(requestContext, responseContext);

    assertEquals("https://shop.example.com", headers.getFirst("Access-Control-Allow-Origin"));
    assertTrue(
        ((String) headers.getFirst("Access-Control-Expose-Headers")).contains("X-Request-Id"));
  }

  @Test
  void noOriginsConfiguredMeansNoCorsHeadersAnywhere() throws IOException {
    when(config.corsAllowedOrigins()).thenReturn(Set.of());
    when(requestContext.getHeaderString("Origin")).thenReturn("https://shop.example.com");
    MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
    // The filter short-circuits before reading headers when the origin is not allowed.
    org.mockito.Mockito.lenient().when(responseContext.getHeaders()).thenReturn(headers);

    filter.filter(requestContext, responseContext);

    assertTrue(headers.isEmpty());
  }
}
