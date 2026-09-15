package com.shelfj.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.RedisSupport;
import com.shelfj.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A variant's measure, which every unit price is computed from (03.13): announced with the save
 * that changed it and a version that puts it in order; required of food where unit pricing is law;
 * re-announced with the catalogue; and twenty saves at once still leave the newest announcement
 * saying what is stored.
 */
@HelidonTest
class UnitMeasureIT {

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final TenantSvcStub STUB;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "product");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("shelfj.redis.host", REDIS.host());
    System.setProperty("shelfj.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("shelfj.redis.password", "");
    // The UK's amended Price Marking Order binds a British business; nothing binds a Japanese one.
    STUB = TenantSvcStub.start().withObligation("GB", "UNIT_PRICING", "GB", "2026-04-06", null);
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private static String tenant(String currency, String country) {
    String t = Ids.newId().toString();
    STUB.with(t, currency, country);
    return t;
  }

  private Response post(String path, String json, String tenant, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response compliance(String tenant, String variant, String json, String roles) {
    return target
        .path("/admin/products/variants/" + variant + "/compliance")
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", roles)
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String id(Response r) {
    assertThat(r.getStatus(), is(201));
    return Json.createReader(new StringReader(r.readEntity(String.class)))
        .readObject()
        .getJsonObject("data")
        .getString("id");
  }

  private String variant(String tenant) {
    String product =
        id(post("/admin/products", "{\"name\":\"Measured " + Ids.newId() + "\"}", tenant, "OWNER"));
    return id(
        post(
            "/admin/products/" + product + "/variants",
            "{\"sku\":\"M-" + Ids.newId() + "\"}",
            tenant,
            "OWNER"));
  }

  /** The measure announcements for a variant, oldest first, as JSON. */
  private static List<JsonObject> announced(String tenant, String variant) throws Exception {
    List<JsonObject> out = new ArrayList<>();
    try (var c = java.sql.DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM product.outbox WHERE tenant_id = ?::uuid AND aggregate_id = ?::uuid"
                    + " AND event_type = 'VariantMeasured' ORDER BY created_at, id")) {
      ps.setString(1, tenant);
      ps.setString(2, variant);
      try (var rs = ps.executeQuery()) {
        while (rs.next())
          out.add(Json.createReader(new StringReader(rs.getString(1))).readObject());
      }
    }
    return out;
  }

  // ── the announcement ───────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A save announces the measure in standard units, with its version, or nulls when there is none")
  void aSaveAnnouncesTheMeasure() throws Exception {
    String gb = tenant("GBP", "GB");
    String wine = variant(gb);
    assertThat(
        compliance(
                gb,
                wine,
                "{\"soldBy\":\"EACH\",\"netContent\":750,\"netContentUom\":\"ML\"}",
                "OWNER")
            .getStatus(),
        is(200));
    JsonObject first = announced(gb, wine).get(0);
    assertThat(first.getString("unit"), is("L"));
    assertThat(
        new BigDecimal(first.getString("quantity")).compareTo(new BigDecimal("0.75")), is(0));
    assertThat(first.getJsonNumber("version").longValue(), is(1L));
    assertThat(first.getString("productId").length(), is(36));

    assertThat(compliance(gb, wine, "{\"soldBy\":\"EACH\"}", "MANAGER").getStatus(), is(200));
    JsonObject second = announced(gb, wine).get(1);
    assertThat("a measure taken away is announced too", second.isNull("unit"), is(true));
    assertThat(second.isNull("quantity"), is(true));
    assertThat(second.getJsonNumber("version").longValue(), is(2L));

    assertThat(
        compliance(
                gb,
                wine,
                "{\"soldBy\":\"WEIGHT\",\"netContent\":100,\"netContentUom\":\"G\"}",
                "OWNER")
            .getStatus(),
        is(200));
    JsonObject third = announced(gb, wine).get(2);
    assertThat(third.getString("unit"), is("KG"));
    assertThat(new BigDecimal(third.getString("quantity")).compareTo(new BigDecimal("0.1")), is(0));
  }

  // ── the food rule ──────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Where unit pricing is law, food states how much its price buys; elsewhere nothing is required")
  void foodDeclaresItsMeasureWhereTheLawRequiresIt() throws Exception {
    String gb = tenant("GBP", "GB");
    String cheese = variant(gb);
    Response bare = compliance(gb, cheese, "{\"food\":true}", "OWNER");
    assertThat(bare.getStatus(), is(400));
    assertThat(
        bare.readEntity(String.class), containsString("PRODUCT_UNIT_PRICE_MEASURE_REQUIRED"));
    assertThat("nothing was saved or announced", announced(gb, cheese).size(), is(0));
    assertThat(
        compliance(
                gb, cheese, "{\"food\":true,\"netContent\":1,\"netContentUom\":\"BAG\"}", "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        compliance(gb, cheese, "{\"food\":true,\"netContent\":0,\"netContentUom\":\"G\"}", "OWNER")
            .getStatus(),
        is(400));
    assertThat(
        compliance(
                gb,
                cheese,
                "{\"food\":true,\"soldBy\":\"EACH\",\"netContent\":1,\"netContentUom\":\"EA\"}",
                "OWNER")
            .getStatus(),
        is(200));
    // Already food: a later save without the flag still has to keep its measure.
    Response dropped = compliance(gb, cheese, "{\"soldBy\":\"EACH\"}", "OWNER");
    assertThat(dropped.getStatus(), is(400));
    assertThat(
        dropped.readEntity(String.class), containsString("PRODUCT_UNIT_PRICE_MEASURE_REQUIRED"));
    assertThat(
        "not food any more, it may be sold without one",
        compliance(gb, cheese, "{\"food\":false}", "OWNER").getStatus(),
        is(200));

    String kettle = variant(gb);
    assertThat(
        "a kettle is not food",
        compliance(gb, kettle, "{\"soldBy\":\"EACH\"}", "OWNER").getStatus(),
        is(200));

    String jp = tenant("JPY", "JP");
    String rice = variant(jp);
    assertThat(
        "no unit-pricing law binds a Japanese business",
        compliance(jp, rice, "{\"food\":true}", "OWNER").getStatus(),
        is(200));
  }

  // ── the catalogue ──────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Re-announcing the catalogue re-announces every variant's measure at its current version")
  void republishReannouncesMeasures() throws Exception {
    String gb = tenant("GBP", "GB");
    String a = variant(gb);
    String b = variant(gb);
    compliance(gb, a, "{\"soldBy\":\"EACH\",\"netContent\":6,\"netContentUom\":\"EA\"}", "OWNER")
        .close();
    assertThat(post("/admin/products/republish-catalogue", "{}", gb, "OWNER").getStatus(), is(200));
    List<JsonObject> forA = announced(gb, a);
    assertThat(forA.size(), is(2));
    assertThat(
        "the re-announcement carries the version the measure has",
        forA.get(1).getJsonNumber("version").longValue(),
        is(1L));
    assertThat(forA.get(1).getString("unit"), is("EA"));
    JsonObject forB = announced(gb, b).get(0);
    assertThat(
        "a variant never measured is announced as having none", forB.isNull("unit"), is(true));
    assertThat(forB.getJsonNumber("version").longValue(), is(0L));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The wrong caller, another business's variant and an unknown unit are refused")
  void refusals() {
    String gb = tenant("GBP", "GB");
    String v = variant(gb);
    assertThat(
        compliance(
                gb, v, "{\"soldBy\":\"EACH\",\"netContent\":1,\"netContentUom\":\"EA\"}", "CASHIER")
            .getStatus(),
        is(403));
    String rival = tenant("GBP", "GB");
    assertThat(
        compliance(
                rival,
                v,
                "{\"soldBy\":\"EACH\",\"netContent\":1,\"netContentUom\":\"EA\"}",
                "OWNER")
            .getStatus(),
        is(404));
    Response unknown =
        compliance(
            gb,
            v,
            "{\"soldBy\":\"EACH\",\"netContent\":1,\"netContentUom\":\"KG' OR '1'='1\"}",
            "OWNER");
    assertThat(unknown.getStatus(), is(400));
    assertThat(unknown.readEntity(String.class), containsString("PRODUCT_UNKNOWN_UOM"));
  }

  // ── abuse ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Twenty saves at once take versions 1 to 20, and the newest announcement is what is stored")
  void concurrentSavesStayInOrder() throws Exception {
    String gb = tenant("GBP", "GB");
    String v = variant(gb);
    var pool = Executors.newFixedThreadPool(20);
    try {
      List<Future<Integer>> tries = new ArrayList<>();
      for (int k = 0; k < 20; k++) {
        final int grams = 100 + k;
        tries.add(
            pool.submit(
                () ->
                    compliance(
                            gb,
                            v,
                            "{\"soldBy\":\"EACH\",\"netContent\":"
                                + grams
                                + ",\"netContentUom\":\"G\"}",
                            "OWNER")
                        .getStatus()));
      }
      for (var f : tries) assertThat(f.get(), lessThan(300));
    } finally {
      pool.shutdownNow();
    }
    List<JsonObject> all = announced(gb, v);
    assertThat(all.size(), is(20));
    assertThat(
        all.stream().map(o -> o.getJsonNumber("version").longValue()).distinct().count(), is(20L));
    JsonObject newest =
        all.stream()
            .max(java.util.Comparator.comparingLong(o -> o.getJsonNumber("version").longValue()))
            .orElseThrow();
    assertThat(newest.getJsonNumber("version").longValue(), is(20L));
    JsonObject stored =
        Json.createReader(
                new StringReader(
                    target
                        .path("/catalog/variants/" + v + "/compliance")
                        .request()
                        .header("X-Tenant-Id", gb)
                        .get(String.class)))
            .readObject()
            .getJsonObject("data");
    BigDecimal storedKg = stored.getJsonNumber("netContent").bigDecimalValue().movePointLeft(3);
    assertThat(new BigDecimal(newest.getString("quantity")).compareTo(storedKg), is(0));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "The owner's tenant data manifest is complete: every table is exported or left out by name")
  void tenantDataIsExportable() {
    com.shelfj.test.TenantDataChecks.assertExportable(
        target, "01a090ae-611e-702c-a97b-d1b8025478e1");
  }
}
