package com.shelfj.test;

import com.sun.net.httpserver.HttpServer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for tenant-svc's {@code GET /admin/tenant}, {@code GET /admin/tenant/obligations},
 * {@code GET /admin/stores} and {@code GET /admin/stores/{id}} in a service's integration tests,
 * where discovery is off. Each tenant answers with the currency and country it was registered with;
 * an unregistered tenant is {@code 404}, so a test that forgets to register one sees the refusal a
 * real unknown tenant would get rather than a borrowed default.
 *
 * <p>Start it in the test's static initialiser, before Helidon boots: it points {@code
 * shelfj.clients.tenant-svc.url} at itself.
 */
public final class TenantSvcStub implements AutoCloseable {

  private static final String STORES = "/admin/stores";

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
    HttpServer server = JsonStub.serve("tenant-svc-stub");
    TenantSvcStub stub = new TenantSvcStub(server);
    server.createContext(
        "/admin/tenant",
        exchange -> {
          stub.requests.incrementAndGet();
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          String body = tenant == null ? null : stub.profiles.get(tenant);
          JsonStub.reply(
              exchange,
              body == null ? 404 : 200,
              body == null
                  ? "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"no such tenant\"}}"
                  : body);
        });
    // Which business holds an e-invoicing address (07.13, the transport seam): tenant-svc's
    // platform-wide lookup, answered from the identities registered here. By scheme and id, else
    // by VAT number; 404 when none holds it, 409 when more than one does.
    server.createContext(
        "/platform/tenants/by-einvoice-address",
        exchange -> {
          stub.requests.incrementAndGet();
          Map<String, String> q = JsonStub.query(exchange.getRequestURI().getRawQuery());
          String scheme = q.get("scheme");
          String id = q.get("id");
          String vat = q.get("vatNumber");
          java.util.List<String> holders =
              stub.profiles.values().stream()
                  .filter(
                      json ->
                          scheme != null
                              ? holds(json, "einvoiceScheme", scheme)
                                  && holds(json, "einvoiceId", id)
                              : vat != null && holds(json, "vatNumber", vat))
                  .toList();
          if (holders.size() == 1) {
            JsonStub.reply(exchange, 200, holders.get(0));
          } else if (holders.isEmpty()) {
            JsonStub.reply(
                exchange,
                404,
                "{\"error\":{\"code\":\"TENANT_NOT_FOUND\",\"message\":\"nobody holds it\"}}");
          } else {
            JsonStub.reply(
                exchange,
                409,
                "{\"error\":{\"code\":\"TENANT_EINVOICE_ADDRESS_SHARED\",\"message\":\""
                    + holders.size()
                    + " businesses hold it\"}}");
          }
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
          JsonStub.reply(
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
          JsonStub.reply(
              exchange,
              200,
              body != null
                  ? body
                  : "{\"data\":{\"country\":\"GB\",\"countries\":[\"GB\"],\"classes\":[],"
                      + "\"holds\":[]}}");
        });
    // A tenant's stores, one page; a tenant with none registered has none. Under it, one of them
    // by id, and 404 for a store that is not the tenant's.
    server.createContext(
        STORES,
        exchange -> {
          String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
          java.util.List<String> own =
              tenant == null
                  ? java.util.List.of()
                  : stub.stores.getOrDefault(tenant, java.util.List.of());
          String rest = exchange.getRequestURI().getPath().substring(STORES.length());
          if (rest.length() > 1) {
            String opening = "{\"id\":\"" + rest.substring(1) + "\"";
            String found = own.stream().filter(s -> s.startsWith(opening)).findFirst().orElse(null);
            JsonStub.reply(
                exchange,
                found == null ? 404 : 200,
                found == null
                    ? "{\"error\":{\"code\":\"TENANT_STORE_NOT_FOUND\",\"message\":\"no such store\"}}"
                    : "{\"data\":" + found + "}");
            return;
          }
          JsonStub.reply(
              exchange,
              200,
              "{\"data\":[" + String.join(",", own) + "],\"meta\":{\"nextCursor\":null}}");
        });
    server.start();
    System.setProperty("shelfj.clients.tenant-svc.url", JsonStub.baseOf(server));
    return stub;
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
    return extend(
        tenantId,
        field("vatNumber", vatNumber)
            + field("einvoiceScheme", einvoiceScheme)
            + field("einvoiceId", einvoiceId));
  }

  /** Gives a registered tenant the legal name {@code GET /admin/tenant} returns (18.9). */
  public TenantSvcStub withLegalName(String tenantId, String legalName) {
    return extend(tenantId, field("legalName", legalName));
  }

  private TenantSvcStub extend(String tenantId, String fields) {
    profiles.computeIfPresent(
        tenantId, (id, json) -> json.substring(0, json.length() - 2) + fields + "}}");
    return this;
  }

  /** Whether a registered profile carries the field with the value, case aside. */
  private static boolean holds(String json, String field, String value) {
    return value != null
        && json.toLowerCase(java.util.Locale.ROOT)
            .contains(("\"" + field + "\":\"" + value + "\"").toLowerCase(java.util.Locale.ROOT));
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
    return withStore(tenantId, storeId, country, null, null, null);
  }

  /**
   * Registers one of a tenant's stores with the postal address an invoice prints (18.9). Nulls
   * leave a field out.
   */
  public TenantSvcStub withStore(
      String tenantId, String storeId, String country, String line1, String city, String pincode) {
    stores
        .computeIfAbsent(tenantId, t -> new java.util.concurrent.CopyOnWriteArrayList<>())
        .add(
            "{\"id\":\""
                + storeId
                + "\",\"country\":"
                + (country == null ? "null" : "\"" + country + "\"")
                + field("line1", line1)
                + field("city", city)
                + field("pincode", pincode)
                + "}");
    return this;
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
