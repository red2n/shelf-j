package com.shelfj.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The point of {@link RedisCache} is that a Redis outage costs latency and nothing else, so these
 * tests are all about what happens when Redis is unavailable. Hand-rolled JDK proxies rather than a
 * mocking framework — {@code RedisCommands} has hundreds of methods and common-service has no
 * Mockito dependency.
 */
class RedisCacheTest {

  /** A commands handle where every operation fails, as it would against a dead connection. */
  @SuppressWarnings("unchecked")
  private static RedisCommands<String, String> failingCommands() {
    return (RedisCommands<String, String>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> {
              throw new RedisConnectionException("redis is down");
            });
  }

  /** A CDI {@code Instance} whose resolution is driven by {@code supplier}, counting attempts. */
  @SuppressWarnings("unchecked")
  private static Instance<RedisCommands<String, String>> resolvingTo(
      Supplier<RedisCommands<String, String>> supplier, AtomicInteger resolutions) {
    return (Instance<RedisCommands<String, String>>)
        Proxy.newProxyInstance(
            Instance.class.getClassLoader(),
            new Class<?>[] {Instance.class},
            (proxy, method, args) -> {
              if ("get".equals(method.getName())) {
                resolutions.incrementAndGet();
                return supplier.get();
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static RedisCache cacheBackedBy(
      Supplier<RedisCommands<String, String>> supplier, AtomicInteger resolutions) {
    RedisCache cache = new RedisCache();
    cache.commandsSource = resolvingTo(supplier, resolutions);
    return cache;
  }

  @Test
  void readReportsAMissWhenTheCommandFails() {
    RedisCache cache = cacheBackedBy(RedisCacheTest::failingCommands, new AtomicInteger());
    // A miss, not an exception — the caller falls through to Postgres.
    assertNull(cache.get("product:t:1"));
  }

  @Test
  void writeAndEvictSwallowFailures() {
    RedisCache cache = cacheBackedBy(RedisCacheTest::failingCommands, new AtomicInteger());
    assertDoesNotThrow(() -> cache.put("product:t:1", "payload", 300));
    assertDoesNotThrow(() -> cache.evict("product:t:1"));
    assertDoesNotThrow(() -> cache.evict("a", "b"));
  }

  @Test
  void unreachableRedisAtStartupIsAMissRatherThanAFailure() {
    RedisCache cache =
        cacheBackedBy(
            () -> {
              throw new RedisConnectionException("cannot connect");
            },
            new AtomicInteger());
    assertNull(cache.get("product:t:1"));
    assertDoesNotThrow(() -> cache.put("product:t:1", "payload", 300));
  }

  @Test
  void aFailedConnectionIsNotRetriedOnEveryCall() {
    AtomicInteger resolutions = new AtomicInteger();
    RedisCache cache =
        cacheBackedBy(
            () -> {
              throw new RedisConnectionException("cannot connect");
            },
            resolutions);

    for (int i = 0; i < 25; i++) {
      cache.get("product:t:" + i);
    }

    // Without the backoff every request would pay a fresh connect attempt while Redis was away,
    // which is slower than having no cache at all.
    assertEquals(1, resolutions.get());
  }

  @Test
  void aResolvedConnectionIsReusedRatherThanResolvedPerCall() {
    AtomicInteger resolutions = new AtomicInteger();
    RedisCache cache = cacheBackedBy(RedisCacheTest::failingCommands, resolutions);

    for (int i = 0; i < 10; i++) {
      cache.get("product:t:" + i);
    }

    // Lettuce reconnects underneath a live handle, so it is obtained once and kept.
    assertEquals(1, resolutions.get());
  }
}
