package com.shelfj.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for inventory against real Postgres (Testcontainers): receive two batches,
 * reserve, over-reserve (422), consume with FIFO deduction, tenant isolation. Kafka/Consul
 * disabled.
 */
@HelidonTest
class InventoryIT {

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
  }

  private static final String T = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER = "99999999-9999-9999-9999-999999999999";
  private static final String S = "22222222-2222-2222-2222-222222222222";
  private static final String V = "33333333-3333-3333-3333-333333333333";

  /** Dedicated variant for the FIFO test so tier-1 stock doesn't pollute its level assertions. */
  private static final String V_FIFO = "44444444-4444-4444-4444-444444444444";

  /** Dedicated store + variants (id-ordered) for the levels pagination / summary test. */
  private static final String S_PAGE = "55555555-5555-5555-5555-555555555555";

  private static final String VP1 = "a0000001-0000-0000-0000-000000000000";
  private static final String VP2 = "a0000002-0000-0000-0000-000000000000";
  private static final String VP3 = "a0000003-0000-0000-0000-000000000000";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response post(String path, String json, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private String get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  @Test
  void receiveReserveConsumeFifoAndIsolation() {
    // Use V_FIFO so tier-1 tests receiving into V don't pollute level assertions here.
    // two batches: A (earlier expiry, 10) then B (later, 5)
    assertThat(
        post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + V_FIFO
                    + "\",\"qty\":10,\"batchNo\":\"A\",\"expiryDate\":\"2026-01-01\"}",
                T)
            .getStatus(),
        is(201));
    assertThat(
        post(
                "/admin/inventory/receive",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + V_FIFO
                    + "\",\"qty\":5,\"batchNo\":\"B\",\"expiryDate\":\"2027-01-01\"}",
                T)
            .getStatus(),
        is(201));

    // levels for store S — V_FIFO row has onHand 15, available 15
    assertThat(
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class),
        containsString("\"available\":15"));

    // reserve 12 → available 3
    Response resResp =
        post(
            "/inventory/reservations",
            "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V_FIFO + "\",\"qty\":12}",
            T);
    assertThat(resResp.getStatus(), is(201));
    String reservationId = field(resResp.readEntity(String.class), "id");
    assertThat(
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class),
        containsString("\"available\":3"));

    // over-reserve (5 > 3) → 422
    Response over =
        post(
            "/inventory/reservations",
            "{\"storeId\":\"" + S + "\",\"variantId\":\"" + V_FIFO + "\",\"qty\":5}",
            T);
    assertThat(over.getStatus(), is(422));
    assertThat(over.readEntity(String.class), containsString("INSUFFICIENT_STOCK"));

    // consume (FIFO: A drains, B reduced) → onHand 3
    Response consume = post("/inventory/reservations/" + reservationId + "/consume", "", T);
    assertThat(consume.getStatus(), is(200));
    assertThat(
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class),
        containsString("\"onHand\":3"));

    // tenant isolation
    assertThat(get("/admin/inventory/levels", OTHER), not(containsString(V_FIFO)));
  }

  @Test
  void levelsPaginateAndSummarize() {
    // Three SKUs in a dedicated store: two low (available <= 5), one healthy.
    receive(S_PAGE, VP1, 3);
    receive(S_PAGE, VP2, 10);
    receive(S_PAGE, VP3, 2);

    // Summary is a server-side aggregate — 3 distinct SKUs, 2 of them low.
    String summary =
        target
            .path("/admin/inventory/levels/summary")
            .queryParam("store", S_PAGE)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(summary, containsString("\"skuCount\":3"));
    assertThat(summary, containsString("\"lowStockCount\":2"));

    // Page 1 (limit 2): the first two SKUs by (store, variant) order, plus a cursor.
    String page1 = levelsPage(S_PAGE, 2, null);
    assertThat(page1, containsString(VP1));
    assertThat(page1, containsString(VP2));
    assertThat(page1, not(containsString(VP3)));
    String cursor = field(page1, "nextCursor");

    // Page 2: the remaining SKU only — the cursor advances past page 1 with no overlap.
    String page2 = levelsPage(S_PAGE, 2, cursor);
    assertThat(page2, containsString(VP3));
    assertThat(page2, not(containsString(VP1)));
    assertThat(page2, not(containsString(VP2)));
    // Exhausted: no further (non-null) cursor — a null nextCursor is omitted by JSON-B.
    assertThat(page2, not(containsString("\"nextCursor\":\"")));
  }

  private void receive(String store, String variant, int qty) {
    Response r =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\"" + store + "\",\"variantId\":\"" + variant + "\",\"qty\":" + qty + "}",
            T);
    assertThat(r.getStatus(), is(201));
  }

  private String levelsPage(String store, int limit, String after) {
    var t =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", store)
            .queryParam("limit", limit);
    if (after != null) {
      t = t.queryParam("after", after);
    }
    return t.request().header("X-Tenant-Id", T).header("X-Roles", "OWNER").get(String.class);
  }

  // ── Tier-1 Gap #21: Reason codes ─────────────────────────────────────────

  @Test
  void reasonCode_createAndList() {
    Response r =
        post(
            "/admin/inventory/reason-codes",
            "{\"code\":\"TEST_DMG\",\"description\":\"Test damage\"}",
            T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("TEST_DMG"));

    String list = get("/admin/inventory/reason-codes", T);
    assertThat(list, containsString("TEST_DMG"));
    // System seeded codes also visible
    assertThat(list, containsString("DAMAGED"));
  }

  @Test
  void reasonCode_missingCode_returns400() {
    Response r = post("/admin/inventory/reason-codes", "{\"description\":\"no code\"}", T);
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void reasonCode_deactivate() {
    post("/admin/inventory/reason-codes", "{\"code\":\"DEACT_ME\"}", T);
    String list = get("/admin/inventory/reason-codes", T);
    String id = fieldNear(list, "\"DEACT_ME\"", "id");
    Response r = post("/admin/inventory/reason-codes/" + id + "/deactivate", "", T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"active\":false"));
  }

  // ── Tier-1 Gap #22: Source types ─────────────────────────────────────────

  @Test
  void sourceType_createAndList() {
    Response r =
        post("/admin/inventory/source-types", "{\"code\":\"POS_SALE\",\"description\":\"POS\"}", T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("POS_SALE"));

    String list = get("/admin/inventory/source-types", T);
    assertThat(list, containsString("POS_SALE"));
    assertThat(list, containsString("RECEIVE")); // system seed
  }

  @Test
  void sourceType_missingCode_returns400() {
    Response r = post("/admin/inventory/source-types", "{\"description\":\"no code\"}", T);
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #23: Lot split / merge ────────────────────────────────────

  @Test
  void lotSplit_positive() {
    // receive a batch first
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":20,\"batchNo\":\"SPLIT-SRC\"}",
            T);
    assertThat(rcv.getStatus(), is(201));
    String srcBatchId = field(rcv.readEntity(String.class), "id");

    Response r =
        post(
            "/admin/inventory/lots/split",
            "{\"sourceBatchId\":\"" + srcBatchId + "\",\"qty\":8,\"batchNo\":\"SPLIT-CHILD\"}",
            T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("SPLIT"));
  }

  @Test
  void lotSplit_excessQty_returns422() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":5,\"batchNo\":\"SPLIT-SMALL\"}",
            T);
    String srcId = field(rcv.readEntity(String.class), "id");

    Response r =
        post("/admin/inventory/lots/split", "{\"sourceBatchId\":\"" + srcId + "\",\"qty\":999}", T);
    assertThat(r.getStatus(), is(422));
    assertThat(r.readEntity(String.class), containsString("INSUFFICIENT_QTY"));
  }

  @Test
  void lotSplit_unknownBatch_returns404() {
    Response r =
        post(
            "/admin/inventory/lots/split",
            "{\"sourceBatchId\":\"00000000-0000-0000-0000-000000000099\",\"qty\":1}",
            T);
    assertThat(r.getStatus(), is(404));
  }

  // ── Tier-1 Gap #24: Expiry alert query ───────────────────────────────────

  @Test
  void expiringBatches_withinWindow() {
    post(
        "/admin/inventory/receive",
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + V
            + "\",\"qty\":3,\"batchNo\":\"EXP-NEAR\",\"expiryDate\":\"2026-01-15\"}",
        T);

    String resp =
        target
            .path("/admin/inventory/batches/expiring")
            .queryParam("store", S)
            .queryParam("withinDays", 3650)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(resp, containsString("EXP-NEAR"));
  }

  @Test
  void expiringBatches_invalidDays_returns400() {
    Response r =
        target
            .path("/admin/inventory/batches/expiring")
            .queryParam("store", S)
            .queryParam("withinDays", 9999)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #25: Grade control ────────────────────────────────────────

  @Test
  void gradeUpdate_positive() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":5,\"batchNo\":\"GRADE-B1\",\"grade\":\"A\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/batches/" + batchId + "/grade")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"grade\":\"B\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"grade\":\"B\""));
  }

  @Test
  void gradeUpdate_blankGrade_returns400() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":1,\"batchNo\":\"GRADE-B2\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/batches/" + batchId + "/grade")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"grade\":\"\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #26: Lot UOM conversions ──────────────────────────────────

  @Test
  void uomConversion_upsertAndList() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":10,\"batchNo\":\"UOM-B1\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/lots/" + batchId + "/uom-conversions")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"batchId\":\""
                        + batchId
                        + "\",\"fromUom\":\"KG\",\"toUom\":\"G\",\"factor\":1000}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"factor\":1000"));

    String list = get("/admin/inventory/lots/" + batchId + "/uom-conversions", T);
    assertThat(list, containsString("KG"));
  }

  @Test
  void uomConversion_negFactor_returns400() {
    Response rcv =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":2,\"batchNo\":\"UOM-NEG\"}",
            T);
    String batchId = field(rcv.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/lots/" + batchId + "/uom-conversions")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"batchId\":\""
                        + batchId
                        + "\",\"fromUom\":\"KG\",\"toUom\":\"G\",\"factor\":-1}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #27: PAR levels ───────────────────────────────────────────

  @Test
  void parLevel_upsertAndList() {
    Response r =
        target
            .path("/admin/inventory/par-levels")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + V
                        + "\",\"parQty\":50,\"reviewCycle\":\"WEEKLY\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"parQty\":50"));

    String list =
        target
            .path("/admin/inventory/par-levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(list, containsString("WEEKLY"));
  }

  @Test
  void parLevel_invalidCycle_returns400() {
    Response r =
        target
            .path("/admin/inventory/par-levels")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + V
                        + "\",\"parQty\":10,\"reviewCycle\":\"YEARLY\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  // ── Tier-1 Gap #28: Order modifiers ──────────────────────────────────────

  @Test
  void ropOrderModifiers_update() {
    // create a ROP plan first
    Response rop =
        target
            .path("/admin/inventory/rop-plans")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + V
                        + "\",\"leadTimeDays\":7,\"orderingCost\":50,\"holdingCostPct\":0.2,\"unitCost\":10}",
                    MediaType.APPLICATION_JSON));
    assertThat(rop.getStatus(), is(200));
    String ropId = field(rop.readEntity(String.class), "id");

    Response r =
        target
            .path("/admin/inventory/rop-plans/" + ropId + "/order-modifiers")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"minOrderQty\":5,\"maxOrderQty\":100,\"lotMultiplier\":5}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"minOrderQty\":5"));
  }

  @Test
  void ropOrderModifiers_unknownPlan_returns404() {
    Response r =
        target
            .path("/admin/inventory/rop-plans/00000000-0000-0000-0000-000000000099/order-modifiers")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"minOrderQty\":1}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(404));
  }

  // ── Tier-1 Gap #29: Bulk reservations ────────────────────────────────────

  @Test
  void bulkReserve_positive() {
    // ensure stock
    post(
        "/admin/inventory/receive",
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + V
            + "\",\"qty\":100,\"batchNo\":\"BULK-SRC\"}",
        T);

    Response r =
        post(
            "/inventory/reservations/batch",
            "{\"reservations\":[{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":2},"
                + "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + V
                + "\",\"qty\":3}]}",
            T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"succeeded\":2"));
  }

  @Test
  void bulkReserve_emptyList_returns400() {
    // Missing "reservations" key → 400 (Bean Validation: @NotNull)
    Response bad = post("/inventory/reservations/batch", "{}", T);
    assertThat(bad.getStatus(), is(400));
  }

  // ── Tier-1 Gap #30: Purge movements ──────────────────────────────────────

  @Test
  void purgeMovements_tooRecent_returns400() {
    // Trying to purge within 90 days must be rejected. Computed relative to now (not hardcoded)
    // so this test doesn't silently start passing for the wrong reason once real time moves the
    // fixed date past the 90-day window.
    String tooRecent = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(30).toString();
    Response r = post("/admin/inventory/movements/purge", "{\"before\":\"" + tooRecent + "\"}", T);
    assertThat(r.getStatus(), is(400));
    assertThat(r.readEntity(String.class), containsString("PURGE_TOO_RECENT"));
  }

  @Test
  void purgeMovements_oldDate_succeeds() {
    Response r =
        post("/admin/inventory/movements/purge", "{\"before\":\"2020-01-01T00:00:00Z\"}", T);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"purged\""));
  }

  @Test
  void purgeMovements_archivesRatherThanDeletes() throws Exception {
    // Golden rule #8: stock_movements is append-only. Purge must relocate rows to
    // stock_movements_archive, never destroy them. Seed a pre-dated row directly
    // (no API backdates created_at), then verify it survives in the archive table.
    UUID movementId = UUID.randomUUID();
    OffsetDateTime oldDate = OffsetDateTime.parse("2019-01-01T00:00:00Z");
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO inventory.stock_movements (id, tenant_id, store_id, variant_id,"
                    + " type, qty, created_at) VALUES (?,?,?,?,'ADJUST',1,?)")) {
      ps.setObject(1, movementId);
      ps.setObject(2, UUID.fromString(T));
      ps.setObject(3, UUID.fromString(S));
      ps.setObject(4, UUID.fromString(V));
      ps.setObject(5, oldDate);
      ps.executeUpdate();
    }

    Response r =
        post("/admin/inventory/movements/purge", "{\"before\":\"2020-01-01T00:00:00Z\"}", T);
    assertThat(r.getStatus(), is(200));

    try (var c = PG.dataSource().getConnection()) {
      try (var ps = c.prepareStatement("SELECT 1 FROM inventory.stock_movements WHERE id=?")) {
        ps.setObject(1, movementId);
        try (var rs = ps.executeQuery()) {
          assertThat("row must leave the hot table", rs.next(), is(false));
        }
      }
      try (var ps =
          c.prepareStatement("SELECT 1 FROM inventory.stock_movements_archive WHERE id=?")) {
        ps.setObject(1, movementId);
        try (var rs = ps.executeQuery()) {
          assertThat("row must survive in the archive", rs.next(), is(true));
        }
      }
    }
  }

  // ── Tier-1 Gap #31: Zone GL mappings ─────────────────────────────────────

  @Test
  void zoneGlMapping_upsertAndList() {
    Response r =
        target
            .path("/admin/inventory/zone-gl-mappings")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"nominalCode\":\"1200\",\"description\":\"Stock account\"}",
                    MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"nominalCode\":\"1200\""));

    String list =
        target
            .path("/admin/inventory/zone-gl-mappings")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    assertThat(list, containsString("1200"));
  }

  @Test
  void zoneGlMapping_missingNominalCode_returns400() {
    Response r =
        target
            .path("/admin/inventory/zone-gl-mappings")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .put(Entity.entity("{\"storeId\":\"" + S + "\"}", MediaType.APPLICATION_JSON));
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void receiveWithSameIdempotencyKeyIsNotDoubleCounted() {
    String variant = UUID.randomUUID().toString();
    String key = UUID.randomUUID().toString();
    String body =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":10,\"batchNo\":\"R\"}";

    Response first = postWithIdempotencyKey("/admin/inventory/receive", body, T, key);
    assertThat(first.getStatus(), is(201));
    String firstBatchId = field(first.readEntity(String.class), "id");

    // a client-timeout retry with the same key replays the original batch, not a second one
    Response retried = postWithIdempotencyKey("/admin/inventory/receive", body, T, key);
    assertThat(retried.getStatus(), is(201));
    assertThat(field(retried.readEntity(String.class), "id"), is(firstBatchId));

    String levels =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    int marker = levels.indexOf("\"variantId\":\"" + variant + "\"");
    assertThat(marker, not(-1));
    String row = levels.substring(levels.lastIndexOf('{', marker), levels.indexOf('}', marker) + 1);
    assertThat(row, containsString("\"onHand\":10.000"));
  }

  @Test
  void reserveWithSameIdempotencyKeyIsNotDoubleHeld() {
    String variant = UUID.randomUUID().toString();
    String key = UUID.randomUUID().toString();
    String receiveBody =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":10,\"batchNo\":\"RV\"}";
    assertThat(post("/admin/inventory/receive", receiveBody, T).getStatus(), is(201));

    String reserveBody = "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variant + "\",\"qty\":7}";
    Response first = postWithIdempotencyKey("/inventory/reservations", reserveBody, T, key);
    assertThat(first.getStatus(), is(201));
    String firstReservationId = field(first.readEntity(String.class), "id");

    // a client-timeout retry with the same key replays the original hold, not a second one — if
    // it held stock twice, only 10-7-7=-4 would remain and a third reserve of 4 would fail
    Response retried = postWithIdempotencyKey("/inventory/reservations", reserveBody, T, key);
    assertThat(retried.getStatus(), is(201));
    assertThat(field(retried.readEntity(String.class), "id"), is(firstReservationId));

    String remainder = "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variant + "\",\"qty\":3}";
    Response third = post("/inventory/reservations", remainder, T);
    assertThat(third.getStatus(), is(201));
  }

  @Test
  void adjustWithSameIdempotencyKeyIsNotDoubleApplied() {
    String variant = UUID.randomUUID().toString();
    String key = UUID.randomUUID().toString();
    String receiveBody =
        "{\"storeId\":\""
            + S
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":10,\"batchNo\":\"ADJ\"}";
    assertThat(post("/admin/inventory/receive", receiveBody, T).getStatus(), is(201));

    // a -4 adjustment, retried with the same key — applied once leaves onHand 6, not 2
    String adjustBody =
        "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variant + "\",\"delta\":-4}";
    Response first = postWithIdempotencyKey("/admin/inventory/adjust", adjustBody, T, key);
    assertThat(first.getStatus(), is(200));
    Response retried = postWithIdempotencyKey("/admin/inventory/adjust", adjustBody, T, key);
    assertThat(retried.getStatus(), is(200));

    String levels =
        target
            .path("/admin/inventory/levels")
            .queryParam("store", S)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get(String.class);
    int marker = levels.indexOf("\"variantId\":\"" + variant + "\"");
    assertThat(marker, not(-1));
    String row = levels.substring(levels.lastIndexOf('{', marker), levels.indexOf('}', marker) + 1);
    assertThat(row, containsString("\"onHand\":6.000"));
  }

  @Test
  void batchReceiveRejectsNonPositiveItemQty() {
    // BatchReceiveRequest.items is @NotNull @Valid — a zero qty item must be rejected by
    // cascading Bean Validation instead of silently receiving zero stock.
    String body = "{\"items\":[{\"storeId\":\"" + S + "\",\"variantId\":\"" + V + "\",\"qty\":0}]}";
    assertThat(post("/admin/inventory/receive/batch", body, T).getStatus(), is(400));
  }

  private Response postWithIdempotencyKey(String path, String json, String tenant, String key) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY, key)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /**
   * Like {@link #field} but for unquoted JSON values (numbers, booleans) — the report DTO's
   * quantities and counts are numeric, so the string-only helper cannot read them.
   */
  private static String numericField(String json, String name) {
    String key = "\"" + name + "\":";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    int end = start;
    while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) end++;
    return json.substring(start, end).trim();
  }

  private static String field(String json, String name) {
    String key = "\"" + name + "\":\"";
    int i = json.indexOf(key);
    if (i < 0) throw new AssertionError(name + " not in " + json);
    int start = i + key.length();
    return json.substring(start, json.indexOf('"', start));
  }

  // ── SJ-D4: who adjusted stock, and why ───────────────────────────────────────

  /** A manual adjustment records the acting user and the reason code on the movement row. */
  @Test
  void manualAdjustmentRecordsActorAndReasonCode() {
    String variant = "b0000001-0000-0000-0000-000000000000";
    String actor = "c0000001-0000-0000-0000-000000000000";

    // Stock in, then written off as theft by a named user.
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(variant, "20"), T, actor).getStatus(),
        is(201));
    assertThat(
        postAs(
                "/admin/inventory/adjust",
                "{\"storeId\":\""
                    + S
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"delta\":-5,\"reason\":\"missing from shelf\","
                    + "\"reasonCode\":\"THEFT\"}",
                T,
                actor)
            .getStatus(),
        is(200));

    String movements = movements(variant, "ADJUST");
    assertThat(movements, containsString("THEFT"));
    assertThat(movements, containsString(actor));
  }

  /**
   * System-caused movements stay unattributed on purpose: they already cite the record that caused
   * them. Asserting this pins the distinction, so a later change cannot quietly start stamping the
   * requesting user onto a sale and make "who adjusted this" ambiguous again.
   */
  @Test
  void systemCausedMovementsCarryNoActor() {
    String variant = "b0000002-0000-0000-0000-000000000000";
    String actor = "c0000002-0000-0000-0000-000000000000";
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(variant, "7"), T, actor).getStatus(),
        is(201));

    String movements = movements(variant, "RECEIVE");
    assertThat(movements, containsString("RECEIVE"));
    // The requesting user must not be stamped onto a system-caused movement: attribution here
    // would be misleading, since the receipt is explained by its refType/refId, not by whoever
    // happened to call the endpoint.
    assertThat(movements, not(containsString(actor)));
  }

  /** Like {@link #post} but with an authenticated user id, as the gateway would stamp it. */
  private Response postAs(String path, String json, String tenant, String userId) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", userId)
        .header("X-Roles", "OWNER")
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  // ── Valuation report ─────────────────────────────────────────────────────────

  /** FIFO values each batch at its own cost; the store rollup is the sum of its variants. */
  @Test
  void valuationCostsEachBatchAtItsOwnPriceUnderFifo() {
    String v1 = "d1000001-0000-0000-0000-000000000000";
    String v2 = "d1000002-0000-0000-0000-000000000000";
    String tenant = "f1000001-0000-0000-0000-000000000000";

    // Two batches of v1 bought at different prices — FIFO must value each at its own, not at an
    // average: 10 × 2.00 + 5 × 3.00 = 35.00.
    receiveCosted(tenant, v1, "10", "2.00");
    receiveCosted(tenant, v1, "5", "3.00");
    receiveCosted(tenant, v2, "4", "1.50"); // 6.00

    String byVariant = valuation(tenant, "VARIANT", null);
    assertThat(numericFieldNear(byVariant, v1, "value"), is("35.00"));
    assertThat(numericFieldNear(byVariant, v1, "onHandQty"), is("15.000"));
    assertThat(numericFieldNear(byVariant, v2, "value"), is("6.00"));
    // Largest holding first.
    assertThat(byVariant.indexOf(v1) < byVariant.indexOf(v2), is(true));
    assertThat(fieldNear(byVariant, v1, "method"), is("FIFO"));

    // The store rollup is the sum of both variants.
    String byStore = valuation(tenant, "STORE", null);
    assertThat(numericFieldNear(byStore, S, "value"), is("41.00"));
    assertThat(numericFieldNear(byStore, S, "onHandQty"), is("19.000"));
  }

  /**
   * The case worth getting right: cost_price is optional on both receipt paths, so stock can have
   * no cost. Valuing it at zero would silently understate a balance-sheet figure, so it has to come
   * back as unvaluedQty instead.
   */
  @Test
  void stockWithNoCostIsReportedAsUnvaluedNotValuedAtZero() {
    String v = "d1000003-0000-0000-0000-000000000000";
    String tenant = "f1000002-0000-0000-0000-000000000000";

    receiveCosted(tenant, v, "10", "4.00"); // 40.00, valued
    // Same variant, no cost supplied — 6 units that cannot be costed.
    assertThat(post("/admin/inventory/receive", receiveJson(v, "6"), tenant).getStatus(), is(201));

    String body = valuation(tenant, "VARIANT", null);
    assertThat(numericFieldNear(body, v, "onHandQty"), is("16.000"));
    assertThat(numericFieldNear(body, v, "value"), is("40.00"));
    // The 6 uncosted units are declared, not folded into the value as zero.
    assertThat(numericFieldNear(body, v, "unvaluedQty"), is("6.000"));
  }

  /** An AVERAGE row values the whole holding at the configured standard cost. */
  @Test
  void averageCostingValuesTheWholeHoldingAtTheConfiguredCost() {
    String v = "d1000004-0000-0000-0000-000000000000";
    String tenant = "f1000003-0000-0000-0000-000000000000";

    receiveCosted(tenant, v, "10", "2.00");
    receiveCosted(tenant, v, "10", "8.00"); // FIFO would say 100.00

    Response cm =
        target
            .path("/admin/inventory/costing-methods")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .put(
                Entity.entity(
                    "{\"storeId\":\""
                        + S
                        + "\",\"variantId\":\""
                        + v
                        + "\",\"method\":\"AVERAGE\",\"averageCost\":3.50}",
                    MediaType.APPLICATION_JSON));
    assertThat(cm.getStatus(), is(200));

    // 20 units × 3.50 = 70.00, not the 100.00 FIFO would give.
    String body = valuation(tenant, "VARIANT", null);
    assertThat(fieldNear(body, v, "method"), is("AVERAGE"));
    assertThat(numericFieldNear(body, v, "value"), is("70.00"));
    assertThat(numericFieldNear(body, v, "unvaluedQty"), is("0.000"));
  }

  /** Tenant scoping, and a bad grouping is a 400 rather than a silently different report. */
  @Test
  void valuationIsTenantScopedAndValidatesItsInputs() {
    String v = "d1000005-0000-0000-0000-000000000000";
    String tenant = "f1000004-0000-0000-0000-000000000000";
    receiveCosted(tenant, v, "3", "5.00");

    assertThat(valuation(tenant, "VARIANT", null), containsString(v));
    assertThat(valuation(OTHER, "VARIANT", null), not(containsString(v)));

    assertThat(
        target
            .path("/admin/inventory/reports/valuation")
            .queryParam("groupBy", "SUPPLIER")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get()
            .getStatus(),
        is(400));
  }

  private void receiveCosted(String tenant, String variantId, String qty, String costPrice) {
    Response r =
        post(
            "/admin/inventory/receive",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + variantId
                + "\",\"qty\":"
                + qty
                + ",\"costPrice\":"
                + costPrice
                + "}",
            tenant);
    assertThat(r.getStatus(), is(201));
  }

  private String valuation(String tenant, String groupBy, String storeId) {
    var t = target.path("/admin/inventory/reports/valuation").queryParam("groupBy", groupBy);
    if (storeId != null) t = t.queryParam("storeId", storeId);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get(String.class);
  }

  // ── Shrinkage report ─────────────────────────────────────────────────────────

  /**
   * The report SJ-D4's attribution work exists to feed. Two members of staff write off stock for
   * different reasons, one also finds some, and the report has to separate all of that correctly.
   */
  @Test
  void shrinkageReportGroupsWriteOffsByReasonActorAndStore() {
    String v1 = "d0000001-0000-0000-0000-000000000000";
    String v2 = "d0000002-0000-0000-0000-000000000000";
    String alice = "e0000001-0000-0000-0000-000000000000";
    String bob = "e0000002-0000-0000-0000-000000000000";
    String tenant = "f0000001-0000-0000-0000-000000000000";

    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v1, "100"), tenant, alice).getStatus(),
        is(201));
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v2, "100"), tenant, alice).getStatus(),
        is(201));

    adjust(tenant, alice, v1, "-30", "THEFT");
    adjust(tenant, bob, v1, "-5", "DAMAGED");
    adjust(tenant, bob, v2, "-10", "THEFT");
    // A find is a gain, not a negative loss — it must not cancel out the THEFT total.
    adjust(tenant, bob, v2, "8", "FOUND");

    String byReason = shrinkage(tenant, "REASON");
    // THEFT: 30 + 10 = 40 across two movements, and it outweighs DAMAGED so it sorts first.
    assertThat(byReason.indexOf("THEFT") < byReason.indexOf("DAMAGED"), is(true));
    assertThat(numericFieldNear(byReason, "THEFT", "qtyWrittenOff"), is("40.000"));
    assertThat(numericFieldNear(byReason, "DAMAGED", "qtyWrittenOff"), is("5.000"));
    // FOUND is a gain: it appears with nothing written off and 8 found.
    assertThat(numericFieldNear(byReason, "FOUND", "qtyWrittenOff"), is("0.000"));
    assertThat(numericFieldNear(byReason, "FOUND", "qtyFound"), is("8.000"));

    // Grouped by actor: bob wrote off 15 across three movements, alice 30 across one.
    String byActor = shrinkage(tenant, "ACTOR");
    assertThat(numericFieldNear(byActor, alice, "qtyWrittenOff"), is("30.000"));
    assertThat(numericFieldNear(byActor, bob, "qtyWrittenOff"), is("15.000"));
    assertThat(numericFieldNear(byActor, bob, "movements"), is("3"));

    // Grouped by store, everything lands on the one store used here: 45 off, 8 found, net -37.
    String byStore = shrinkage(tenant, "STORE");
    assertThat(numericFieldNear(byStore, S, "qtyWrittenOff"), is("45.000"));
    assertThat(numericFieldNear(byStore, S, "netQty"), is("-37.000"));

    // Receipts are not adjustments and must never appear as shrinkage.
    assertThat(byReason, not(containsString("\"groupKey\":\"RECEIVE\"")));
  }

  /** Drill-down answers "what did this person actually write off?". */
  @Test
  void shrinkageDrillsDownToVariantsForOneActor() {
    String v1 = "d0000003-0000-0000-0000-000000000000";
    String v2 = "d0000004-0000-0000-0000-000000000000";
    String carol = "e0000003-0000-0000-0000-000000000000";
    String tenant = "f0000002-0000-0000-0000-000000000000";

    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v1, "50"), tenant, carol).getStatus(),
        is(201));
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v2, "50"), tenant, carol).getStatus(),
        is(201));
    adjust(tenant, carol, v1, "-20", "THEFT");
    adjust(tenant, carol, v2, "-3", "THEFT");

    String body =
        target
            .path("/admin/inventory/reports/shrinkage/by-variant")
            .queryParam("actorId", carol)
            .queryParam("reasonCode", "theft")
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .get(String.class);
    // Heaviest first, and reasonCode is matched case-insensitively.
    assertThat(body.indexOf(v1) < body.indexOf(v2), is(true));
    assertThat(numericFieldNear(body, v1, "qtyWrittenOff"), is("20.000"));
  }

  /**
   * A report is only as trustworthy as its isolation, and a bad period is a 400 not a silent empty.
   */
  @Test
  void shrinkageIsTenantScopedAndValidatesItsInputs() {
    String v = "d0000005-0000-0000-0000-000000000000";
    String dave = "e0000004-0000-0000-0000-000000000000";
    String tenant = "f0000003-0000-0000-0000-000000000000";
    assertThat(
        postAs("/admin/inventory/receive", receiveJson(v, "40"), tenant, dave).getStatus(),
        is(201));
    adjust(tenant, dave, v, "-9", "EXPIRY");

    assertThat(shrinkage(tenant, "REASON"), containsString("EXPIRY"));
    // Another tenant sees none of it.
    assertThat(shrinkage(OTHER, "REASON"), not(containsString("EXPIRY")));

    assertThat(shrinkageStatus(tenant, "groupBy", "SUPPLIER"), is(400));
    assertThat(shrinkageStatus(tenant, "from", "last-tuesday"), is(400));
    assertThat(shrinkageStatus(tenant, "storeId", "not-a-uuid"), is(400));
  }

  private void adjust(
      String tenant, String actor, String variantId, String delta, String reasonCode) {
    Response r =
        postAs(
            "/admin/inventory/adjust",
            "{\"storeId\":\""
                + S
                + "\",\"variantId\":\""
                + variantId
                + "\",\"delta\":"
                + delta
                + ",\"reasonCode\":\""
                + reasonCode
                + "\"}",
            tenant,
            actor);
    assertThat(r.getStatus(), is(200));
  }

  private String shrinkage(String tenant, String groupBy) {
    return target
        .path("/admin/inventory/reports/shrinkage")
        .queryParam("groupBy", groupBy)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private int shrinkageStatus(String tenant, String param, String value) {
    return target
        .path("/admin/inventory/reports/shrinkage")
        .queryParam(param, value)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get()
        .getStatus();
  }

  /** Movements for one variant, filtered by type. */
  private String movements(String variantId, String type) {
    return target
        .path("/admin/inventory/movements")
        .queryParam("variantId", variantId)
        .queryParam("type", type)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .get(String.class);
  }

  private static String receiveJson(String variantId, String qty) {
    return "{\"storeId\":\"" + S + "\",\"variantId\":\"" + variantId + "\",\"qty\":" + qty + "}";
  }

  /** Find the value of {@code name} in the JSON object that contains {@code marker}. */
  /** {@link #fieldNear} for an unquoted numeric value. */
  private static String numericFieldNear(String json, String marker, String name) {
    int m = json.indexOf(marker);
    if (m < 0) throw new AssertionError(marker + " not found in " + json);
    int objStart = json.lastIndexOf('{', m);
    int objEnd = json.indexOf('}', m);
    return numericField(json.substring(objStart, objEnd + 1), name);
  }

  private static String fieldNear(String json, String marker, String name) {
    int m = json.indexOf(marker);
    if (m < 0) throw new AssertionError(marker + " not found in " + json);
    // scan backward to find the start of the enclosing object
    int objStart = json.lastIndexOf('{', m);
    // find the end of the object (next '}' after the marker position)
    int objEnd = json.indexOf('}', m);
    String obj = json.substring(objStart, objEnd + 1);
    return field(obj, name);
  }
}
