package com.shelfj.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.ids.Ids;
import com.shelfj.test.PostgresSupport;
import com.shelfj.test.RedisSupport;
import com.shelfj.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shelf space: fixtures, planograms, space plans and resets (07.17), over HTTP and a real database.
 *
 * <p>Three things here cannot be had from a unit test, and they are the reason this class exists.
 *
 * <p><b>Capacity is a generated column.</b> {@code facings * depth} is computed by Postgres, so no
 * write path can leave it disagreeing with the layout it came from. A test against a stubbed
 * repository would assert the arithmetic the application happened to do on the way in, which is the
 * thing that goes stale.
 *
 * <p><b>One draft and one layout in force per fixture are partial unique indexes.</b> The rule is
 * the index, not the check in the service, and only a database enforces it against two people
 * working at once.
 *
 * <p><b>Publishing supersedes under a deferred foreign key.</b> The predecessor has to be marked
 * before the successor is inserted or the partial unique index refuses it, and the deferred
 * constraint is what makes that order legal at all.
 */
@HelidonTest
class MerchandisingIT {

  private static final String T = Ids.newId().toString();
  private static final String RIVAL = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;

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
    TenantSvcStub.start().with(T, "GBP", "GB").with(RIVAL, "GBP", "GB");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
    REDIS.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response put(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .put(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String query, String value, String tenant) {
    var t = target.path(path);
    if (query != null) t = t.queryParam(query, value);
    return t.request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER")
        .get();
  }

  private static String id(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return data(body).getString("id");
  }

  private static jakarta.json.JsonObject data(String body) {
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static String body(Response r, int expected) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(expected));
    return body;
  }

  private String fixture(String tenant, int shelves, int widthMm) {
    return id(
        post(
            "/admin/merchandising/fixtures",
            "{\"storeId\":\""
                + STORE
                + "\",\"code\":\"F-"
                + Ids.newId()
                + "\",\"name\":\"Gondola\",\"kind\":\"GONDOLA\",\"shelfCount\":"
                + shelves
                + ",\"shelfWidthMm\":"
                + widthMm
                + "}",
            tenant));
  }

  /** A variant with a recorded facing width, so a layout can be checked against the shelf. */
  private String variant(String tenant, Integer facingWidthMm) {
    return variant(tenant, facingWidthMm, null);
  }

  private String variant(String tenant, Integer facingWidthMm, String categoryId) {
    String product =
        id(
            post(
                "/admin/products",
                "{\"name\":\"Line "
                    + Ids.newId()
                    + "\""
                    + (categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"")
                    + "}",
                tenant));
    String variant =
        id(
            post(
                "/admin/products/" + product + "/variants",
                "{\"sku\":\"S-" + Ids.newId() + "\"}",
                tenant));
    if (facingWidthMm != null) {
      body(
          put(
              "/admin/merchandising/variants/" + variant + "/facing-width",
              "{\"facingWidthMm\":" + facingWidthMm + "}",
              tenant),
          200);
    }
    return variant;
  }

  private String draft(String tenant, String fixtureId) {
    return id(
        post(
            "/admin/merchandising/fixtures/" + fixtureId + "/planograms",
            "{\"effectiveFrom\":\"2026-10-01\"}",
            tenant));
  }

  private static String position(String variantId, int shelf, int seq, int facings, int depth) {
    return "{\"variantId\":\""
        + variantId
        + "\",\"shelf\":"
        + shelf
        + ",\"sequence\":"
        + seq
        + ",\"facings\":"
        + facings
        + ",\"depth\":"
        + depth
        + ",\"minPresentation\":1}";
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("Capacity is the database's arithmetic, and a layout's total is the sum of it")
  void capacityIsGenerated() {
    String fixtureId = fixture(T, 4, 1000);
    String planogram = draft(T, fixtureId);
    String v1 = variant(T, 60);
    String v2 = variant(T, 80);

    String fit =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":["
                    + position(v1, 1, 1, 3, 4)
                    + ","
                    + position(v2, 1, 2, 2, 5)
                    + "]}",
                T),
            200);
    // The save answers with the fit: 3 facings of 60mm and 2 of 80mm on a 1000mm shelf.
    assertThat(fit, containsString("\"usedMm\":340"));

