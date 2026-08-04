package com.shelfj.gateway.filters;

import com.shelfj.gateway.GatewayConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.time.Duration;

/**
 * One shared Redis connection for the gateway's rate-limit and brute-force counters — state that
 * must be visible across every gateway replica, not held in per-instance heap.
 */
@ApplicationScoped
public class RedisClientProducer {

  @Inject GatewayConfig config;

  private RedisClient client;
  private StatefulRedisConnection<String, String> connection;

  /**
   * Command timeout for the rate-limit and brute-force counters.
   *
   * <p>Lettuce defaults to 60 seconds. On the gateway that is the whole platform's availability:
   * every request passes through {@link RateLimitFilter} before reaching any upstream, so a hung
   * Redis would pin each request thread for a minute and the public door would stop answering
   * entirely. Fail fast instead and let the filters decide what to do without the counter.
   */
  private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(250);

  @Produces
  @ApplicationScoped
  public RedisCommands<String, String> redisCommands() {
    RedisURI uri =
        RedisURI.Builder.redis(config.redisHost(), config.redisPort())
            .withPassword(config.redisPassword().toCharArray())
            .withTimeout(COMMAND_TIMEOUT)
            .build();
    client = RedisClient.create(uri);
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
