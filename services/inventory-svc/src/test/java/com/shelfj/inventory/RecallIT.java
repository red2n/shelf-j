package com.shelfj.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

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
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Recalls against real Postgres: opening one takes every pack in scope off sale at every store,
 * holds a pack it cannot rule out, and holds stock that arrives later; a store's final disposition
 * takes the stock off the books; a recall closes only when no store still holds recalled stock;
 * cancelling puts stock back only where nothing else holds it. Kafka and Consul disabled, so events
 * are asserted in the outbox.
 */
@HelidonTest
class RecallIT {

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
    System.setProperty("shelfj.inventory.food-safety.overdue-sweeper.enabled", "false");
  }

  private static final String T = "22222222-2222-2222-2222-222222222222";
  private static final String OTHER = "99999999-9999-9999-9999-999999999999";
  private static final String MANAGER = "b1000000-0000-0000-0000-000000000001";
  private static final String STAFF = "b1000000-0000-0000-0000-000000000002";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── opening ────────────────────────────────────────────────────────────────

  @Test
  void aRecallTakesEveryPackInScopeOffSaleAtEveryStoreAndTheTillSeesIt() {
    String storeA = uuid();
    String storeB = uuid();
    String variant = uuid();
    String aL1 = receive(storeA, variant, "10", "L1", "2026-10-01");
    String aL2 = receive(storeA, variant, "5", "L2", "2026-10-01");
    String bL1 = receive(storeB, variant, "3", "l1", null);
    String bUnknown = receive(storeB, variant, "4", null, "2026-10-01");

    JsonObject recall = created(open("FSA-PRIN-01", "RECALL", lotLine(variant, "L1")));
    Map<String, JsonObject> held = heldByBatch(recall);
    assertThat(held.size(), is(3));
    assertThat(held.get(aL1).getString("match"), is("IN_SCOPE"));
    assertThat(held.get(bL1).getString("match"), is("IN_SCOPE"));
    assertThat(held.get(bUnknown).getString("match"), is("LOT_UNKNOWN"));
    assertThat(held.containsKey(aL2), is(false));

    assertThat(materialStatus(aL1), is("RECALLED"));
    assertThat(materialStatus(bUnknown), is("RECALLED"));
    assertThat(materialStatus(aL2), is("AVAILABLE"));
    assertThat(onHand(storeA, variant), is("5.000"));
    assertThat(onHand(storeB, variant), is("0"));

    String payload = outboxPayload("RecallOpened", recall.getString("id"));
    assertThat(payload, containsString(storeA));
    assertThat(payload, containsString(storeB));
    assertThat(payload, containsString("\"reference\":\"FSA-PRIN-01\""));

    JsonArray active =
        dataArray(send("GET", "/admin/inventory/recalls/active", null, T, "CASHIER", STAFF, null));
    JsonObject line = find(active, "recallId", recall.getString("id"));
    assertThat(line.getString("variantId"), is(variant));
    assertThat(line.getString("batchNo"), is("L1"));
    assertThat(line.getString("kind"), is("RECALL"));
    assertThat(line.getString("customerNotice"), containsString("Do not eat"));
  }

  @Test
  void stockArrivingUnderAnOpenRecallIsHeldAsItArrives() {
    String store = uuid();
    String variant = uuid();
    receive(store, variant, "2", "L7", "2026-10-15");
    String recallId =
        created(
                open(
                    "SUP-7",
                    "WITHDRAWAL",
                    "{\"variantId\":\""
                        + variant
                        + "\",\"expiryFrom\":\"2026-10-01\",\"expiryTo\":\"2026-10-31\"}"))
            .getString("id");

    JsonObject late =
        created(
            send(
                "POST",
                "/admin/inventory/receive",
                receiveJson(store, variant, "6", "L8", "2026-10-20"),
                T,
                "STOREKEEPER",
                STAFF,
                null));
    assertThat(late.getString("materialStatus"), is("RECALLED"));
    assertThat(late.getString("materialStatusReason"), is("Recall SUP-7"));
    JsonObject outOfScope =
        created(
            send(
                "POST",
                "/admin/inventory/receive",
                receiveJson(store, variant, "6", "L9", "2026-11-20"),
                T,
                "STOREKEEPER",
                STAFF,
                null));
    assertThat(outOfScope.getString("materialStatus"), is("AVAILABLE"));

    JsonObject arrived = heldByBatch(get(recallId)).get(late.getString("id"));
    assertThat(arrived.getString("quarantinedOn"), is("ARRIVAL"));
    assertThat(arrived.getString("match"), is("IN_SCOPE"));
  }

  // ── what stores do ─────────────────────────────────────────────────────────

  @Test
  void anUncertainBatchCanBeReleasedAndOneInScopeCannot() {
    String store = uuid();
    String variant = uuid();
    String inScope = receive(store, variant, "3", "L1", null);
    String unknown = receive(store, variant, "4", null, null);
    String recallId = created(open("REL-1", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");

    Response certain = release(recallId, inScope, "STOREKEEPER", null);
    assertThat(certain.getStatus(), is(409));
    assertThat(certain.readEntity(String.class), containsString("RECALL_BATCH_IN_SCOPE"));

    assertThat(release(recallId, unknown, "STOREKEEPER", store).getStatus(), is(200));
    assertThat(materialStatus(unknown), is("AVAILABLE"));
    assertThat(onHand(store, variant), is("4.000"));

    Response again = release(recallId, unknown, "STOREKEEPER", null);
    assertThat(again.getStatus(), is(409));
    assertThat(again.readEntity(String.class), containsString("RECALL_BATCH_ALREADY_RELEASED"));

    Response otherStore = release(recallId, unknown, "STOREKEEPER", uuid());
    assertThat(otherStore.getStatus(), is(403));
  }

  @Test
  void aRecallClosesOnlyWhenNoStoreStillHoldsRecalledStock() throws Exception {
    String storeA = uuid();
    String storeB = uuid();
    String variant = uuid();
    String aBatch = receive(storeA, variant, "10", "L1", null);
    receive(storeB, variant, "3", "L1", null);
    String recallId = created(open("CLOSE-1", "RECALL", lotLine(variant, "L1"))).getString("id");

    Response early = close(recallId);
    assertThat(early.getStatus(), is(409));
    String earlyBody = early.readEntity(String.class);
    assertThat(earlyBody, containsString("RECALL_STORES_OUTSTANDING"));
    assertThat(earlyBody, containsString(storeA));
    assertThat(earlyBody, containsString(storeB));

    JsonObject held = created(action(recallId, storeA, "10", "HELD_FOR_COLLECTION", storeA));
    assertThat(held.getJsonNumber("systemQty").bigDecimalValue().toPlainString(), is("10.000"));
    assertThat(remainingQty(aBatch), is("10.000"));

    JsonObject destroyed = created(action(recallId, storeA, "9", "DESTROYED", storeA));
    assertThat(
        destroyed.getJsonNumber("systemQty").bigDecimalValue().toPlainString(), is("10.000"));
    assertThat(remainingQty(aBatch), is("0.000"));
    assertThat(
        scalar(
            "SELECT qty || '|' || ref_type || '|' || reason_code || '|' || actor_id"
                + " FROM inventory.stock_movements WHERE batch_id = '"
                + aBatch
                + "' AND type = 'ADJUST'"),
        is("-10.000|RECALL|RECALL_WITHDRAWAL|" + STAFF));
    assertThat(
        scalar(
            "SELECT count(*) FROM inventory.outbox WHERE event_type = 'StockAdjusted'"
                + " AND payload LIKE '%"
                + storeA
                + "%' AND payload LIKE '%\"delta\":-10.000%'"),
        is("1"));

    Response stillB = close(recallId);
    assertThat(stillB.getStatus(), is(409));
    assertThat(stillB.readEntity(String.class), containsString(storeB));

    created(action(recallId, storeB, "3", "RETURNED_TO_SUPPLIER", null));
    JsonObject closed = data(close(recallId));
    assertThat(closed.getString("status"), is("CLOSED"));
    JsonObject progressA = find(closed.getJsonArray("stores"), "storeId", storeA);
    assertThat(progressA.getJsonNumber("qtyFound").bigDecimalValue().toPlainString(), is("9.000"));
    assertThat(progressA.getBoolean("outstanding"), is(false));

    JsonArray active =
        dataArray(send("GET", "/admin/inventory/recalls/active", null, T, "CASHIER", STAFF, null));
    assertThat(
        active.stream().anyMatch(v -> recallId.equals(v.asJsonObject().getString("recallId"))),
        is(false));

    Response afterClose = action(recallId, storeA, "0", "DESTROYED", null);
    assertThat(afterClose.getStatus(), is(409));
    assertThat(afterClose.readEntity(String.class), containsString("RECALL_NOT_OPEN"));
  }

  // ── cancelling ─────────────────────────────────────────────────────────────

  @Test
  void cancellingPutsStockBackOnlyWhereNoOtherRecallHoldsIt() {
    String store = uuid();
    String variant = uuid();
    String batch = receive(store, variant, "8", "L1", null);
    String every =
        created(open("CANCEL-EVERY", "WITHDRAWAL", "{\"variantId\":\"" + variant + "\"}"))
            .getString("id");
    String byLot =
        created(open("CANCEL-LOT", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");

    assertThat(cancel(every).getStatus(), is(200));
    assertThat(materialStatus(batch), is("RECALLED"));
    assertThat(data(cancel(byLot)).getString("status"), is("CANCELLED"));
    assertThat(materialStatus(batch), is("AVAILABLE"));
    assertThat(onHand(store, variant), is("8.000"));
  }

  @Test
  void aRecallUnderWhichStockWasDisposedOfCannotBeCancelled() {
    String store = uuid();
    String variant = uuid();
    receive(store, variant, "2", "L1", null);
    String recallId =
        created(open("NO-UNDO", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");
    created(action(recallId, store, "2", "DESTROYED", null));

    Response cancel = cancel(recallId);
    assertThat(cancel.getStatus(), is(409));
    assertThat(cancel.readEntity(String.class), containsString("RECALL_ALREADY_ACTIONED"));
  }

  // ── who may do what ────────────────────────────────────────────────────────

  @Test
  void openingIsManagementWorkAndABadNoticeIsRefused() {
    String variant = uuid();
    Response cashier =
        send(
            "POST",
            "/admin/recalls",
            openJson("ROLE-1", "WITHDRAWAL", lotLine(variant, "L1"), null),
            T,
            "CASHIER",
            STAFF,
            null);
    assertThat(cashier.getStatus(), is(403));
    Response storekeeper =
        send(
            "POST",
            "/admin/recalls",
            openJson("ROLE-1", "WITHDRAWAL", lotLine(variant, "L1"), null),
            T,
            "STOREKEEPER",
            STAFF,
            null);
    assertThat(storekeeper.getStatus(), is(403));

    created(open("Dup-Ref", "WITHDRAWAL", lotLine(variant, "L1")));
    Response duplicate = open("dup-ref", "WITHDRAWAL", lotLine(variant, "L2"));
    assertThat(duplicate.getStatus(), is(409));
    assertThat(duplicate.readEntity(String.class), containsString("RECALL_REFERENCE_TAKEN"));

    Response noNotice =
        send(
            "POST",
            "/admin/recalls",
            openJson("NOTICE-1", "RECALL", lotLine(variant, "L1"), null),
            T,
            "OWNER",
            MANAGER,
            null);
    assertThat(noNotice.getStatus(), is(400));
    assertThat(noNotice.readEntity(String.class), containsString("RECALL_NOTICE_REQUIRED"));

    Response inverted =
        open(
            "DATES-1",
            "WITHDRAWAL",
            "{\"variantId\":\""
                + variant
                + "\",\"expiryFrom\":\"2026-10-31\",\"expiryTo\":\"2026-10-01\"}");
    assertThat(inverted.getStatus(), is(400));
    assertThat(inverted.readEntity(String.class), containsString("RECALL_DATES_INVERTED"));
  }

  @Test
  void aTenantNeitherSeesNorHoldsAnotherTenantsStock() {
    String store = uuid();
    String variant = uuid();
    String batch = receive(store, variant, "5", "L1", null);
    String recallId =
        created(open("TENANT-1", "WITHDRAWAL", lotLine(variant, "L1"))).getString("id");

    Response foreignRead =
        send("GET", "/admin/inventory/recalls/" + recallId, null, OTHER, "OWNER", MANAGER, null);
    assertThat(foreignRead.getStatus(), is(404));
    JsonArray foreignActive =
        dataArray(
            send("GET", "/admin/inventory/recalls/active", null, OTHER, "CASHIER", STAFF, null));
    assertThat(foreignActive.size(), is(0));

    String foreignBatch =
        created(
                send(
                    "POST",
                    "/admin/inventory/receive",
                    receiveJson(store, variant, "5", "L1", null),
                    OTHER,
                    "STOREKEEPER",
                    STAFF,
                    null))
            .getString("id");
    assertThat(materialStatus(foreignBatch), is("AVAILABLE"));
    assertThat(materialStatus(batch), is("RECALLED"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private Response open(String reference, String kind, String line) {
    return send(
        "POST",
        "/admin/recalls",
        openJson(reference, kind, line, "Do not eat. Return it to the store for a full refund."),
        T,
        "OWNER",
        MANAGER,
        null);
  }

  private static String openJson(String reference, String kind, String line, String notice) {
    return "{\"reference\":\""
        + reference
        + "\",\"kind\":\""
        + kind
        + "\",\"hazard\":\"ALLERGEN\",\"reason\":\"Undeclared peanut\",\"source\":\"FSA\""
        + (notice == null ? "" : ",\"customerNotice\":\"" + notice + "\"")
        + ",\"items\":["
        + line
        + "]}";
  }

  private static String lotLine(String variant, String lot) {
    return "{\"variantId\":\"" + variant + "\",\"batchNo\":\"" + lot + "\"}";
  }

  private JsonObject get(String recallId) {
    return data(
        send("GET", "/admin/inventory/recalls/" + recallId, null, T, "STOREKEEPER", STAFF, null));
  }

  private Response action(
      String recallId, String store, String qty, String disposition, String storeIds) {
    return send(
        "POST",
        "/admin/inventory/recalls/" + recallId + "/stores/" + store + "/actions",
        "{\"qtyFound\":"
            + qty
            + ",\"disposition\":\""
            + disposition
            + "\",\"noticeDisplayed\":true,\"notes\":\"Pulled from aisle 4\"}",
        T,
        "STOREKEEPER",
        STAFF,
        storeIds);
  }

  private Response release(String recallId, String batch, String role, String storeIds) {
    return send(
        "POST",
        "/admin/inventory/recalls/" + recallId + "/batches/" + batch + "/release",
        "{\"reason\":\"Pack shows lot L4, not L1\"}",
        T,
        role,
        STAFF,
        storeIds);
  }

  private Response close(String recallId) {
    return send(
        "POST",
        "/admin/recalls/" + recallId + "/close",
        "{\"notes\":\"Recovered 12 of 13\"}",
        T,
        "MANAGER",
        MANAGER,
        null);
  }

  private Response cancel(String recallId) {
    return send(
        "POST",
        "/admin/recalls/" + recallId + "/cancel",
        "{\"reason\":\"Opened against the wrong product\"}",
        T,
        "OWNER",
        MANAGER,
        null);
  }

  private String receive(String store, String variant, String qty, String lot, String expiry) {
    return created(
            send(
                "POST",
                "/admin/inventory/receive",
                receiveJson(store, variant, qty, lot, expiry),
                T,
                "STOREKEEPER",
                STAFF,
                null))
        .getString("id");
  }

  private static String receiveJson(
      String store, String variant, String qty, String lot, String expiry) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + variant
        + "\",\"qty\":"
        + qty
        + ",\"costPrice\":1.20"
        + (lot == null ? "" : ",\"batchNo\":\"" + lot + "\"")
        + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
        + "}";
  }

  private static Map<String, JsonObject> heldByBatch(JsonObject recall) {
    Map<String, JsonObject> out = new HashMap<>();
    for (var v : recall.getJsonArray("batches")) {
      out.put(v.asJsonObject().getString("batchId"), v.asJsonObject());
    }
    return out;
  }

  private static JsonObject find(JsonArray array, String key, String value) {
    return array.stream()
        .map(v -> v.asJsonObject())
        .filter(o -> value.equals(o.getString(key)))
        .findFirst()
        .orElseThrow(() -> new AssertionError(key + "=" + value + " not in " + array));
  }

  private Response send(
      String method,
      String path,
      String json,
      String tenant,
      String roles,
      String userId,
      String storeIds) {
    Invocation.Builder b =
        target.path(path).request().header("X-Tenant-Id", tenant).header("X-Roles", roles);
    b = b.header("X-User-Id", userId);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
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
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonObject("data");
  }

  private static JsonArray dataArray(Response r) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return parse(body).getJsonArray("data");
  }

  private static JsonObject parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject();
    }
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  private static String materialStatus(String batchId) {
    return scalar(
        "SELECT material_status FROM inventory.inventory_batches WHERE id = '" + batchId + "'");
  }

  private static String remainingQty(String batchId) {
    return scalar(
        "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '" + batchId + "'");
  }

  private static String onHand(String store, String variant) {
    return scalar(
        "SELECT COALESCE(SUM(remaining_qty), 0) FROM inventory.inventory_batches"
            + " WHERE store_id = '"
            + store
            + "' AND variant_id = '"
            + variant
            + "' AND material_status = 'AVAILABLE'");
  }

  private static String outboxPayload(String eventType, String aggregateId) {
    return scalar(
        "SELECT payload FROM inventory.outbox WHERE event_type = '"
            + eventType
            + "' AND aggregate_id = '"
            + aggregateId
            + "'");
  }

  private static String scalar(String sql) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      if (!rs.next()) {
        return null;
      }
      Object value = rs.getObject(1);
      return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
