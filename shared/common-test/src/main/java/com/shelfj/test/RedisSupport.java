package com.shelfj.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Reusable Redis Testcontainer support for service integration tests (docs/ARCHITECTURE.md §16).
 * Same shape as {@link PostgresSupport}: a real Redis in a container, no mocks, so
 * distributed-counter behaviour (atomic INCR+EXPIRE, key TTL/expiry) is verified against the real
 * thing.
 */
public final class RedisSupport implements AutoCloseable {

  private final GenericContainer<?> container;

  private RedisSupport(GenericContainer<?> container) {
    this.container = container;
  }

  /** Start a Redis 7 container (no password — auth is not what these tests exercise). */
  public static RedisSupport start() {
    @SuppressWarnings("resource")
    GenericContainer<?> c =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    c.start();
    return new RedisSupport(c);
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(6379);
  }

  public void stop() {
    container.stop();
  }

  @Override
  public void close() {
    stop();
  }
}
