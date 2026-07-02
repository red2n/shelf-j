package com.shelfj.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-upstream-service failure tracking for {@link ProxyResource}, keyed by service name. The
 * gateway's proxy methods route to every business service through one shared {@code WebClient} with
 * no isolation between upstreams — without this, a degraded-but-not-fully-down service (e.g.
 * inventory-svc answering just under the timeout) gets every proxied request dispatched to it at
 * full volume, each one tying up gateway resources for the full {@code
 * upstream.read-timeout-seconds} before failing, and gives callers no fast-fail signal. Tracking
 * failures per service (instead of one breaker shared across all proxy traffic) means a degraded
 * service trips its own circuit without affecting requests to any other, healthy service.
 *
 * <p>Only connectivity/timeout failures (the upstream didn't answer at all) count against a
 * service's circuit — a legitimate 4xx/5xx application response means the service did answer, so it
 * isn't treated as a reliability failure here.
 */
@ApplicationScoped
class UpstreamCircuitBreaker {

  @Inject GatewayConfig config;

  private record State(int consecutiveFailures, Instant openUntil) {}

  private final Map<String, State> states = new ConcurrentHashMap<>();

  /** True if this service's circuit is open (tripped recently enough to still be fail-fast). */
  boolean isOpen(String service) {
    State s = states.get(service);
    return s != null && s.openUntil() != null && Instant.now().isBefore(s.openUntil());
  }

  /** A request to this service succeeded (got any HTTP response) — clear its failure history. */
  void recordSuccess(String service) {
    states.remove(service);
  }

  /** A request to this service couldn't even get a response (connect/read failure or timeout). */
  void recordFailure(String service) {
    states.compute(
        service,
        (k, prev) -> {
          int failures = (prev == null ? 0 : prev.consecutiveFailures()) + 1;
          Instant openUntil =
              failures >= config.circuitBreakerFailureThreshold()
                  ? Instant.now().plusSeconds(config.circuitBreakerOpenSeconds())
                  : null;
          return new State(failures, openUntil);
        });
  }
}
