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
 * A stand-in for tenant-svc's {@code GET /admin/tenant}, {@code GET /admin/tenant/obligations} and
 * {@code GET /admin/stores} in a service's integration tests, where discovery is off. Each tenant
 * answers with the currency and country it was registered with; an unregistered tenant is {@code
 * 404}, so a test that forgets to register one sees the refusal a real unknown tenant would get
 * rather than a borrowed default.
 *
 * <p>Start it in the test's static initialiser, before Helidon boots: it points {@code
 * shelfj.clients.tenant-svc.url} at itself.
 */
public final class TenantSvcStub implements AutoCloseable {

  private final HttpServer server;
  private final Map<String, String> profiles = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> obligations = new ConcurrentHashMap<>();
  private final Map<String, java.util.List<String>> stores = new ConcurrentHashMap<>();
  private final java.util.Map<String, String> retention =
      new java.util.concurrent.ConcurrentHashMap<>();
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
      // A tenant's retention schedule (21.16), as registered; a tenant with none has an empty one.
      server.createContext(
          "/admin/tenant/retention",
          exchange -> {
            stub.requests.incrementAndGet();
            String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
            String body = tenant == null ? null : stub.retention.get(tenant);
            reply(
                exchange,
                200,
                body != null
                    ? body
                    : "{\"data\":{\"country\":\"GB\",\"countries\":[\"GB\"],\"classes\":[],"
                        + "\"holds\":[]}}");
          });
      // A tenant's stores, one page; a tenant with none registered has none.
      server.createContext(
          "/admin/stores",
          exchange -> {
            String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
            reply(
                exchange,
                200,
                "{\"data\":["
                    + String.join(
                        ",",
                        tenant == null
                            ? java.util.List.of()
                            : stub.stores.getOrDefault(tenant, java.util.List.of()))
                    + "],\"meta\":{\"nextCursor\":null}}");
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
   * Gives a registered tenant the e-invoicing identity {@code GET /admin/tenant} returns: its VAT
   * identifier and its Peppol participant identifier. Nulls leave a field out.
   */
  public TenantSvcStub withIdentity(
      String tenantId, String vatNumber, String einvoiceScheme, String einvoiceId) {
    profiles.computeIfPresent(
        tenantId,
        (id, json) ->
            json.substring(0, json.length() - 2)
                + field("vatNumber", vatNumber)
                + field("einvoiceScheme", einvoiceScheme)
                + field("einvoiceId", einvoiceId)
                + "}}");
    return this;
  }

  private static String field(String name, String value) {
    return value == null ? "" : ",\"" + name + "\":\"" + value + "\"";
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

  /**
   * Registers a tenant's retention schedule (21.16), as tenant-svc's {@code GET
   * /admin/tenant/retention} answers it.
   *
   * @param periods the period set per class, e.g. {@code "ORDER_PERSONAL_DATA", 0}; a class not
   *     named has no period, so nothing of it is purged
   * @param holds hold objects as the sheet lists them, e.g. {@code
   *     {"subjectKind":"CUSTOMER","subjectId":"…"}}
   */
  public TenantSvcStub withRetention(
      String tenantId, java.util.Map<String, Integer> periods, java.util.List<String> holds) {
    StringBuilder classes = new StringBuilder();
    for (var e : periods.entrySet()) {
      if (classes.length() > 0) classes.append(',');
      classes
          .append("{\"code\":\"")
          .append(e.getKey())
          .append("\",\"periodDays\":")
          .append(e.getValue())
          .append('}');
    }
    retention.put(
        tenantId,
        "{\"data\":{\"country\":\"GB\",\"countries\":[\"GB\"],\"classes\":["
            + classes
            + "],\"holds\":["
            + String.join(",", holds)
            + "]}}");
    return this;
  }

  /**
   * Registers one of a tenant's stores, as tenant-svc's {@code GET /admin/stores} lists it.
   *
   * @param country the store's country, or null when it records none
   */
  public TenantSvcStub withStore(String tenantId, String storeId, String country) {
    stores
        .computeIfAbsent(tenantId, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"id\":\""
                + storeId
                + "\",\"country\":"
                + (country == null ? "null" : "\"" + country + "\"")
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
