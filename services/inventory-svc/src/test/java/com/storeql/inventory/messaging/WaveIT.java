package com.storeql.inventory.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wave picking (readiness review, Supply chain, warehouse &amp; logistics).
 *
 * <p>Confirmed online orders wait at their store; a wave gathers them into one walk through the
 * zones, directed to the batch the picking rule chooses and naming the orders each line serves;
 * completing it deducts exactly what was picked, consumes the holds, tells order-svc to fulfil the
 * orders, and a later OrderFulfilled for those lines deducts nothing twice. Written before the
 * code.
 */
@HelidonTest
class WaveIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "inventory");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a092ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a092ae-611e-702c-a97b-d1b8025478e2";
  private static final String STORE = "01a092ae-611e-703c-a378-a4972ea461e1";
  private static final String OTHER_STORE = "01a092ae-611e-703c-a378-a4972ea461e2";
  private static final String APPLES = "01a092ae-611e-7037-a4b7-c854f0266ae1";
  private static final String PEARS = "01a092ae-611e-7037-a4b7-c854f0266ae2";
  private static final String ZONE_A = "01a092ae-611e-7041-a4b7-c854f0266aa1";
  private static final String ZONE_B = "01a092ae-611e-7041-a4b7-c854f0266aa2";
  private static final String USER = "01a092ae-611e-700b-bde4-50df0324c3e1";

  @Inject WebTarget target;
  @Inject OrderEventHandler orders;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE inventory.wave_picked_lines, inventory.pick_wave_allocations,"
              + " inventory.pick_wave_lines, inventory.pick_waves, inventory.awaiting_order_lines,"
              + " inventory.awaiting_orders, inventory.putaway_tasks, inventory.putaway_rules,"
              + " inventory.picking_rule_zone_priorities, inventory.picking_rule_assignments,"
              + " inventory.picking_rules, inventory.lot_genealogy, inventory.stock_movements,"
              + " inventory.reservations, inventory.sale_revenue, inventory.inventory_batches,"
              + " inventory.processed_events, inventory.outbox CASCADE");
    }
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Response call(String method, String path, String json, String tenant, String roles) {
    return call(method, path, json, tenant, roles, null);
  }

  private Response call(
      String method, String path, String json, String tenant, String roles, String storeIds) {
    return call(method, path, json, tenant, roles, storeIds, null);
  }

  private Response call(
      String method,
      String path,
      String json,
      String tenant,
      String roles,
      String storeIds,
      String idempotencyKey) {
    var b =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", roles);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    if ("POST".equals(method)) {
      b =
          b.header(
              "Idempotency-Key", idempotencyKey != null ? idempotencyKey : Ids.newId().toString());
    }
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private Response post(String path, String json) {
    return call("POST", path, json, T, "STOREKEEPER");
  }

  private Response get(String path) {
    return call("GET", path, null, T, "OWNER");
  }

  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  private static BigDecimal num(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private static JsonObject find(JsonArray rows, String field, String value) {
    for (JsonValue v : rows) {
      if (value.equals(v.asJsonObject().getString(field, null))) return v.asJsonObject();
    }
    throw new AssertionError("no row with " + field + " = " + value + " in " + rows);
  }

  /** Stock on the shelf, placed in a zone, with a lot and a date. */
  private JsonObject receive(String variant, int qty, String zone, String lot, String expiry) {
    return Envelopes.created(
        call(
            "POST",
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + STORE
                + "\",\"variantId\":\""
                + variant
                + "\",\"qty\":"
                + qty
                + ",\"batchNo\":\""
                + lot
                + "\",\"costPrice\":1.00"
                + (zone == null ? "" : ",\"zoneId\":\"" + zone + "\"")
                + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
                + "}",
            T,
            "OWNER"));
  }

  /** The hold checkout places for a line, as order-svc does. */
  private void hold(String orderId, String variant, int qty) {
    assertThat(
        post(
                "/inventory/reservations",
                "{\"storeId\":\""
                    + STORE
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + ",\"orderId\":\""
                    + orderId
                    + "\"}")
            .getStatus(),
        is(201));
  }

  private static String confirmed(
      String eventId,
      String tenant,
      String orderId,
      String store,
      String fulfilment,
      String lines) {
    return confirmed(eventId, tenant, orderId, store, fulfilment, lines, null);
  }

  /** As above, saying when order-svc confirmed the order ({@code occurredAt}). */
  private static String confirmed(
      String eventId,
      String tenant,
      String orderId,
      String store,
      String fulfilment,
      String lines,
      String occurredAt) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderConfirmed\","
        + (occurredAt == null ? "" : "\"occurredAt\":\"" + occurredAt + "\",")
        + "\"tenantId\":\""
        + tenant
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + store
        + "\",\"channel\":\"ONLINE\",\"customerId\":null,\"total\":10.00,\"taxAmount\":0,"
        + "\"currency\":\"GBP\",\"fulfilmentType\":\""
        + fulfilment
        + "\",\"lines\":["
        + lines
        + "]}";
  }

  private static String line(String variant, int qty) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + ",\"unitPrice\":2.50}";
  }

  private static String fulfilled(String eventId, String orderId, String items) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
        + T
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + STORE
        + "\",\"items\":["
        + items
        + "]}";
  }

  private static String item(String variant, int qty, String net) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + ",\"netAmount\":" + net + "}";
  }

  private BigDecimal onHand(String variant) {
    JsonArray levels = Envelopes.okArray(get("/admin/inventory/levels?store=" + STORE));
    for (JsonValue v : levels) {
      if (variant.equals(v.asJsonObject().getString("variantId"))) {
        return v.asJsonObject().getJsonNumber("onHand").bigDecimalValue();
      }
    }
    return BigDecimal.ZERO;
  }

  /** When order-svc confirmed order 1 and, five seconds later, order 2. */
  private static final String CONFIRMED_1 = "2026-09-20T09:00:00Z";

  private static final String CONFIRMED_2 = "2026-09-20T09:00:05Z";

  /**
   * Two orders confirmed for the store: order 1 wants 3 apples and 2 pears, order 2 wants 4 apples.
   * Order 2's confirmation arrives first — two confirmations ride different partitions and land in
   * either order — yet order 1 was confirmed first and waits first.
   */
  private String[] twoOrdersWaiting() {
    String order1 = Ids.newId().toString();
    String order2 = Ids.newId().toString();
    hold(order1, APPLES, 3);
    hold(order1, PEARS, 2);
    hold(order2, APPLES, 4);
    orders.handle(
        confirmed(
            Ids.newId().toString(), T, order2, STORE, "PICKUP", line(APPLES, 4), CONFIRMED_2));
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            order1,
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2),
            CONFIRMED_1));
    return new String[] {order1, order2};
  }

  // ── confirmed orders wait at the store ─────────────────────────────────────

  @Test
  void confirmedOrdersWaitAtTheStoreOnce() {
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    // The same confirmation again projects nothing twice; a till sale and a delivery at another
    // store are not this store's to pick.
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            ids[0],
            STORE,
            "DELIVERY",
            line(APPLES, 3) + "," + line(PEARS, 2)));
    String till = Ids.newId().toString();
    orders.handle(
        confirmed(Ids.newId().toString(), T, till, STORE, "INSTORE", line(APPLES, 1))
            .replace("\"channel\":\"ONLINE\"", "\"channel\":\"POS\""));
    orders.handle(
        confirmed(
            Ids.newId().toString(),
            T,
            Ids.newId().toString(),
            OTHER_STORE,
            "DELIVERY",
            line(APPLES, 1)));

    JsonArray waiting = Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE));
    assertThat(waiting.size(), is(2));
    // Listed by when order-svc confirmed them, not by when the confirmations arrived here.
    assertThat(waiting.getJsonObject(0).getString("orderId"), is(ids[0]));
    assertThat(waiting.getJsonObject(1).getString("orderId"), is(ids[1]));
    JsonObject first = find(waiting, "orderId", ids[0]);
    assertThat(Instant.parse(first.getString("confirmedAt")), is(Instant.parse(CONFIRMED_1)));
    assertThat(first.getString("fulfilmentType"), is("DELIVERY"));
    assertThat(first.getJsonArray("lines").size(), is(2));
    assertThat(
        num(find(first.getJsonArray("lines"), "variantId", APPLES), "qtyOutstanding"),
        comparesEqualTo(new BigDecimal("3")));

    // A cancelled order leaves the list; a fulfilled one too.
    orders.handle(
        "{\"eventType\":\"OrderCancelled\",\"tenantId\":\""
            + T
            + "\",\"orderId\":\""
            + ids[1]
            + "\",\"reason\":\"changed mind\"}");
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(1));
    orders.handle(
        fulfilled(
            Ids.newId().toString(),
            ids[0],
            item(APPLES, 3, "7.50") + "," + item(PEARS, 2, "5.00")));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
    // Fulfilled by hand, the stock left the ordinary way.
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("17")));
  }

  // ── a wave is one walk through the zones ───────────────────────────────────

  @Test
  void aWaveIsOneWalkThroughTheZonesDirectedByTheRule() {
    // Older apples in zone B, newer apples in zone A; pears in zone A. FEFO by default.
    JsonObject oldApples = receive(APPLES, 5, ZONE_B, "A-OLD", "2026-10-01");
    JsonObject newApples = receive(APPLES, 10, ZONE_A, "A-NEW", "2026-11-01");
    JsonObject pears = receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();

    String key = Ids.newId().toString();
    String build = "{\"storeId\":\"" + STORE + "\"}";
    JsonObject wave =
        Envelopes.created(
            call("POST", "/admin/inventory/waves", build, T, "STOREKEEPER", null, key));
    assertThat(wave.getString("status"), is("OPEN"));
    assertThat(wave.getInt("orderCount"), is(2));
    JsonArray lines = wave.getJsonArray("lines");
    assertThat(lines.size(), is(3));
    // Zone A first (new apples for order 2's remainder, the pears), zone B last (the old apples
    // that both orders draw first, FEFO).
    assertThat(lines.getJsonObject(0).getString("zoneId"), is(ZONE_A));
    assertThat(lines.getJsonObject(1).getString("zoneId"), is(ZONE_A));
    JsonObject last = lines.getJsonObject(2);
    assertThat(last.getString("zoneId"), is(ZONE_B));
    assertThat(last.getString("batchId"), is(oldApples.getString("id")));
    assertThat(last.getString("batchNo"), is("A-OLD"));
    assertThat(num(last, "directedQty"), comparesEqualTo(new BigDecimal("5")));
    JsonArray served = last.getJsonArray("orders");
    assertThat(served.size(), is(2));
    assertThat(num(find(served, "orderId", ids[0]), "qty"), comparesEqualTo(new BigDecimal("3")));
    assertThat(num(find(served, "orderId", ids[1]), "qty"), comparesEqualTo(new BigDecimal("2")));
    JsonObject newLine = find(lines, "batchId", newApples.getString("id"));
    assertThat(num(newLine, "directedQty"), comparesEqualTo(new BigDecimal("2")));
    assertThat(find(lines, "batchId", pears.getString("id")).getInt("walkOrder") <= 2, is(true));

    // Nothing else waits, so a second wave has nothing to pick; the orders are in this wave.
    assertThat(
        code(post("/admin/inventory/waves", build), 409), is("INVENTORY_WAVE_NOTHING_TO_PICK"));
    // The same key again is the same wave, even now that nothing waits: the key is looked up
    // before the waiting list is judged.
    assertThat(
        Envelopes.created(
                call("POST", "/admin/inventory/waves", build, T, "STOREKEEPER", null, key))
            .getString("id"),
        is(wave.getString("id")));
    assertThat(
        code(
            post(
                "/admin/inventory/waves",
                "{\"storeId\":\"" + STORE + "\",\"orderIds\":[\"" + ids[0] + "\"]}"),
            409),
        is("INVENTORY_WAVE_ORDER_IN_ANOTHER_WAVE"));
    // Listed for the store; read back whole; cancelled, the orders wait again and nothing moved.
    assertThat(Envelopes.okArray(get("/admin/inventory/waves?storeId=" + STORE)).size(), is(1));
    assertThat(
        Envelopes.ok(get("/admin/inventory/waves/" + wave.getString("id")))
            .getJsonArray("lines")
            .size(),
        is(3));
    assertThat(
        Envelopes.ok(post("/admin/inventory/waves/" + wave.getString("id") + "/cancel", "{}"))
            .getString("status"),
        is("CANCELLED"));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(2));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("15")));
    assertThat(
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"))
            .getJsonArray("lines")
            .size(),
        is(3));
  }

  // ── completing deducts what was picked and tells order-svc ─────────────────

  @Test
  void completingAWaveDeductsWhatWasPickedConsumesTheHoldsAndTellsOrderSvcOnce() {
    JsonObject oldApples = receive(APPLES, 5, ZONE_B, "A-OLD", "2026-10-01");
    receive(APPLES, 10, ZONE_A, "A-NEW", "2026-11-01");
    receive(PEARS, 8, ZONE_A, "P-1", null);
    String[] ids = twoOrdersWaiting();
    JsonObject wave =
        Envelopes.created(post("/admin/inventory/waves", "{\"storeId\":\"" + STORE + "\"}"));
    String waveId = wave.getString("id");
    JsonArray lines = wave.getJsonArray("lines");
    JsonObject oldLine = find(lines, "batchId", oldApples.getString("id"));

    // The old apples are picked short: 4 of the 5 directed. Everything else in full.
    StringBuilder picks = new StringBuilder();
    for (JsonValue v : lines) {
      JsonObject l = v.asJsonObject();
      boolean shortLine = l.getString("id").equals(oldLine.getString("id"));
      picks
          .append(picks.length() == 0 ? "" : ",")
          .append("{\"lineId\":\"")
          .append(l.getString("id"))
          .append("\",\"pickedQty\":")
          .append(shortLine ? "4" : l.getJsonNumber("directedQty").toString())
          .append("}");
    }
    assertThat(
        code(
            post(
                "/admin/inventory/waves/" + waveId + "/picks",
                "{\"lines\":[{\"lineId\":\"" + oldLine.getString("id") + "\",\"pickedQty\":9}]}"),
            400),
        is("INVENTORY_WAVE_PICK_EXCEEDS_LINE"));
    JsonObject picked =
        Envelopes.ok(
            post("/admin/inventory/waves/" + waveId + "/picks", "{\"lines\":[" + picks + "]}"));
    assertThat(
        num(find(picked.getJsonArray("lines"), "batchId", oldApples.getString("id")), "pickedQty"),
        comparesEqualTo(new BigDecimal("4")));

    JsonObject done = Envelopes.ok(post("/admin/inventory/waves/" + waveId + "/complete", "{}"));
    assertThat(done.getString("status"), is("COMPLETED"));
    // Order 1 (the earlier) got its 3 old apples; order 2 got 1 of its 2 from the old batch plus
    // 2 from the new: 15 apples became 15 - 6 = 9; 8 pears became 6.
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("9")));
    assertThat(onHand(PEARS), comparesEqualTo(new BigDecimal("6")));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '"
                + oldApples.getString("id")
                + "'"),
        is("1.000"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE type = 'SALE' AND ref_type ="
                + " 'ORDER' AND ref_id = '"
                + ids[1]
                + "'"),
        is("2"));
    // Order 1's holds are consumed; order 2's apple hold keeps the 1 still to come.
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.reservations WHERE order_id = '"
                + ids[0]
                + "' AND status = 'HELD'"),
        is("0"));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT qty FROM inventory.reservations WHERE order_id = '"
                + ids[1]
                + "' AND status = 'HELD'"),
        is("1.000"));
    // Order 2 still waits for one apple; order 1 waits for nothing.
    JsonArray waiting = Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE));
    assertThat(waiting.size(), is(1));
    assertThat(
        num(
            find(find(waiting, "orderId", ids[1]).getJsonArray("lines"), "variantId", APPLES),
            "qtyOutstanding"),
        comparesEqualTo(new BigDecimal("1")));
    // order-svc is told what to fulfil, with the picked quantities.
    String announced =
        Envelopes.scalar(
            PG,
            "SELECT string_agg(payload, '|') FROM inventory.outbox WHERE event_type = 'WavePicked'");
    assertThat(announced, containsString("\"waveId\":\"" + waveId + "\""));
    assertThat(announced, containsString("\"orderId\":\"" + ids[0] + "\""));
    assertThat(announced, containsString("\"variantId\":\"" + PEARS + "\",\"qty\":2"));
    assertThat(announced, containsString("\"variantId\":\"" + APPLES + "\",\"qty\":3"));

    // order-svc fulfils and announces; the fulfilment of what the wave picked deducts nothing
    // more but keeps the revenue; a fulfilment the wave did not pick (the last apple, by hand)
    // deducts as always.
    orders.handle(
        fulfilled(
            Ids.newId().toString(),
            ids[0],
            item(APPLES, 3, "7.50") + "," + item(PEARS, 2, "5.00")));
    orders.handle(fulfilled(Ids.newId().toString(), ids[1], item(APPLES, 3, "7.50")));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("9")));
    assertThat(onHand(PEARS), comparesEqualTo(new BigDecimal("6")));
    assertThat(
        Envelopes.scalar(
            PG, "SELECT count(*) FROM inventory.sale_revenue WHERE order_id = '" + ids[0] + "'"),
        is("2"));
    orders.handle(fulfilled(Ids.newId().toString(), ids[1], item(APPLES, 1, "2.50")));
    assertThat(onHand(APPLES), comparesEqualTo(new BigDecimal("8")));
    assertThat(
        Envelopes.okArray(get("/admin/inventory/waves/awaiting?storeId=" + STORE)).size(), is(0));
    // Completed once.
    assertThat(
        code(post("/admin/inventory/waves/" + waveId + "/complete", "{}"), 409),
        is("INVENTORY_WAVE_NOT_OPEN"));
  }

  // ── the store's own ────────────────────────────────────────────────────────

  @Test
  void wavesAreTheStoresOwn() {
    receive(APPLES, 20, ZONE_A, "A-1", null);
    receive(PEARS, 8, ZONE_A, "P-1", null);
    twoOrdersWaiting();
    String body = "{\"storeId\":\"" + STORE + "\"}";
    assertThat(call("POST", "/admin/inventory/waves", body, T, "CASHIER").getStatus(), is(403));
    assertThat(
        call("POST", "/admin/inventory/waves", body, T, "STOREKEEPER", OTHER_STORE).getStatus(),
        is(403));
    assertThat(
        Envelopes.okArray(
                call("GET", "/admin/inventory/waves/awaiting?storeId=" + STORE, null, T2, "OWNER"))
            .size(),
        is(0));
    assertThat(
        code(call("POST", "/admin/inventory/waves", body, T2, "OWNER"), 409),
        is("INVENTORY_WAVE_NOTHING_TO_PICK"));
    JsonObject wave =
        Envelopes.created(call("POST", "/admin/inventory/waves", body, T, "STOREKEEPER", STORE));
    assertThat(
        call("GET", "/admin/inventory/waves/" + wave.getString("id"), null, T2, "OWNER")
            .getStatus(),
        is(404));
    assertThat(
        call(
                "POST",
                "/admin/inventory/waves/" + wave.getString("id") + "/cancel",
                "{}",
                T,
                "CASHIER")
            .getStatus(),
        is(403));
  }
}
