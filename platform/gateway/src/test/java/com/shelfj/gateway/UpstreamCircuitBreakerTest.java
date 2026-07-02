package com.shelfj.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * No DI seam exists for GatewayConfig (its fields are MP-Config-injected, populated only by the
 * container) — same as other gateway/service unit tests in this codebase, this drives the class
 * directly via its package-private fields instead of a mocking framework.
 */
class UpstreamCircuitBreakerTest {

  private static UpstreamCircuitBreaker newBreaker(int threshold, int openSeconds) {
    var config = new GatewayConfig();
    config.circuitBreakerFailureThreshold = threshold;
    config.circuitBreakerOpenSeconds = openSeconds;
    var breaker = new UpstreamCircuitBreaker();
    breaker.config = config;
    return breaker;
  }

  @Test
  void closedByDefault() {
    var breaker = newBreaker(3, 10);
    assertFalse(breaker.isOpen("inventory-svc"));
  }

  @Test
  void opensOnlyAfterReachingTheFailureThreshold() {
    var breaker = newBreaker(3, 10);
    breaker.recordFailure("inventory-svc");
    breaker.recordFailure("inventory-svc");
    assertFalse(breaker.isOpen("inventory-svc"));

    breaker.recordFailure("inventory-svc");
    assertTrue(breaker.isOpen("inventory-svc"));
  }

  @Test
  void successResetsTheConsecutiveFailureCount() {
    var breaker = newBreaker(3, 10);
    breaker.recordFailure("inventory-svc");
    breaker.recordFailure("inventory-svc");
    breaker.recordSuccess("inventory-svc");

    // Only 2 consecutive failures since the reset — must not have tripped.
    breaker.recordFailure("inventory-svc");
    breaker.recordFailure("inventory-svc");
    assertFalse(breaker.isOpen("inventory-svc"));
  }

  @Test
  void eachServicesCircuitIsTrackedIndependently() {
    var breaker = newBreaker(2, 10);
    breaker.recordFailure("inventory-svc");
    breaker.recordFailure("inventory-svc");

    assertTrue(breaker.isOpen("inventory-svc"));
    assertFalse(breaker.isOpen("payment-svc"));
  }
}
