package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Provider
@ApplicationScoped
@Priority(Priorities.USER)
public class RateLimitFilter implements ContainerRequestFilter {

  @Inject GatewayConfig config;
  private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

  @SuppressWarnings("PMD.CloseResource")
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.rateLimitEnabled()) {
      return;
    }

    String ip = extractClientIp(requestContext);
    TokenBucket bucket =
        buckets.computeIfAbsent(ip, k -> new TokenBucket(config.rateLimitRequestsPerMinute()));
    if (!bucket.tryConsume()) {
      Response resp =
          Response.status(429)
              .header("Retry-After", "60")
              .entity("Too many requests - rate limit exceeded")
              .build();
      requestContext.abortWith(resp);
    }
  }

  private String extractClientIp(ContainerRequestContext ctx) {
    String xf = ctx.getHeaderString("X-Forwarded-For");
    if (xf != null && !xf.isBlank()) {
      return xf.split(",")[0].trim();
    }
    String xr = ctx.getHeaderString("X-Real-IP");
    if (xr != null && !xr.isBlank()) {
      return xr;
    }
    return "unknown";
  }

  private static final class TokenBucket {

    private final int capacity;
    private static final long refillIntervalMs = 60_000L;
    private int tokens;
    private long lastRefillMs;

    TokenBucket(int capacity) {
      this.capacity = Math.max(1, capacity);
      this.tokens = this.capacity;
      this.lastRefillMs = System.currentTimeMillis();
    }

    synchronized boolean tryConsume() {
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
  }
}
