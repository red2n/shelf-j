package com.shelfj.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.gateway.GatewayConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext requestContext;

  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    filter = new RateLimitFilter();
    filter.config = config;
  }

  @Test
  void shouldAbortRequestAfterRateLimitExceeded() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    verify(requestContext, times(1)).abortWith(any());
  }

  @Test
  void rotatingForwardedForHeaderDoesNotEscapeTheLimitWhenTrustDisabled() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);
    when(config.trustForwardedHeaders()).thenReturn(false);
    // Attacker rotates X-Forwarded-For per request; without a trusted proxy the header must
    // never even be read, so all requests land in the same (socket-derived) bucket.
    org.mockito.Mockito.lenient()
        .when(requestContext.getHeaderString("X-Forwarded-For"))
        .thenReturn("10.0.0.1", "10.0.0.2", "10.0.0.3");

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    verify(requestContext, times(1)).abortWith(any());
  }

  @Test
  void forwardedForIsHonouredOnlyBehindTrustedProxy() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(2);
    when(config.trustForwardedHeaders()).thenReturn(true);
    when(requestContext.getHeaderString("X-Forwarded-For"))
        .thenReturn("10.0.0.1", "10.0.0.2", "10.0.0.3");

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    // three distinct clients as reported by the trusted proxy — nobody throttled
    verify(requestContext, never()).abortWith(any());
  }
}
