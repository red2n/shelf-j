package com.shelfj.gateway.filters;

import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;

/**
 * Failure/block counters live in Redis — shared across every gateway replica — instead of
 * per-instance heap, so a distributed login flood can't dodge the lockout by hitting a different
 * replica, and a gateway restart doesn't quietly reset every attacker's strike count.
 */
public class BruteForceProtectionService {

  private final RedisCommands<String, String> redis;
  private final int maxFailures;
  private final long blockDurationSeconds;
  private final long expiryDurationSeconds = Duration.ofMinutes(30).toSeconds();

  public BruteForceProtectionService(RedisCommands<String, String> redis) {
    this(redis, 5, Duration.ofMinutes(15));
  }

  public BruteForceProtectionService(
      RedisCommands<String, String> redis, int maxFailures, Duration blockDuration) {
    this.redis = redis;
    this.maxFailures = Math.max(1, maxFailures);
    this.blockDurationSeconds = Math.max(1, blockDuration.toSeconds());
  }

  public void recordFailure(String key) {
    if (key == null) {
      return;
    }
    String failKey = failKey(key);
    long failures = redis.incr(failKey);
    if (failures == 1) {
      redis.expire(failKey, expiryDurationSeconds);
    }
    if (failures >= maxFailures) {
      redis.set(blockKey(key), "1", SetArgs.Builder.ex(blockDurationSeconds));
      redis.del(failKey);
    }
  }

  public void recordSuccess(String key) {
    if (key == null) {
      return;
    }
    redis.del(failKey(key), blockKey(key));
  }

  public boolean isBlocked(String key) {
    if (key == null) {
      return false;
    }
    return redis.exists(blockKey(key)) > 0;
  }

  private static String failKey(String key) {
    return "bruteforce:fail:" + key;
  }

  private static String blockKey(String key) {
    return "bruteforce:block:" + key;
  }
}
