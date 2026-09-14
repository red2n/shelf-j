package com.shelfj.test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for tenant-svc's {@code GET /admin/tenant} and {@code GET /admin/tenant/obligations}
 * in a service's integration tests, where discovery is off. Each tenant answers with the currency
 * and country it was registered with; an unregistered tenant is {@code 404}, so a test that forgets
 * to register one sees the refusal a real unknown tenant would get rather than a borrowed default.
 *
 * <p>Start it in the test's static initialiser, before Helidon boots: it points {@code
 * shelfj.clients.tenant-svc.url} at itself.
 */
public final class TenantSvcStub implements AutoCloseable {

  private final HttpServer server;
  private final Map<String, String> profiles = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> obligations = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicInteger requests =
      new java.util.concurrent.atomic.AtomicInteger();

  private TenantSvcStub(HttpServer server) {
    this.server = server;
  }

  /** Starts the stub on a free local port and points the client property at it. */
  public static TenantSvcStub start() {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      TenantSvcStub stub = new TenantSvcStub(server);
      server.createContext(
          "/admin/tenant",
          exchange -> {
            stub.requests.incrementAndGet();
            String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
            String body = tenant == null ? null : stub.profiles.get(tenant);
            reply(
                exchange,
                body == null ? 404 : 200,
                body == null
                    ? "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"no such tenant\"}}"
                    : body);
          });
      // The longer context wins, so the obligations route is not answered as a profile.
      server.createContext(
          "/admin/tenant/obligations",
          exchange -> {
            stub.requests.incrementAndGet();
            String query = exchange.getRequestURI().getRawQuery();
            String country = "";
            for (String pair : query == null ? new String[0] : query.split("&")) {
              if (pair.startsWith("country=")) country = pair.substring(8);
            }
            reply(
                exchange,
                200,
                "{\"data\":{\"country\":\""
                    + country
                    + "\",\"obligations\":["
                    + String.join(",", stub.obligations.getOrDefault(country, java.util.List.of()))
                    + "]}}");
          });
      server.setExecutor(
          java.util.concurrent.Executors.newCachedThreadPool(
              r -> {
                Thread t = new Thread(r, "tenant-svc-stub");
                t.setDaemon(true);
                return t;
              }));
      server.start();
      System.setProperty(
          "shelfj.clients.tenant-svc.url",
          "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
      return stub;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Registers a tenant's declared currency and country. */
  public TenantSvcStub with(String tenantId, String currency, String country) {
    profiles.put(
        tenantId,
        "{\"data\":{\"id\":\""
            + tenantId
            + "\",\"currency\":\""
            + currency
            + "\",\"country\":\""
            + country
            + "\",\"status\":\"ACTIVE\"}}");
    return this;
  }

  /**
   * Registers an obligation that reaches a country, as tenant-svc's {@code GET
   * /admin/tenant/obligations} lists it.
   *
   * @param effectiveTo the last day it applies, or null while it still does
   */
  public TenantSvcStub withObligation(
      String country, String code, String scope, String effectiveFrom, String effectiveTo) {
    obligations
        .computeIfAbsent(country, c -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"code\":\""
                + code
                + "\",\"scope\":\""
                + scope
                + "\",\"effectiveFrom\":\""
                + effectiveFrom
                + "\""
                + (effectiveTo == null ? "" : ",\"effectiveTo\":\"" + effectiveTo + "\"")
                + "}");
    return this;
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** How many profile reads have reached the stub, to show a cache holding. */
  public int requests() {
    return requests.get();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
