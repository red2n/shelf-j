package com.shelfj.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Verifies the central-config wiring: unset URL is a no-op (local dev / tests unaffected), a
 * reachable config-svc layers its values (authenticated with the internal token), and a 404 or an
 * unreachable service degrades to empty so the host still boots on its local defaults.
 */
class ConfigServiceConfigSourceTest {

  private static final String TOKEN = "unit-test-token-000000000000000000";

  private HttpServer startStub(int status, String body, Consumer<String> tokenSink)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config/",
        exchange -> {
          if (tokenSink != null) {
            tokenSink.accept(exchange.getRequestHeaders().getFirst("X-Config-Token"));
          }
          byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
          try (var os = exchange.getResponseBody()) {
            os.write(out);
          }
        });
    server.start();
    return server;
  }

  /** Build the source with test bootstrap props set (and cleared straight after construction). */
  private ConfigServiceConfigSource build(String url) {
    System.setProperty("shelfj.service.name", "test-svc");
    System.setProperty("shelfj.config.token", TOKEN);
    if (url != null) System.setProperty("shelfj.config.url", url);
    try {
      return new ConfigServiceConfigSource();
    } finally {
      System.clearProperty("shelfj.config.url");
      System.clearProperty("shelfj.service.name");
      System.clearProperty("shelfj.config.token");
    }
  }

  @Test
  void unsetUrlIsANoOpSource() {
    ConfigServiceConfigSource src = build(null);
    assertTrue(src.getProperties().isEmpty(), "no URL configured → empty source");
    assertEquals("shelfj-config-service", src.getName());
    assertEquals(150, src.getOrdinal(), "must sit above the local file (100), below env (300)");
  }

  @Test
  void layersRemoteConfigAndSendsTheInternalToken() throws Exception {
    AtomicReference<String> seenToken = new AtomicReference<>();
    HttpServer stub =
        startStub(
            200,
            "{\"data\":{\"shelfj.demo.key\":\"from-config-svc\","
                + "\"shelfj.order.pending-sweeper.ttl-hours\":\"48\"},\"error\":null,\"meta\":{}}",
            seenToken::set);
    try {
      var src = build("http://127.0.0.1:" + stub.getAddress().getPort());
      assertEquals("from-config-svc", src.getValue("shelfj.demo.key"));
      assertEquals("48", src.getValue("shelfj.order.pending-sweeper.ttl-hours"));
      assertTrue(src.getPropertyNames().contains("shelfj.demo.key"));
      assertEquals(TOKEN, seenToken.get(), "config-svc is called with the internal X-Config-Token");
    } finally {
      stub.stop(0);
    }
  }

  @Test
  void noConfigForServiceDegradesToEmpty() throws Exception {
    HttpServer stub = startStub(404, "{\"error\":{\"code\":\"CONFIG_NOT_FOUND\"}}", null);
    try {
      var src = build("http://127.0.0.1:" + stub.getAddress().getPort());
      assertTrue(src.getProperties().isEmpty(), "404 → boot on local defaults");
    } finally {
      stub.stop(0);
    }
  }

  @Test
  void unreachableConfigSvcDegradesToEmpty() {
    // Nothing is listening here → connect failure → empty source (service still starts).
    var src = build("http://127.0.0.1:1");
    assertTrue(src.getProperties().isEmpty(), "unreachable config-svc → boot on local defaults");
  }
}
