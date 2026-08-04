package com.shelfj.service;

import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Fail-open facade over Redis for cache-aside reads.
 *
 * <p>A cache is an optimisation, not a source of truth: Postgres is. So a Redis outage must cost
 * latency, never correctness or availability. Calling {@link RedisCommands} directly does the
 * opposite — Lettuce throws {@link RedisException} on a dead or hung connection, and an unguarded
 * {@code redis.get} in a read path turns a cache outage into a 500 on the service's highest-traffic
 * endpoint. Every cache access goes through here so that cannot happen.
 *
 * <p>Two distinct failures are handled:
 *
 * <ul>
 *   <li><b>Redis dies while running</b> — commands throw, each method swallows and reports a miss.
 *       Lettuce reconnects underneath, so recovery needs no intervention.
 *   <li><b>Redis is down when the service starts</b> — the producer's {@code connect()} throws, so
 *       resolving the bean at all fails. Resolution is therefore lazy and retried on a backoff,
 *       rather than injected eagerly; without the backoff every request would pay a fresh connect
 *       attempt while Redis was away.
 * </ul>
 *
 * <p>Deliberately not covered by a readiness probe: fail-open is what makes Redis genuinely
 * optional, and a service that still serves every request correctly from Postgres is ready.
 */
@ApplicationScoped
public class RedisCache {

  private static final Logger LOG = System.getLogger(RedisCache.class.getName());

  /** How long to stop trying to obtain a connection after an attempt fails. */
  private static final long RECONNECT_BACKOFF_MS = 10_000;

  @Inject Instance<RedisCommands<String, String>> commandsSource;

  private volatile RedisCommands<String, String> commands;
  private volatile long nextAttemptAtMillis;

  /**
   * Reads a cached value.
   *
   * @return the cached value, or null on a miss — and on any Redis failure, which is reported as a
   *     miss so the caller falls through to its own source of truth
   */
  public String get(String key) {
    RedisCommands<String, String> redis = commands();
    if (redis == null) return null;
    try {
      return redis.get(key);
    } catch (RedisException e) {
      degraded("read", key, e);
      return null;
    }
  }

  /** Caches {@code value} under {@code key} for {@code ttlSeconds}. A failure is not fatal. */
  public void put(String key, String value, long ttlSeconds) {
    RedisCommands<String, String> redis = commands();
    if (redis == null) return;
    try {
      redis.set(key, value, SetArgs.Builder.ex(ttlSeconds));
    } catch (RedisException e) {
      degraded("write", key, e);
    }
  }

  /**
   * Evicts {@code keys}.
   *
   * <p>A failed eviction is the one case that costs correctness rather than latency: the stale
   * entry survives until its TTL expires. Logged at WARNING for that reason — TTLs on cached
   * entries are what bound the damage, so cache-aside entries must always carry one.
   */
  public void evict(String... keys) {
    RedisCommands<String, String> redis = commands();
    if (redis == null) return;
    try {
      redis.del(keys);
    } catch (RedisException e) {
      degraded("evict", String.join(",", keys), e);
    }
  }

  /**
   * The live commands handle, or null when Redis cannot currently be reached.
   *
   * <p>Once obtained the handle is kept: Lettuce reconnects transparently underneath it, so a later
   * outage surfaces as a thrown command rather than a null here.
   */
  private RedisCommands<String, String> commands() {
    RedisCommands<String, String> existing = commands;
    if (existing != null) return existing;

    long now = System.currentTimeMillis();
    if (now < nextAttemptAtMillis) return null;

    try {
      RedisCommands<String, String> resolved = commandsSource.get();
      commands = resolved;
      LOG.log(Level.INFO, "Redis cache connected");
      return resolved;
    } catch (RuntimeException e) {
      nextAttemptAtMillis = now + RECONNECT_BACKOFF_MS;
      LOG.log(
          Level.WARNING,
          "Redis unreachable — serving uncached for the next {0}ms: {1}",
          RECONNECT_BACKOFF_MS,
          e.toString());
      return null;
    }
  }

  private static void degraded(String operation, String key, RedisException e) {
    LOG.log(Level.WARNING, "Redis {0} failed for key {1}: {2}", operation, key, e.toString());
  }
}
