package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
import io.helidon.webserver.http.ServerRequest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Provider
@ApplicationScoped
// Must run BEFORE authentication (JwtAuthFilter at 999): rate limiting exists precisely to
// shed unauthenticated floods cheaply, so it cannot sit behind the auth check.
@Priority(100)
public class RateLimitFilter implements ContainerRequestFilter {

  /** Hard cap on tracked buckets so key churn cannot exhaust gateway memory. */
  static final int MAX_BUCKETS = 10_000;

  @Inject GatewayConfig config;

  /** Helidon binds the underlying server request per-request; gives the socket remote address. */
  @Context ServerRequest serverRequest;

  final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

  @SuppressWarnings("PMD.CloseResource")
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.rateLimitEnabled()) {
      return;
    }

    String ip = ClientIp.resolve(requestContext, serverRequest, config.trustForwardedHeaders());
    if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(ip)) {
      evictStale();
      // Stale eviction freed nothing (all buckets active) — drop the least-recently-active
      // bucket so the cap is a real bound, not a suggestion an attacker can blow past with key
      // churn. Picking the coldest bucket (instead of an arbitrary one) makes it more likely an
      // idle legitimate caller is evicted than a currently-hammering attacker's own bucket.
      if (buckets.size() >= MAX_BUCKETS) {
        evictLeastRecentlyActive();
      }
    }
    TokenBucket bucket =
        buckets.computeIfAbsent(ip, k -> new TokenBucket(config.rateLimitRequestsPerMinute()));
    if (!bucket.tryConsume()) {
      Response resp =
          Response.status(429)
              .header("Retry-After", "60")
              .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
              .entity(
                  com.shelfj.web.ApiResponse.error(
                      com.shelfj.web.ErrorBody.of(
                          "RATE_LIMITED", "Too many requests - rate limit exceeded")))
              .build();
      requestContext.abortWith(resp);
    }
  }

  private void evictStale() {
    long now = System.currentTimeMillis();
    buckets.values().removeIf(b -> b.isStale(now));
  }

  /**
   * Wall-clock millis are too coarse under a fast burst (the exact scenario that fills the map to
   * MAX_BUCKETS) — many buckets can tie on the same millisecond, making "oldest timestamp" picks
   * effectively arbitrary among the tied group. A strictly increasing counter, stamped on every
   * touch, has no ties.
   */
  private static final AtomicLong TOUCH_SEQ = new AtomicLong();

  private void evictLeastRecentlyActive() {
    String oldestKey = null;
    long oldestSeq = Long.MAX_VALUE;
    for (var e : buckets.entrySet()) {
      long seq = e.getValue().lastTouchSeq();
      if (seq < oldestSeq) {
        oldestSeq = seq;
        oldestKey = e.getKey();
      }
    }
    if (oldestKey != null) {
      buckets.remove(oldestKey);
    }
  }

  private static final class TokenBucket {

    private final int capacity;
    private static final long refillIntervalMs = 60_000L;
    private int tokens;
    private long lastRefillMs;
    private long lastTouchSeq;

    TokenBucket(int capacity) {
      this.capacity = Math.max(1, capacity);
      this.tokens = this.capacity;
      this.lastRefillMs = System.currentTimeMillis();
      this.lastTouchSeq = TOUCH_SEQ.incrementAndGet();
    }

    synchronized boolean tryConsume() {
      lastTouchSeq = TOUCH_SEQ.incrementAndGet();
      long now = System.currentTimeMillis();
      if (now - lastRefillMs >= refillIntervalMs) {
        tokens = capacity;
        lastRefillMs = now;
      }
      if (tokens <= 0) {
        return false;
      }
      tokens--;
      return true;
    }

    /** A bucket untouched for two refill windows is dead weight and safe to evict. */
    synchronized boolean isStale(long now) {
      return now - lastRefillMs >= 2 * refillIntervalMs;
    }

    synchronized long lastTouchSeq() {
      return lastTouchSeq;
    }
  }
}
