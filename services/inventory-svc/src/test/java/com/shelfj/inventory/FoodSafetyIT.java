package com.shelfj.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.service.FoodSafetyService;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Food-safety checks against real Postgres: a failed reading is alerted and stays open until a
 * corrective action is recorded; limits can only be tightened; retries record once; the filter
 * gates setup to management by path; tenants and store-restricted staff are kept apart; a missed
 * check is alerted once. Kafka and Consul disabled, so events are asserted in the outbox.
 */
@HelidonTest
class FoodSafetyIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "inventory");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    // The test drives the sweep itself, so a scheduled one cannot race its assertions.
    System.setProperty("shelfj.inventory.food-safety.overdue-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String OTHER = "01a090ae-611e-701d-9d60-a9d7516ed03b";
  private static final String MANAGER = "01a090ae-611e-7022-a4af-ac524c304167";
  private static final String STAFF = "01a090ae-611e-7023-be8d-9115b3480de8";

  private static final String CHILLED = "01a090a0-1bc3-70b9-a4f2-357a63f90f41";
  private static final String OPENING = "01a090a0-1bc3-70bf-ab53-eb45b1289166";

  @Inject WebTarget target;
  @Inject FoodSafetyService foodSafety;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the core flow ──────────────────────────────────────────────────────────

  @Test
  void aWarmChillerFailsAlertsTheStoreAndStaysOpenUntilSomethingIsDone() {
    String store = Ids.newId().toString();
    JsonObject point = created(createPoint(store, "Dairy chiller 1", CHILLED, "", 4));
    String pointId = point.getString("id");
    assertThat(point.getJsonNumber("maxValue").bigDecimalValue().toPlainString(), is("8.00"));
    assertThat(point.getString("dueStatus"), is("OK"));

    JsonObject pass = created(record(pointId, "\"value\":5.0", null, "STOREKEEPER", null));
    assertThat(pass.getString("result"), is("PASS"));

    JsonObject fail = created(record(pointId, "\"value\":9.5", null, "STOREKEEPER", null));
    assertThat(fail.getString("result"), is("FAIL"));
    assertThat(fail.getBoolean("openFailure"), is(true));
    // JSON-B omits a null field rather than writing null, so an absent bound has no key (SJ-D14).
    assertThat(fail.containsKey("minValue"), is(false));
    assertThat(fail.getJsonNumber("maxValue").bigDecimalValue().toPlainString(), is("8.00"));

    String payload = outboxPayload("FoodSafetyCheckFailed", fail.getString("id"));
    assertThat(payload, containsString("\"pointName\":\"Dairy chiller 1\""));
    assertThat(payload, containsString("\"value\":9.50"));
    assertThat(outboxPayload("FoodSafetyCheckFailed", pass.getString("id")), is(nullValue()));

    Response onPass = correct(pass.getString("id"), "STOREKEEPER", null);
    assertThat(onPass.getStatus(), is(409));
    assertThat(onPass.readEntity(String.class), containsString("FOOD_SAFETY_RECORD_PASSED"));
    assertThat(correct(fail.getString("id"), "STOREKEEPER", null).getStatus(), is(201));

    JsonObject detail =
        data(
            send(
                "GET",
                "/admin/inventory/food-safety/records/" + fail.getString("id"),
                null,
                T,
                "STOREKEEPER",
                STAFF,
                null,
                null));
    assertThat(detail.getBoolean("openFailure"), is(false));
    assertThat(detail.getJsonArray("correctiveActions").size(), is(1));

    JsonObject listed =
        dataArray(
                send(
                    "GET",
                    "/admin/inventory/food-safety/points?storeId=" + store,
                    null,
                    T,
                    "STOREKEEPER",
                    STAFF,
                    null,
                    null))
            .getJsonObject(0);
    assertThat(listed.getString("lastResult"), is("FAIL"));
    assertThat(listed.getJsonNumber("openFailures").longValue(), is(0L));
  }

  @Test
  void aPointCannotBeLaxerThanTheLawAndAPlatformTypeCannotBeEdited() {
    String store = Ids.newId().toString();
    Response laxer = createPoint(store, "Warm chiller", CHILLED, ",\"maxValue\":10", 4);
    assertThat(laxer.getStatus(), is(422));
    assertThat(laxer.readEntity(String.class), containsString("FOOD_SAFETY_LIMIT_LAXER_THAN_TYPE"));

    created(createPoint(store, "Cold chiller", CHILLED, ",\"maxValue\":5", 4));
    Response sameName = createPoint(store, "cold CHILLER", CHILLED, "", 4);
    assertThat(sameName.getStatus(), is(409));
    assertThat(sameName.readEntity(String.class), containsString("FOOD_SAFETY_POINT_NAME_TAKEN"));

    Response platform =
        send(
            "PUT",
            "/admin/food-safety/check-types/" + CHILLED,
            "{\"name\":\"Chilled\",\"maxValue\":12,\"active\":true}",
            T,
            "OWNER",
            MANAGER,
            null,
            null);
    assertThat(platform.getStatus(), is(409));
    assertThat(platform.readEntity(String.class), containsString("FOOD_SAFETY_TYPE_READ_ONLY"));

    Response ownWithLimits =
        send(
            "POST",
            "/admin/food-safety/check-types",
            "{\"code\":\"SLICER_CLEAN\",\"name\":\"Slicer cleaned\",\"kind\":\"PASS_FAIL\",\"maxValue\":1}",
            T,
            "OWNER",
            MANAGER,
            null,
            null);
    assertThat(ownWithLimits.getStatus(), is(400));
    JsonObject own =
        created(
            send(
                "POST",
                "/admin/food-safety/check-types",
                "{\"code\":\"SLICER_CLEAN\",\"name\":\"Slicer cleaned\",\"kind\":\"PASS_FAIL\"}",
                T,
                "OWNER",
                MANAGER,
                null,
                null));
    assertThat(own.getBoolean("platform"), is(false));
    assertThat(own.getBoolean("statutory"), is(false));
  }

  @Test
  void aRetriedCheckIsRecordedOnce() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Freezer", CHILLED, "", 4)).getString("id");
    String key = Ids.newId().toString();
    Response first = record(pointId, "\"value\":3", key, "STOREKEEPER", null);
    assertThat(first.getStatus(), is(201));
    String firstId = data(first).getString("id");
    Response second = record(pointId, "\"value\":3", key, "STOREKEEPER", null);
    assertThat(second.getStatus(), is(200));
    assertThat(data(second).getString("id"), is(firstId));
    assertThat(
        dataArray(
                send(
                    "GET",
                    "/admin/inventory/food-safety/records?pointId=" + pointId,
                    null,
                    T,
                    "OWNER",
                    MANAGER,
                    null,
                    null))
            .size(),
        is(1));
  }

  @Test
  void aPassFailCheckTakesAVerdictNotAReading() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Opening checks", OPENING, "", 24)).getString("id");
    Response withValue = record(pointId, "\"value\":1", null, "CASHIER", null);
    assertThat(withValue.getStatus(), is(400));
    assertThat(withValue.readEntity(String.class), containsString("FOOD_SAFETY_VALUE_NOT_ALLOWED"));
    Response noVerdict = record(pointId, "\"notes\":\"forgot\"", null, "CASHIER", null);
    assertThat(noVerdict.getStatus(), is(400));
    assertThat(
        created(record(pointId, "\"passed\":false", null, "CASHIER", null)).getString("result"),
        is("FAIL"));
  }

  // ── who may do what ────────────────────────────────────────────────────────

  @Test
  void onlyManagementSetsUpAndAnyStaffRecords() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Hot cabinet", CHILLED, "", 4)).getString("id");
    String point =
        "{\"storeId\":\""
            + store
            + "\",\"name\":\"X\",\"checkTypeId\":\""
            + CHILLED
            + "\",\"frequencyHours\":4}";

    assertThat(
        send("POST", "/admin/food-safety/points", point, T, "CASHIER", STAFF, null, null)
            .getStatus(),
        is(403));
    assertThat(
        send("POST", "/admin/food-safety/points", point, T, "STOREKEEPER", STAFF, null, null)
            .getStatus(),
        is(403));
    assertThat(
        send(
                "PUT",
                "/admin/food-safety/points/" + pointId,
                "{\"name\":\"X\",\"maxValue\":20,\"frequencyHours\":4}",
                T,
                "CASHIER",
                STAFF,
                null,
                null)
            .getStatus(),
        is(403));
    assertThat(
        send("GET", "/admin/food-safety/reviews", null, T, "CASHIER", STAFF, null, null)
            .getStatus(),
        is(403));

    assertThat(record(pointId, "\"value\":4", null, "CASHIER", null).getStatus(), is(201));
    assertThat(
        send(
                "GET",
                "/admin/inventory/food-safety/points?storeId=" + store,
                null,
                T,
                "CUSTOMER",
                STAFF,
                null,
                null)
            .getStatus(),
        is(403));
    assertThat(
        send(
                "GET",
                "/admin/inventory/food-safety/points?storeId=" + store,
                null,
                T,
                null,
                null,
                null,
                null)
            .getStatus(),
        is(403));
  }

  @Test
  void aStoreRestrictedMemberOfStaffCannotRecordAtAnotherStore() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Deli chiller", CHILLED, "", 4)).getString("id");
    Response elsewhere =
        record(pointId, "\"value\":4", null, "STOREKEEPER", Ids.newId().toString());
    assertThat(elsewhere.getStatus(), is(403));
    assertThat(elsewhere.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));
    assertThat(record(pointId, "\"value\":4", null, "STOREKEEPER", store).getStatus(), is(201));
  }

  @Test
  void anotherTenantCannotSeeOrUseTheRecords() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Bakery chiller", CHILLED, "", 4)).getString("id");
    String recordId =
        created(record(pointId, "\"value\":4", null, "STOREKEEPER", null)).getString("id");

    assertThat(
        send(
                "GET",
                "/admin/inventory/food-safety/records/" + recordId,
                null,
                OTHER,
                "OWNER",
                MANAGER,
                null,
                null)
            .getStatus(),
        is(404));
    assertThat(
        send(
                "POST",
                "/admin/inventory/food-safety/records",
                "{\"pointId\":\"" + pointId + "\",\"value\":4}",
                OTHER,
                "OWNER",
                MANAGER,
                null,
                null)
            .getStatus(),
        is(404));
    assertThat(
        dataArray(
                send(
                    "GET",
                    "/admin/inventory/food-safety/points?storeId=" + store,
                    null,
                    OTHER,
                    "OWNER",
                    MANAGER,
                    null,
                    null))
            .size(),
        is(0));
  }

  @Test
  void aSwitchedOffPointTakesNoChecksAndSwitchingItTwiceIsRefused() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Old freezer", CHILLED, "", 4)).getString("id");
    String reason = "{\"reason\":\"decommissioned\"}";
    JsonObject off =
        data(
            send(
                "POST",
                "/admin/food-safety/points/" + pointId + "/deactivate",
                reason,
                T,
                "OWNER",
                MANAGER,
                null,
                null));
    assertThat(off.getBoolean("active"), is(false));
    assertThat(
        send(
                "POST",
                "/admin/food-safety/points/" + pointId + "/deactivate",
                reason,
                T,
                "OWNER",
                MANAGER,
                null,
                null)
            .getStatus(),
        is(409));

    Response onOff = record(pointId, "\"value\":4", null, "STOREKEEPER", null);
    assertThat(onOff.getStatus(), is(409));
    assertThat(onOff.readEntity(String.class), containsString("FOOD_SAFETY_POINT_INACTIVE"));
    assertThat(
        send(
                "POST",
                "/admin/food-safety/points/" + pointId + "/activate",
                reason,
                T,
                "OWNER",
                MANAGER,
                null,
                null)
            .getStatus(),
        is(200));
  }

  // ── diary, review, sweep ───────────────────────────────────────────────────

  @Test
  void theDiaryPagesNewestFirstOverAHalfOpenWindow() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Salad bar", CHILLED, "", 4)).getString("id");
    for (String v : new String[] {"1", "2", "3"}) {
      created(record(pointId, "\"value\":" + v, null, "STOREKEEPER", null));
    }
    String base = "/admin/inventory/food-safety/records?storeId=" + store;
    Response firstPage = send("GET", base + "&limit=2", null, T, "OWNER", MANAGER, null, null);
    JsonObject body = json(firstPage);
    JsonArray items = body.getJsonArray("data");
    assertThat(items.size(), is(2));
    String newest = items.getJsonObject(0).getString("recordedAt");
    String cursor = body.getJsonObject("meta").getString("nextCursor");
    assertThat(cursor, notNullValue());

    JsonObject second =
        json(send("GET", base + "&limit=2&after=" + cursor, null, T, "OWNER", MANAGER, null, null));
    assertThat(second.getJsonArray("data").size(), is(1));
    assertThat(second.getJsonObject("meta").containsKey("nextCursor"), is(false));

    // to is exclusive: a window ending at the newest record's instant leaves that record out.
    assertThat(
        dataArray(send("GET", base + "&to=" + newest, null, T, "OWNER", MANAGER, null, null))
            .size(),
        is(2));
  }

  @Test
  void aReviewKeepsTheCountsItSignedOff() {
    String store = Ids.newId().toString();
    String pointId = created(createPoint(store, "Fish counter", CHILLED, "", 4)).getString("id");
    created(record(pointId, "\"value\":4", null, "STOREKEEPER", null));
    String failId =
        created(record(pointId, "\"value\":11", null, "STOREKEEPER", null)).getString("id");

    String window = "\"from\":\"2020-01-01T00:00:00Z\",\"to\":\"2100-01-01T00:00:00Z\"";
    JsonObject review =
        created(
            send(
                "POST",
                "/admin/food-safety/reviews",
                "{\"storeId\":\"" + store + "\"," + window + ",\"notes\":\"fine\"}",
                T,
                "OWNER",
                MANAGER,
                null,
                null));
    assertThat(review.getInt("recordsCount"), is(2));
    assertThat(review.getInt("failuresCount"), is(1));
    assertThat(review.getInt("openFailuresCount"), is(1));

    assertThat(correct(failId, "STOREKEEPER", null).getStatus(), is(201));
    JsonObject listed =
        dataArray(
                send(
                    "GET",
                    "/admin/food-safety/reviews?storeId=" + store,
                    null,
                    T,
                    "OWNER",
                    MANAGER,
                    null,
                    null))
            .getJsonObject(0);
    assertThat(listed.getInt("openFailuresCount"), is(1));
  }

  @Test
  void aMissedCheckIsAlertedOnce() throws Exception {
    String store = Ids.newId().toString();
    String pointId =
        created(createPoint(store, "Butchery chiller", CHILLED, "", 1)).getString("id");
    exec(
        "UPDATE inventory.fs_monitoring_points SET created_at = now() - interval '3 hours' WHERE id = '"
            + pointId
            + "'");

    foodSafety.sweepOverdue(500);
    foodSafety.sweepOverdue(500);
    assertThat(outboxCount("FoodSafetyCheckOverdue", pointId), is(1));

    JsonObject listed =
        dataArray(
                send(
                    "GET",
                    "/admin/inventory/food-safety/points?storeId=" + store,
                    null,
                    T,
                    "STOREKEEPER",
                    STAFF,
                    null,
                    null))
            .getJsonObject(0);
    assertThat(listed.getString("dueStatus"), is("OVERDUE"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Response createPoint(String store, String name, String typeId, String extra, int hours) {
    return send(
        "POST",
        "/admin/food-safety/points",
        "{\"storeId\":\""
            + store
            + "\",\"name\":\""
            + name
            + "\",\"checkTypeId\":\""
            + typeId
            + "\",\"frequencyHours\":"
            + hours
            + extra
            + "}",
        T,
        "OWNER",
        MANAGER,
        null,
        null);
  }

  private Response record(String pointId, String fields, String key, String role, String storeIds) {
    return send(
        "POST",
        "/admin/inventory/food-safety/records",
        "{\"pointId\":\"" + pointId + "\"," + fields + "}",
        T,
        role,
        STAFF,
        storeIds,
        key);
  }

  private Response correct(String recordId, String role, String storeIds) {
    return send(
        "POST",
        "/admin/inventory/food-safety/records/" + recordId + "/corrective-actions",
        "{\"action\":\"Moved stock to the walk-in, called the engineer\",\"foodDisposition\":\"MOVED\"}",
        T,
        role,
        STAFF,
        storeIds,
        null);
  }

  private Response send(
      String method,
      String path,
      String json,
      String tenant,
      String roles,
      String userId,
      String storeIds,
      String idempotencyKey) {
    String[] parts = path.split("\\?", 2);
    WebTarget t = target.path(parts[0]);
    if (parts.length == 2) {
      for (String pair : parts[1].split("&")) {
        String[] kv = pair.split("=", 2);
        t = t.queryParam(kv[0], kv[1]);
      }
    }
    Invocation.Builder b = t.request().header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (userId != null) b = b.header("X-User-Id", userId);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    if (idempotencyKey != null) b = b.header("Idempotency-Key", idempotencyKey);
    return json == null
        ? b.method(method)
        : b.method(method, Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject created(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return parse(body).getJsonObject("data");
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus() < 300, is(true));
    return parse(body).getJsonObject("data");
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonArray("data");
  }

  private static JsonObject json(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body);
  }

  private static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  private static String outboxPayload(String eventType, String aggregateId) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT payload FROM inventory.outbox WHERE event_type = ? AND aggregate_id = ?::uuid")) {
      ps.setString(1, eventType);
      ps.setString(2, aggregateId);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int outboxCount(String eventType, String aggregateId) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM inventory.outbox WHERE event_type = ? AND aggregate_id = ?::uuid")) {
      ps.setString(1, eventType);
      ps.setString(2, aggregateId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static void exec(String sql) throws Exception {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement()) {
      st.executeUpdate(sql);
    }
  }
}
