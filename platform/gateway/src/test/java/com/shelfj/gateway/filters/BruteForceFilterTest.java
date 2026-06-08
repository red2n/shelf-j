package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BruteForceProtectionServiceTest {

  @Test
  void shouldBlockAfterMaxFailures() {
    BruteForceProtectionService protection =
        new BruteForceProtectionService(3, Duration.ofMinutes(10));
    String key = "user:bob";

    assertFalse(protection.isBlocked(key));
    protection.recordFailure(key);
    assertFalse(protection.isBlocked(key));
    protection.recordFailure(key);
    assertFalse(protection.isBlocked(key));
    protection.recordFailure(key);
    assertTrue(protection.isBlocked(key));
  }

  @Test
  void shouldResetAfterSuccessfulLogin() {
    BruteForceProtectionService protection =
        new BruteForceProtectionService(3, Duration.ofMinutes(10));
    String key = "user:bob";

    protection.recordFailure(key);
    protection.recordFailure(key);
    protection.recordSuccess(key);

    assertFalse(protection.isBlocked(key));
  }
}
