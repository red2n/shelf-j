package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.gateway.GatewayConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  void capEvictsTheLeastRecentlyActiveBucketNotAnArbitraryOne() throws IOException {
    when(config.rateLimitEnabled()).thenReturn(true);
    when(config.rateLimitRequestsPerMinute()).thenReturn(100);
    when(config.trustForwardedHeaders()).thenReturn(true);
    var counter = new AtomicInteger();
    when(requestContext.getHeaderString("X-Forwarded-For"))
        .thenAnswer(inv -> "10.0.0." + counter.getAndIncrement());

    String firstIp = "10.0.0.0";
    String lastIp = "10.0.0." + RateLimitFilter.MAX_BUCKETS;
    // Fill to the cap, then one more distinct IP forces an eviction (no stale entries exist to
    // reclaim instead, since every bucket was just created).
    for (int i = 0; i <= RateLimitFilter.MAX_BUCKETS; i++) {
      filter.filter(requestContext);
    }

    assertEquals(RateLimitFilter.MAX_BUCKETS, filter.buckets.size());
    assertFalse(filter.buckets.containsKey(firstIp), "oldest bucket should have been evicted");
    assertTrue(filter.buckets.containsKey(lastIp), "newest bucket should be kept");
  }
}
