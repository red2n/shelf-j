package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
import io.helidon.webserver.http.ServerRequest;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

@Provider
@ApplicationScoped
// Must run BEFORE authentication (JwtAuthFilter at 999): rate limiting exists precisely to
// shed unauthenticated floods cheaply, so it cannot sit behind the auth check.
@Priority(100)
public class RateLimitFilter implements ContainerRequestFilter {

  private static final Logger LOG = System.getLogger(RateLimitFilter.class.getName());

  /**
   * Fixed window: a key's counter resets WINDOW_SECONDS after the request that first created it
   * (not on a calendar boundary). Counters live in Redis — shared across every gateway replica —
   * instead of per-instance heap, so an attacker cannot dodge the limit by hitting a different
   * replica. Redis's own {@code maxmemory-policy allkeys-lru} (docker-compose) bounds key churn; no
   * bespoke eviction needed here.
   */
  static final long WINDOW_SECONDS = 60;

  /** INCR + conditional EXPIRE in one round trip — atomic, so concurrent replicas can't race. */
  private static final String INCR_WITH_EXPIRE_SCRIPT =
      "local current = redis.call('INCR', KEYS[1]) "
          + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
          + "return current";

  @Inject GatewayConfig config;

  @Inject RedisCommands<String, String> redis;

  /** Helidon binds the underlying server request per-request; gives the socket remote address. */
  @Context ServerRequest serverRequest;

  @SuppressWarnings("PMD.CloseResource")
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (!config.rateLimitEnabled()) {
      return;
    }

    String ip = ClientIp.resolve(requestContext, serverRequest, config.trustForwardedHeaders());
    long current = consume(ip);
    if (current > config.rateLimitRequestsPerMinute()) {
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

  /**
   * Returns this IP's count within the current window, or 0 when Redis cannot answer.
   *
   * <p>Fails <b>open</b>: an unreachable counter store means requests are allowed through
   * unmetered. The alternative — treating "cannot count" as "over the limit" — turns a Redis outage
   * into a total outage of the public door, which is a far worse failure than briefly unmetered
   * traffic. Rate limiting sheds load; it is not an authorisation control, and every request still
   * passes JWT validation and the upstream circuit breakers behind this.
   *
   * <p>Logged at WARNING so a silently unmetered gateway is visible rather than assumed.
   */
  private long consume(String ip) {
    try {
      Long current =
          redis.eval(
              INCR_WITH_EXPIRE_SCRIPT,
              ScriptOutputType.INTEGER,
              new String[] {"ratelimit:" + ip},
              String.valueOf(WINDOW_SECONDS));
      return current == null ? 0L : current;
    } catch (RedisException e) {
      LOG.log(
          Level.WARNING,
          "Rate-limit counter unavailable — allowing request unmetered: {0}",
          e.toString());
      return 0L;
    }
  }
}