    // 3x4 and 2x5: the numbers are read back from the generated column, not from the request.
    String saved = body(get("/admin/merchandising/planograms/" + planogram, null, null, T), 200);
    assertThat(saved, containsString("\"capacity\":12"));
    assertThat(saved, containsString("\"capacity\":10"));
    assertThat(saved, containsString("\"totalCapacity\":22"));
  }

  @Test
  @DisplayName("A layout wider than its shelf is refused when it is saved, not at publication")
  void theShelfIsFinite() {
    // 1000mm of shelf, 5 facings of a 250mm line: 1250mm. Refused now rather than at publication,
    // because finding out after a reset has been scheduled around the layout is finding out too
    // late.
    String fixtureId = fixture(T, 1, 1000);
    String planogram = draft(T, fixtureId);
    String wide = variant(T, 250);

    String refused =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(wide, 1, 1, 5, 2) + "]}",
                T),
            409);
    assertThat(refused, containsString("PLANOGRAM_SHELF_OVERFLOWS"));
  }

  @Test
  @DisplayName(
      "A line with no recorded width is placed, and the answer says it could not be checked")
  void whatCannotBeMeasuredIsNotRefused() {
    // Most catalogues have gaps. A planogram nobody can save because one line lacks a measurement
    // would be worse than one whose check is partial and honest about being partial.
    String fixtureId = fixture(T, 1, 1000);
    String planogram = draft(T, fixtureId);
    String unmeasured = variant(T, null);

    String fit =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(unmeasured, 1, 1, 9, 2) + "]}",
                T),
            200);
    assertThat(fit, containsString("\"unmeasured\":1"));
    assertThat("nothing measurable was used", fit, containsString("\"usedMm\":0"));
  }

  @Test
  @DisplayName("A shelf beyond the fixture's shelf count is refused")
  void beyondTheFixture() {
    String fixtureId = fixture(T, 2, 1000);
    String planogram = draft(T, fixtureId);
    String v = variant(T, 50);
    String refused =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(v, 3, 1, 1, 1) + "]}",
                T),
            400);
    assertThat(refused, containsString("PLANOGRAM_SHELF_BEYOND_FIXTURE"));
  }

  @Test
  @DisplayName("One draft per fixture, and the second is refused by the index")
  void oneDraftAtATime() {
    // Two people drawing the same shelf at once is a merge nobody wins.
    String fixtureId = fixture(T, 2, 1000);
    draft(T, fixtureId);
    String refused =
        body(
            post(
                "/admin/merchandising/fixtures/" + fixtureId + "/planograms",
                "{\"effectiveFrom\":\"2026-11-01\"}",
                T),
            409);
    assertThat(refused, containsString("PLANOGRAM_DRAFT_EXISTS"));
  }

  @Test
  @DisplayName("Publishing supersedes the version in force, and an empty layout publishes nothing")
  void publishingSupersedes() {
    String fixtureId = fixture(T, 2, 2000);
    String v = variant(T, 100);

    String first = draft(T, fixtureId);
    String empty =
        body(post("/admin/merchandising/planograms/" + first + "/publish", "{}", T), 409);
    assertThat(empty, containsString("PLANOGRAM_EMPTY"));

    body(
        put(
            "/admin/merchandising/planograms/" + first + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 4, 3) + "]}",
            T),
        200);
    String published =
        body(post("/admin/merchandising/planograms/" + first + "/publish", "{}", T), 200);
    assertThat(published, containsString("\"status\":\"PUBLISHED\""));
    assertThat(published, containsString("\"version\":1"));

    // A second version: the predecessor must be marked before this one is inserted, which only the
    // deferred foreign key allows.
    String second = draft(T, fixtureId);
    body(
        put(
            "/admin/merchandising/planograms/" + second + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 6, 3) + "]}",
            T),
        200);
    String successor =
        body(post("/admin/merchandising/planograms/" + second + "/publish", "{}", T), 200);
    assertThat(successor, containsString("\"version\":2"));
    assertThat(successor, containsString("\"supersedes\":\"" + first + "\""));

    String old = body(get("/admin/merchandising/planograms/" + first, null, null, T), 200);
    assertThat(old, containsString("\"status\":\"SUPERSEDED\""));
    assertThat(old, containsString("\"supersededBy\":\"" + second + "\""));

    // And the layout in force is the new one.
    String inForce =
        body(get("/admin/merchandising/fixtures/" + fixtureId + "/planogram", null, null, T), 200);
    assertThat(inForce, containsString("\"id\":\"" + second + "\""));
  }

  @Test
  @DisplayName("A published layout is never edited again")
  void publishedIsImmutable() {
    String fixtureId = fixture(T, 1, 2000);
    String v = variant(T, 100);
    String planogram = draft(T, fixtureId);
    body(
        put(
            "/admin/merchandising/planograms/" + planogram + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 2, 2) + "]}",
            T),
        200);
    body(post("/admin/merchandising/planograms/" + planogram + "/publish", "{}", T), 200);

    String refused =
        body(
            put(
                "/admin/merchandising/planograms/" + planogram + "/positions",
                "{\"positions\":[" + position(v, 1, 1, 3, 2) + "]}",
                T),
            409);
    assertThat(refused, containsString("PLANOGRAM_NOT_DRAFT"));
  }

  @Test
  @DisplayName("A category's actual share is measured from the shelves, not typed in")
  void spaceIsMeasured() {
    // The variance is what a space plan is for: a promise of eight per cent against what the bays
    // actually give the category. Only a planned category appears, so there is a plan here.
    String categoryId =
        id(post("/admin/categories", "{\"name\":\"Crisps " + Ids.newId() + "\"}", T));
    String fixtureId = fixture(T, 2, 1000);
    String v = variant(T, 200, categoryId);
    body(
        put(
            "/admin/merchandising/space-plans",
            "{\"storeId\":\""
                + STORE
                + "\",\"categoryId\":\""
                + categoryId
                + "\",\"targetShare\":0.0800}",
            T),
        200);
    String planogram = draft(T, fixtureId);
    body(
        put(
            "/admin/merchandising/planograms/" + planogram + "/positions",
            "{\"positions\":[" + position(v, 1, 1, 2, 2) + "]}",
            T),
        200);
    body(post("/admin/merchandising/planograms/" + planogram + "/publish", "{}", T), 200);

    String report = body(get("/admin/merchandising/space", "store", STORE, T), 200);
    // 2 facings x 200mm of a 2x1000mm fixture: 400 of 2000.
    assertThat(report, containsString("\"actualMm\":400"));
  }

  @Test
  @DisplayName("A reset is overdue when its day has passed, and cancelling it needs a reason")
  void resets() {
    String categoryId =
        id(post("/admin/categories", "{\"name\":\"Soft drinks " + Ids.newId() + "\"}", T));
    String reset =
        id(
            post(
                "/admin/merchandising/resets",
                "{\"categoryId\":\""
                    + categoryId
                    + "\",\"name\":\"Spring reset\",\"scheduledFor\":\"2020-03-01\"}",
                T));
    String listed = body(get("/admin/merchandising/resets", null, null, T), 200);
    assertThat("its day is long past", listed, containsString("\"overdue\":true"));

    String noReason =
        body(post("/admin/merchandising/resets/" + reset + "/cancel", "{\"reason\":\"\"}", T), 400);
    assertThat(noReason, containsString("VALIDATION_FAILED"));

    String cancelled =
        body(
            post(
                "/admin/merchandising/resets/" + reset + "/cancel",
                "{\"reason\":\"Supplier pulled the range\"}",
                T),
            200);
    assertThat(cancelled, containsString("\"status\":\"CANCELLED\""));
    assertThat(
        "a cancelled reset is not overdue", cancelled, not(containsString("\"overdue\":true")));
  }

  @Test
  @DisplayName("Another business cannot read or touch this one's fixtures")
  void tenantsAreSeparate() {
    String fixtureId = fixture(T, 2, 1000);
    assertThat(
        body(
            get("/admin/merchandising/fixtures/" + fixtureId + "/planogram", null, null, RIVAL),
            404),
        containsString("NOT_FOUND"));
    assertThat(
        body(post("/admin/merchandising/fixtures/" + fixtureId + "/retire", "{}", RIVAL), 404),
        containsString("FIXTURE_NOT_FOUND"));
  }

  @Test
  @DisplayName("A caller with no roles is refused, whatever the tenant header says")
  void rolesAreRequired() {
    // No gateway in front of this test, so the request arrives with no roles rather than no token:
    // 403, where a call through the gateway would be a 401.
    Response r =
        target
            .path("/admin/merchandising/fixtures")
            .queryParam("store", STORE)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", USER)
            .get();
    assertThat(r.readEntity(String.class), r.getStatus(), is(403));
  }
}
