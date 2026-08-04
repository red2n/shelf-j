package com.shelfj.gateway.filters;

import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;

/**
 * Failure/block counters live in Redis — shared across every gateway replica — instead of
 * per-instance heap, so a distributed login flood can't dodge the lockout by hitting a different
 * replica, and a gateway restart doesn't quietly reset every attacker's strike count.
 */
public class BruteForceProtectionService {

  private static final Logger LOG = System.getLogger(BruteForceProtectionService.class.getName());

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
    try {
      String failKey = failKey(key);
      long failures = redis.incr(failKey);
      if (failures == 1) {
        redis.expire(failKey, expiryDurationSeconds);
      }
      if (failures >= maxFailures) {
        redis.set(blockKey(key), "1", SetArgs.Builder.ex(blockDurationSeconds));
        redis.del(failKey);
      }
    } catch (RedisException e) {
      degraded("record failure", e);
    }
  }

  public void recordSuccess(String key) {
    if (key == null) {
      return;
    }
    try {
      redis.del(failKey(key), blockKey(key));
    } catch (RedisException e) {
      degraded("clear counters", e);
    }
  }

  /**
   * Whether {@code key} is currently locked out.
   *
   * <p>Fails <b>open</b> when Redis cannot answer: an unreachable counter store reports "not
   * blocked" rather than "blocked".
   *
   * <p>This is a deliberate trade-off and the reverse is defensible. Failing closed would deny
   * every login for as long as Redis is away — a total authentication outage for all tenants,
   * triggered by a cache going down, and trivially weaponised by anyone able to disturb Redis.
   * Failing open costs a window in which credential-stuffing is unthrottled at the gateway, but an
   * attacker still needs valid credentials: iam-svc verifies every password regardless, so this
   * control is defence-in-depth rather than the thing standing between an attacker and an account.
   *
   * <p>Logged at WARNING so the window is visible. If a deployment's threat model prefers the other
   * trade, return true here instead — that is the only line that needs to change.
   */
  public boolean isBlocked(String key) {
    if (key == null) {
      return false;
    }
    try {
      return redis.exists(blockKey(key)) > 0;
    } catch (RedisException e) {
      degraded("check block", e);
      return false;
    }
  }

  private static void degraded(String operation, RedisException e) {
    LOG.log(
        Level.WARNING,
        "Brute-force protection degraded — could not {0}, allowing the attempt: {1}",
        operation,
        e.toString());
  }

  private static String failKey(String key) {
    return "bruteforce:fail:" + key;
  }

  private static String blockKey(String key) {
    return "bruteforce:block:" + key;
  }
}
