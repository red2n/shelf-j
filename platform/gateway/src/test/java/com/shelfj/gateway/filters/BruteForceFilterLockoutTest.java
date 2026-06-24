package com.shelfj.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.gateway.GatewayConfig;
import com.shelfj.test.RedisSupport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Drives the full lockout loop: failed logins must actually block the next attempt. Counters live
 * in Redis now, so this runs against a real Redis container rather than mocking the storage layer.
 */
@ExtendWith(MockitoExtension.class)
class BruteForceFilterLockoutTest {

  private static final String LOGIN_BODY = "{\"email\":\"bob@example.com\",\"password\":\"x\"}";

  private static RedisSupport REDIS;
  private static RedisClient client;
  private static StatefulRedisConnection<String, String> connection;

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext request;
  @Mock ContainerResponseContext response;
  @Mock UriInfo uriInfo;

  private BruteForceFilter filter;
  private final Map<String, Object> props = new HashMap<>();

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

  @AfterEach
  void cleanUp() {
    connection.sync().flushall();
  }

  @BeforeEach
  void setUp() throws IOException {
    when(config.bruteForceEnabled()).thenReturn(true);
    when(config.bruteForceMaxFailures()).thenReturn(2);
    when(config.bruteForceBlockMinutes()).thenReturn(15);
    lenient().when(config.bruteForceLoginPath()).thenReturn("/auth/login");
    lenient().when(config.trustForwardedHeaders()).thenReturn(false);

    filter = new BruteForceFilter();
    filter.config = config;
    filter.redis = connection.sync();
    filter.init();

    lenient().when(request.getUriInfo()).thenReturn(uriInfo);
    lenient().when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/login");
    lenient().when(request.getMethod()).thenReturn("POST");

    // back properties with a real map so request+response phases share state
    lenient()
        .doAnswer(
            inv -> {
              props.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(request)
        .setProperty(anyString(), any());
    lenient()
        .when(request.getProperty(anyString()))
        .thenAnswer(inv -> props.get(inv.getArgument(0, String.class)));
  }

  private void freshBody() {
    InputStream body = new ByteArrayInputStream(LOGIN_BODY.getBytes(StandardCharsets.UTF_8));
    lenient().when(request.getEntityStream()).thenReturn(body);
  }

  @Test
  void failedLoginsLockTheAccountOut() throws IOException {
    when(response.getStatus()).thenReturn(401);

    for (int i = 0; i < 2; i++) {
      freshBody();
      filter.filter(request); // request phase — not blocked yet
      filter.filter(request, response); // response phase — records the 401
    }
    verify(request, never()).abortWith(any());

    freshBody();
    filter.filter(request);

    verify(request).abortWith(any());
  }

  @Test
  void rbacDeniedNonLoginAuthPathsDoNotFeedTheCounter() throws IOException {
    // A 403 from e.g. the POS session sweep is an authorization result, not a failed credential
    // attempt — repeated calls must never lock the client's IP out of login.
    lenient().when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/pos/sessions/sweep");
    lenient().when(response.getStatus()).thenReturn(403);

    for (int i = 0; i < 5; i++) {
      freshBody();
      filter.filter(request);
      filter.filter(request, response);
    }

    verify(request, never()).abortWith(any());
  }

  @Test
  void successfulLoginResetsTheCounter() throws IOException {
    when(response.getStatus()).thenReturn(401, 200);

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 401 — one failure

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 200 — reset

    freshBody();
    filter.filter(request);
    filter.filter(request, response); // 401 again — still under the limit

    freshBody();
    filter.filter(request);

    verify(request, never()).abortWith(any());
  }
}
