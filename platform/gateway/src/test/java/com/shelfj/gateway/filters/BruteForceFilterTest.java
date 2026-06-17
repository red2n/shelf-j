package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  @Test
  void capEvictsTheLeastRecentlyActiveEntryNotAnArbitraryOne() {
    BruteForceProtectionService protection =
        new BruteForceProtectionService(3, Duration.ofMinutes(10));

    String firstKey = "user:0";
    for (int i = 0; i <= BruteForceProtectionService.MAX_ENTRIES; i++) {
      protection.recordFailure("user:" + i);
    }
    String lastKey = "user:" + BruteForceProtectionService.MAX_ENTRIES;

    assertEquals(BruteForceProtectionService.MAX_ENTRIES, protection.stateByKey.size());
    assertFalse(
        protection.stateByKey.containsKey(firstKey), "oldest entry should have been evicted");
    assertTrue(protection.stateByKey.containsKey(lastKey), "newest entry should be kept");
  }
}
