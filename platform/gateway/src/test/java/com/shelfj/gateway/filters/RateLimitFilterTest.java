package com.shelfj.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
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
    when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("192.0.2.1");

    filter.filter(requestContext);
    filter.filter(requestContext);
    filter.filter(requestContext);

    verify(requestContext, times(1)).abortWith(any());
  }
}
