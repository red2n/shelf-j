package com.shelfj.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One shared Redis connection for services that cache hot reads (cart-svc, product-svc, ...).
 * Lazily produced — CDI only invokes {@link #redisCommands()} when something actually injects
 * {@code RedisCommands<String, String>}, so services that don't cache anything never connect.
 */
@ApplicationScoped
public class RedisClientProducer {

  @Inject
  @ConfigProperty(name = "shelfj.redis.host", defaultValue = "localhost")
  String redisHost;

  @Inject
  @ConfigProperty(name = "shelfj.redis.port", defaultValue = "6379")
  int redisPort;

  // Optional rather than a String with defaultValue="" - Helidon's config validation treats an
  // empty-string defaultValue as "no value found" and fails CDI deployment. Optional is the
  // correct MicroProfile Config idiom for a property that may legitimately be absent (e.g. the
  // no-auth Redis Testcontainer used by RedisSupport in integration tests).
  @Inject
  @ConfigProperty(name = "shelfj.redis.password")
  Optional<String> redisPassword;

  private RedisClient client;
  private StatefulRedisConnection<String, String> connection;

  @Produces
  @ApplicationScoped
  public RedisCommands<String, String> redisCommands() {
    RedisURI.Builder uriBuilder = RedisURI.Builder.redis(redisHost, redisPort);
    if (redisPassword.isPresent() && !redisPassword.get().isBlank()) {
      uriBuilder.withPassword(redisPassword.get().toCharArray());
    }
    client = RedisClient.create(uriBuilder.build());
    connection = client.connect();
    return connection.sync();
  }

  void close(@Disposes RedisCommands<String, String> commands) {
    if (connection != null) {
      connection.close();
    }
    if (client != null) {
      client.shutdown();
    }
  }
}
