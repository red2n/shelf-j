package com.shelfj.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One shared Redis connection for services that cache hot reads (cart-svc, product-svc, ...).
 * Lazily produced — CDI only invokes {@link #redisCommands()} when something actually injects
 * {@code RedisCommands<String, String>}, so services that don't cache anything never connect.
 */
@ApplicationScoped
public class RedisClientProducer {

  /** Redis host. Property: {@code shelfj.redis.host}. */
  @Inject
  @ConfigProperty(name = "shelfj.redis.host", defaultValue = "localhost")
  String redisHost;

  /** Redis port. Property: {@code shelfj.redis.port}. */
  @Inject
  @ConfigProperty(name = "shelfj.redis.port", defaultValue = "6379")
  int redisPort;

  // Optional rather than a String with defaultValue="" - Helidon's config validation treats an
  // empty-string defaultValue as "no value found" and fails CDI deployment. Optional is the
  // correct MicroProfile Config idiom for a property that may legitimately be absent (e.g. the
  // no-auth Redis Testcontainer used by RedisSupport in integration tests).
  /**
   * Redis auth password, if the target instance requires one. Property: {@code
   * shelfj.redis.password}. Empty/absent connects without {@code AUTH} — normal for the no-auth
   * Testcontainer used in integration tests.
   */
  @Inject
  @ConfigProperty(name = "shelfj.redis.password")
  Optional<String> redisPassword;

  private RedisClient client;
  private StatefulRedisConnection<String, String> connection;

  /**
   * Builds the CDI-managed {@link RedisCommands} bean. Called lazily by CDI, only when some other
   * bean actually injects {@code RedisCommands<String, String>}.
   *
   * @return synchronous commands over one connection to {@link #redisHost}:{@link #redisPort},
   *     authenticated with {@link #redisPassword} if present and non-blank
   */
  /**
   * Command timeout for every cache operation.
   *
   * <p>Lettuce defaults to 60 seconds, which is catastrophic for a cache: a hung Redis — worse than
   * a dead one, because nothing fails fast — would pin a request thread for a minute per call while
   * the answer sits available in Postgres. A cache lookup that has not returned in this long has
   * already cost more than the query it was meant to save, so give up and read through.
   */
  private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(250);

  @Produces
  @ApplicationScoped
  public RedisCommands<String, String> redisCommands() {
    RedisURI.Builder uriBuilder =
        RedisURI.Builder.redis(redisHost, redisPort).withTimeout(COMMAND_TIMEOUT);
    if (redisPassword.isPresent() && !redisPassword.get().isBlank()) {
      uriBuilder.withPassword(redisPassword.get().toCharArray());
    }
    client = RedisClient.create(uriBuilder.build());
    connection = client.connect();
    return connection.sync();
  }

  /**
   * Closes the connection and shuts down the client on application shutdown.
   *
   * @param commands the bean produced by {@link #redisCommands()} (unused directly — closes the
   *     underlying connection/client fields instead)
   */
  void close(@Disposes RedisCommands<String, String> commands) {
    if (connection != null) {
      connection.close();
    }
    if (client != null) {
      client.shutdown();
    }
  }
}
