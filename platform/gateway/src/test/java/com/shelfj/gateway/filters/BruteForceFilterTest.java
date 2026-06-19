package com.shelfj.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.test.RedisSupport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Counters now live in Redis (shared across gateway replicas) instead of gateway heap, so these
 * tests run against a real Redis container rather than mocking the storage layer.
 */
class BruteForceProtectionServiceTest {

  private static RedisSupport REDIS;
  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  private BruteForceProtectionService protection;

  @BeforeAll
  static void startRedis() {
    REDIS = RedisSupport.start();
    client = RedisClient.create(RedisURI.Builder.redis(REDIS.host(), REDIS.port()).build());
    connection = client.connect();
  }

  @AfterAll
  static void stopRedis() {
    connection.close();
    client.shutdown();
    REDIS.stop();
  }

  @BeforeEach
  void setUp() {
    connection.sync().flushall();
    protection = new BruteForceProtectionService(connection.sync(), 3, Duration.ofMinutes(10));
  }

  @AfterEach
  void cleanUp() {
    connection.sync().flushall();
  }

  @Test
  void shouldBlockAfterMaxFailures() {
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
    String key = "user:bob";

    protection.recordFailure(key);
    protection.recordFailure(key);
    protection.recordSuccess(key);

    assertFalse(protection.isBlocked(key));
  }

  @Test
  void distinctKeysAreTrackedIndependently() {
    protection.recordFailure("user:alice");
    protection.recordFailure("user:alice");
    protection.recordFailure("user:alice");

    assertTrue(protection.isBlocked("user:alice"));
    assertFalse(protection.isBlocked("user:carol"));
  }

  @Test
  void stateIsSharedAcrossInstancesViaRedis() {
    // The whole point of the fix: a second service instance (i.e. a second gateway replica) must
    // see the same block, because it lives in Redis rather than per-instance heap.
    BruteForceProtectionService secondReplica =
        new BruteForceProtectionService(connection.sync(), 3, Duration.ofMinutes(10));
    String key = "user:dave";

    protection.recordFailure(key);
    secondReplica.recordFailure(key);
    secondReplica.recordFailure(key);

    assertTrue(secondReplica.isBlocked(key));
    assertTrue(protection.isBlocked(key));
  }
}
