package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.service.TenantDataErasureHandler;
import com.storeql.tenant.service.SwitchingService;
import com.storeql.test.Concurrency;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A business leaving the platform (21.14, EU Data Act art.25): notice given, extended once and
 * withdrawn; the sweep starting an erasure once however many run, closing the business and telling
 * every service; tenant-svc erasing its share while keeping the record that the business left; the
 * evidence from each service recorded once; and every refusal and abuse along the way. Kafka and
 * Consul disabled; two services are expected to answer, so the evidence can be completed here.
 */
@HelidonTest
class SwitchingIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start().wire("tenant");
    System.setProperty("storeql.switching.services", "order-svc,tenant-svc");
    System.setProperty("storeql.switching.sweeper.enabled", "false");
  }

  private static final String BASE = "/admin/tenant/switching";
  private static final String OWNER = Ids.newId().toString();
  private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
  private static final Set<String> KEPT =
      Set.of("tenants", "tenant_switches", "tenant_erasure_evidence");

  @Inject WebTarget target;
  @Inject SwitchingService service;
  @Inject TenantDataErasureHandler erasure;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @Test
  @DisplayName("Notice given, extended once, withdrawn with a reason, and given again")
  void lifecycle() {
    String t = onboard();
    JsonObject given =
        created(as(BASE, t, "OWNER").post(json(notice("SWITCH", TODAY.plusMonths(1)))));
    JsonObject n = given.getJsonObject("notice");
    assertThat(given.getString("stage"), is("NOTICE"));
    assertThat(n.getString("noticeEndsOn"), is(TODAY.plusMonths(1).toString()));
    assertThat(n.getString("transitionEndsOn"), is(TODAY.plusMonths(1).plusDays(30).toString()));
    assertThat(n.getString("retrievalEndsOn"), is(TODAY.plusMonths(1).plusDays(60).toString()));
    assertThat(n.getString("erasureDueOn"), is(TODAY.plusMonths(1).plusDays(61).toString()));
    assertThat(n.getString("noticeGivenBy"), is(OWNER));
    assertCode(
        as(BASE, t, "OWNER").post(json(notice("ERASE", TODAY))),
        409,
        "SWITCHING_NOTICE_ALREADY_GIVEN");

    LocalDate longer = TODAY.plusMonths(3);
    JsonObject extended = ok(as(BASE + "/extend", t, "OWNER").post(json(extension(longer))));
    assertThat(
        extended.getJsonObject("notice").getString("transitionEndsOn"), is(longer.toString()));
    assertThat(
        extended.getJsonObject("notice").getString("erasureDueOn"),
        is(longer.plusDays(31).toString()));
    assertCode(
        as(BASE + "/extend", t, "OWNER").post(json(extension(longer.plusDays(1)))),
        409,
        "SWITCHING_ALREADY_EXTENDED");

    assertThat(as(BASE + "/cancel", t, "OWNER").post(json("{}")).getStatus(), is(400));
    JsonObject cancelled =
        ok(as(BASE + "/cancel", t, "OWNER").post(json("{\"reason\":\"staying after all\"}")));
    assertThat(cancelled.getString("stage"), is("CANCELLED"));
    assertThat(
        cancelled.getJsonObject("notice").getString("cancelReason"), is("staying after all"));
    assertCode(
        as(BASE + "/cancel", t, "OWNER").post(json("{\"reason\":\"again\"}")),
        409,
        "SWITCHING_CLOSED");
    assertCode(
        as(BASE + "/extend", t, "OWNER").post(json(extension(longer))), 409, "SWITCHING_CLOSED");
    assertThat(
        created(as(BASE, t, "OWNER").post(json(notice("SWITCH", TODAY.plusDays(10)))))
            .getString("stage"),
        is("NOTICE"));
  }

  @Test
  @DisplayName("Only the owner acts; nonsense and the wrong moment are refused by name")
  void refusals() {
    String t = onboard();
    assertCode(as(BASE, t, "OWNER").get(), 404, "SWITCHING_NO_NOTICE");
    for (String role : List.of("MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
      assertThat(role, as(BASE, t, role).post(json(notice("ERASE", TODAY))).getStatus(), is(403));
      assertThat(role, as(BASE, t, role).get().getStatus(), is(403));
    }
    assertCode(
        as(BASE, t, "OWNER").post(json(notice("LEAVE", TODAY))), 400, "SWITCHING_INTENT_UNKNOWN");
    assertCode(
        as(BASE, t, "OWNER").post(json(notice("SWITCH", TODAY.minusDays(1)))),
        400,
        "SWITCHING_NOTICE_INVALID");
    assertCode(
        as(BASE, t, "OWNER").post(json(notice("SWITCH", TODAY.plusMonths(2).plusDays(1)))),
        400,
        "SWITCHING_NOTICE_TOO_LONG");
    assertCode(
        as(BASE, t, "OWNER").post(json("{\"intent\":\"SWITCH\",\"noticeEndsOn\":\"15/09/2026\"}")),
        400,
        "SWITCHING_NOTICE_INVALID");
    assertThat(as(BASE, t, "OWNER").post(json("{\"intent\":\"SWITCH\"}")).getStatus(), is(400));
    assertCode(
        as(BASE + "/extend", t, "OWNER").post(json(extension(TODAY.plusMonths(1)))),
        404,
        "SWITCHING_NO_NOTICE");

    created(as(BASE, t, "OWNER").post(json(notice("ERASE", TODAY.plusDays(5)))));
    assertCode(
        as(BASE + "/extend", t, "OWNER").post(json(extension(TODAY.plusMonths(1)))),
        409,
        "SWITCHING_NOT_EXTENDABLE");
    String rival = onboard();
    assertCode(as(BASE, rival, "OWNER").get(), 404, "SWITCHING_NO_NOTICE");
    assertThat(
        as("/platform/tenants/switching/sweep", t, "OWNER").post(json("{}")).getStatus(), is(403));
    assertThat(as("/platform/tenants/switching/" + t, rival, "OWNER").get().getStatus(), is(403));
  }

  @Test
  @DisplayName(
      "An erasure starts once however many sweeps race, closes the business, and is evidenced")
  void erasure() throws Exception {
    String t = onboard();
    created(as(BASE, t, "OWNER").post(json(notice("ERASE", TODAY))));
    assertThat(ok(as(BASE, t, "OWNER").get()).getString("stage"), is("ERASURE_DUE"));

    List<Integer> started =
        Concurrency.inParallel(
            10,
            () ->
                ok(platform("/platform/tenants/switching/sweep").post(json("{}")))
                    .getInt("started"));
    assertThat(started.toString(), started.stream().mapToInt(Integer::intValue).sum(), is(1));

    JsonObject status = ok(as(BASE, t, "OWNER").get());
    assertThat(status.getString("stage"), is("ERASING"));
    assertThat(status.getJsonArray("awaiting").toString(), is("[\"order-svc\",\"tenant-svc\"]"));
    assertCode(
        as(BASE + "/cancel", t, "OWNER").post(json("{\"reason\":\"too late\"}")),
        409,
        "SWITCHING_CLOSED");
    assertThat(
        dbString("SELECT status FROM tenant.tenants WHERE id = '" + t + "'"), is("INACTIVE"));
    List<String> due = outbox("TenantDataErasureDue", t);
    assertThat(due.size(), is(1));
    assertThat(
        outbox("TenantStatusChanged", t).stream().anyMatch(p -> p.contains("\"INACTIVE\"")),
        is(true));

    // tenant-svc erases its share, keeping the record that the business left.
    erasure.handle(due.get(0));
    for (var e : manifest(t).entrySet()) {
      int expected =
          KEPT.contains(e.getKey()) && !e.getKey().equals("tenant_erasure_evidence") ? 1 : 0;
      assertThat(e.getKey(), e.getValue().getInt("rows"), is(expected));
    }
    List<String> erased = outbox("TenantDataErased", t);
    assertThat(erased.size(), is(1));
    assertThat(service.recordErased(erased.get(0)), is(true));
    assertThat(service.recordErased(erased.get(0)), is(false));
    assertThat(
        ok(as(BASE, t, "OWNER").get()).getJsonArray("awaiting").toString(), is("[\"order-svc\"]"));

    // Evidence that answers nothing started here, or is nonsense, is not recorded.
    String eventId = parse(due.get(0)).getString("eventId");
    assertThat(service.recordErased("not json"), is(false));
    assertThat(service.recordErased(erased(t, Ids.newId().toString(), "order-svc", 3)), is(false));
    assertThat(service.recordErased(erased(t, eventId, "order-svc", -1)), is(false));
    assertThat(service.recordErased(erased(onboard(), eventId, "order-svc", 3)), is(false));
    assertThat(service.recordErased(erased(t, eventId, "order-svc", 42)), is(true));

    JsonObject done = ok(as(BASE, t, "OWNER").get());
    assertThat(done.getString("stage"), is("ERASED"));
    assertThat(done.getJsonArray("evidence").size(), is(2));
    assertThat(done.getJsonArray("awaiting").size(), is(0));
    assertThat(
        ok(platform("/platform/tenants/switching/" + t).get()).getString("stage"), is("ERASED"));
    assertThat(
        ok(platform("/platform/tenants/switching/sweep").post(json("{}"))).getInt("started"),
        is(0));
  }

  @Test
  @DisplayName("A notice cannot be imported into another business, nor the business record")
  void noticesAreNotImported() {
    String t = onboard();
    created(as(BASE, t, "OWNER").post(json(notice("SWITCH", TODAY.plusDays(20)))));
    JsonArray rows = page(t, "tenant_switches");
    assertThat(rows.size(), is(1));
    String fresh = onboard();
    String body = Json.createObjectBuilder().add("rows", rows).build().toString();
    assertCode(
        as("/admin/tenant-data/tables/tenant_switches", fresh, "OWNER").post(json(body)),
        409,
        "TENANT_DATA_IMPORT_SKIPPED");
    assertCode(
        as("/admin/tenant-data/tables/tenants", fresh, "OWNER").post(json("{\"rows\":[]}")),
        409,
        "TENANT_DATA_IMPORT_SKIPPED");
    assertCode(as(BASE, fresh, "OWNER").get(), 404, "SWITCHING_NO_NOTICE");
    JsonObject manifest = ok(as("/admin/tenant-data", t, "OWNER").get());
    assertThat(
        manifest.getJsonObject("keptAtErasure").getString("tenant_switches"),
        containsString("left"));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private String onboard() {
    Response r =
        target
            .path("/onboarding/tenants")
            .request(MediaType.APPLICATION_JSON)
            .header("X-User-Id", Ids.newId().toString())
            .post(
                json(
                    "{\"businessName\":\"Leaving "
                        + Ids.newId()
                        + "\",\"country\":\"GB\",\"currency\":\"GBP\"}"));
    return created(r).getString("id");
  }

  private Invocation.Builder as(String path, String tenant, String roles) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", OWNER)
        .header("X-Roles", roles);
  }

  private Invocation.Builder platform(String path) {
    return target
        .path(path)
        .request(MediaType.APPLICATION_JSON)
        .header("X-User-Id", OWNER)
        .header("X-Roles", "PLATFORM_ADMIN");
  }

  private Map<String, JsonObject> manifest(String tenant) {
    Map<String, JsonObject> tables = new LinkedHashMap<>();
    for (JsonValue v : ok(as("/admin/tenant-data", tenant, "OWNER").get()).getJsonArray("tables")) {
      tables.put(v.asJsonObject().getString("name"), v.asJsonObject());
    }
    return tables;
  }

  private JsonArray page(String tenant, String table) {
    return ok(as("/admin/tenant-data/tables/" + table, tenant, "OWNER").get()).getJsonArray("rows");
  }

  private static String notice(String intent, LocalDate ends) {
    return "{\"intent\":\"" + intent + "\",\"noticeEndsOn\":\"" + ends + "\"}";
  }

  private static String extension(LocalDate ends) {
    return "{\"transitionEndsOn\":\"" + ends + "\"}";
  }

  private static String erased(String tenant, String erasureEventId, String service, int rows) {
    return Json.createObjectBuilder()
        .add("eventId", Ids.newId().toString())
        .add("eventType", "TenantDataErased")
        .add("tenantId", tenant)
        .add("aggregateId", tenant)
        .add("occurredAt", Instant.now().toString())
        .add("service", service)
        .add("erasureEventId", erasureEventId)
        .add("rows", rows)
        .add("tables", Json.createObjectBuilder().add("orders", rows))
        .build()
        .toString();
  }

  private static Entity<String> json(String body) {
    return Entity.entity(body, MediaType.APPLICATION_JSON);
  }

  private static JsonObject created(Response r) {
    return data(r, 201);
  }

  private static JsonObject ok(Response r) {
    return data(r, 200);
  }

  private static JsonObject data(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return parse(body).getJsonObject("data");
  }

  private static void assertCode(Response r, int status, String code) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    assertThat(body, containsString(code));
  }

  private static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  private static List<String> outbox(String eventType, String tenant) throws SQLException {
    List<String> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM tenant.outbox WHERE tenant_id = ?::uuid AND event_type = ?"
                    + " ORDER BY created_at, id")) {
      ps.setString(1, tenant);
      ps.setString(2, eventType);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static String dbString(String sql) throws SQLException {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      if (!rs.next()) throw new AssertionError("no row: " + sql);
      return rs.getString(1);
    }
  }
}
