package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.shelfj.order.service.OrderService;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for order-svc against real Postgres (Testcontainers): place order, confirm, void
 * (POS), return, layaway, gift card issue/reload/redeem. Kafka/Consul disabled.
 */
@HelidonTest
class OrderIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("shelfj.db.url", PG.jdbcUrl());
    System.setProperty("shelfj.db.migration-url", PG.jdbcUrl());
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "order");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
    // Disable server-side pricing enforcement so tests don't need a live pricing-svc.
    System.setProperty("shelfj.order.pricing.enforce", "false");
  }

  private static final String T = "11111111-1111-1111-1111-111111111111";
  private static final String S = "22222222-2222-2222-2222-222222222222";
  private static final String V = "33333333-3333-3333-3333-333333333333";

  @Inject WebTarget target;
  @Inject OrderService orderService;

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

  /**
   * Like {@link #post(String, String, String)} but with an Idempotency-Key header — required by
   * {@code POST /orders} now that the server rejects order placement without one.
   */
  private Response post(String path, String json, String tenant, String idempotencyKey) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", idempotencyKey)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  private Response listOrders(String tenant, int limit, String after) {
    WebTarget t = target.path("/orders").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  @Test
  void placeOrderConfirmAndReturn() {
    // place POS order
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            "it-place-confirm-return");
    assertThat(r1.getStatus(), is(201));
    String body1 = r1.readEntity(String.class);
    assertThat(body1, containsString("PENDING"));
    String orderId = extractId(body1);

    // confirm
    Response r2 = post("/orders/" + orderId + "/confirm", "{}", T);
    assertThat(r2.getStatus(), is(200));
    assertThat(r2.readEntity(String.class), containsString("CONFIRMED"));

    // return one unit
    Response r3 =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"customer changed mind\","
                + "\"refundMethod\":\"ORIGINAL\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1}]}",
            T);
    assertThat(r3.getStatus(), is(201));
    assertThat(r3.readEntity(String.class), containsString("COMPLETED"));
  }

  /**
   * Simulates what {@code PaymentEventHandler} does on each PaymentCaptured event — Kafka is
   * disabled in this IT, so the events are driven directly through {@link OrderService} instead of
   * a real consumer loop.
   */
  @Test
  void splitTendersAccumulateAndConfirmOnlyOnceTotalIsCovered() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            "it-split-tender");
    assertThat(placed.getStatus(), is(201));
    UUID orderId = UUID.fromString(extractId(placed.readEntity(String.class)));
    UUID tenantId = UUID.fromString(T);

    // First tender (cash, $4) — covers less than the $10 total: still PENDING.
    orderService.handlePaymentCaptured(
        tenantId, orderId, UUID.randomUUID(), new BigDecimal("4.00"));
    Response afterFirst = get("/orders/" + orderId, T);
    assertThat(afterFirst.readEntity(String.class), containsString("PENDING"));

    // Second tender (card, $6) — the two together cover the total: now CONFIRMED.
    orderService.handlePaymentCaptured(
        tenantId, orderId, UUID.randomUUID(), new BigDecimal("6.00"));
    Response afterSecond = get("/orders/" + orderId, T);
    assertThat(afterSecond.readEntity(String.class), containsString("CONFIRMED"));
  }

  /**
   * Redelivery of the same Kafka event (same paymentId) must not double-count toward paid_amount —
   * verified on a second order where double-counting a single $6 tender (redelivered once) would
   * incorrectly push paid_amount past the $10 total and confirm prematurely.
   */
  @Test
  void redeliveredPaymentEventIsNotDoubleCounted() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            "it-redelivery");
    assertThat(placed.getStatus(), is(201));
    UUID orderId = UUID.fromString(extractId(placed.readEntity(String.class)));
    UUID tenantId = UUID.fromString(T);

    UUID paymentId = UUID.randomUUID();
    orderService.handlePaymentCaptured(tenantId, orderId, paymentId, new BigDecimal("6.00"));
    // Same paymentId redelivered: if paid_amount were double-counted (6+6=12 >= 10) the order
    // would wrongly confirm. The unique key on order_payment_events must make this a no-op.
    orderService.handlePaymentCaptured(tenantId, orderId, paymentId, new BigDecimal("6.00"));

    Response after = get("/orders/" + orderId, T);
    assertThat(after.readEntity(String.class), containsString("PENDING"));
  }

  @Test
  void retriedCheckoutWithSameIdempotencyKeyReplaysOriginalOrder() {
    String orderJson =
        "{\"storeId\":\""
            + S
            + "\","
            + "\"channel\":\"POS\","
            + "\"fulfilmentType\":\"INSTORE\","
            + "\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":1,\"unitPrice\":5.00}],"
            + "\"currency\":\"USD\","
            + "\"idempotencyKey\":\"idem-replay-1\"}";

    Response first = post("/orders", orderJson, T);
    assertThat(first.getStatus(), is(201));
    String firstId = extractId(first.readEntity(String.class));

    // retry (e.g. client timeout + resubmit) must return the SAME order, not an error
    Response retry = post("/orders", orderJson, T);
    assertThat(retry.getStatus(), is(201));
    assertThat(extractId(retry.readEntity(String.class)), is(firstId));
  }

  @Test
  void negativeTaxAndOversizedDiscountAreRejected() {
    // negative taxAmount must fail bean validation (gap #63)
    Response negTax =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"taxAmount\":-5.00,\"currency\":\"USD\"}",
            T,
            "it-neg-tax");
    assertThat(negTax.getStatus(), is(400));

    // discount larger than the subtotal must not drive the total negative
    Response bigDisc =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],"
                + "\"discountAmount\":50.00,\"currency\":\"USD\"}",
            T,
            "it-big-disc");
    assertThat(bigDisc.getStatus(), is(400));
    assertThat(bigDisc.readEntity(String.class), containsString("ORDER_DISCOUNT_EXCEEDS_SUBTOTAL"));
  }

  @Test
  void listOrdersPaginatesWithCursor() {
    // Dedicated tenant so orders created by other tests never leak into these pages.
    String tenant = "44444444-4444-4444-4444-444444444444";
    var allIds = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      Response r =
          post(
              "/orders",
              "{\"storeId\":\""
                  + S
                  + "\","
                  + "\"channel\":\"POS\","
                  + "\"fulfilmentType\":\"INSTORE\","
                  + "\"items\":[{\"variantId\":\""
                  + V
                  + "\",\"qty\":1,\"unitPrice\":1.00}],"
                  + "\"currency\":\"USD\"}",
              tenant,
              "it-paginate-" + i);
      assertThat(r.getStatus(), is(201));
      allIds.add(extractId(r.readEntity(String.class)));
    }

    // page 1: two orders + a nextCursor
    Response p1 = listOrders(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllIds(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    // page 2: the remaining order, no further cursor
    Response p2 = listOrders(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllIds(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    // the two pages cover all three orders with no overlap
    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen.size(), is(3));
    assertThat(seen, is(allIds));

    // a garbage cursor is a clean 400, not a 500
    Response bad = listOrders(tenant, 2, "!!not-base64!!");
    assertThat(bad.getStatus(), is(400));
    assertThat(bad.readEntity(String.class), containsString("INVALID_CURSOR"));
  }

  @Test
  void returnQuantityCannotExceedPurchased() {
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],"
                + "\"currency\":\"USD\"}",
            T,
            "it-return-qty");
    assertThat(r1.getStatus(), is(201));
    String orderId = extractId(r1.readEntity(String.class));

    // returning 3 when only 2 were purchased must be rejected outright
    Response tooMany =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"too many\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":3}]}",
            T);
    assertThat(tooMany.getStatus(), is(409));
    assertThat(tooMany.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));

    // returning 1 (of 2) succeeds...
    Response first =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"first\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":1}]}",
            T);
    assertThat(first.getStatus(), is(201));

    // ...but a second return of 2 more (1 already returned + 2 > 2 purchased) must be rejected
    Response second =
        post(
            "/orders/" + orderId + "/returns",
            "{\"reason\":\"second\",\"items\":[{\"variantId\":\"" + V + "\",\"qty\":2}]}",
            T);
    assertThat(second.getStatus(), is(409));
    assertThat(second.readEntity(String.class), containsString("RETURN_QTY_EXCEEDS_PURCHASED"));
  }

  @Test
  void posVoidOrder() {
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"POS\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],"
                + "\"currency\":\"USD\"}",
            T,
            "it-pos-void");
    assertThat(r1.getStatus(), is(201));
    String orderId = extractId(r1.readEntity(String.class));

    Response rv = post("/orders/" + orderId + "/void", "{\"reason\":\"cashier error\"}", T);
    assertThat(rv.getStatus(), is(200));
    assertThat(rv.readEntity(String.class), containsString("cashier error"));
  }

  @Test
  void layawayCreateAndDeposit() {
    Response r1 =
        post(
            "/layaways",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":100.00}],"
                + "\"initialDeposit\":30.00,"
                + "\"paymentMethod\":\"CASH\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String body = r1.readEntity(String.class);
    assertThat(body, containsString("ACTIVE"));
    assertThat(body, containsString("30"));
    String layawayId = extractLayawayId(body);

    // add more deposit
    Response r2 =
        post(
            "/layaways/" + layawayId + "/deposits",
            "{\"amount\":70.00,\"paymentMethod\":\"CARD\"}",
            T);
    assertThat(r2.getStatus(), is(200));

    // complete
    Response r3 = post("/layaways/" + layawayId + "/complete", "{}", T);
    assertThat(r3.getStatus(), is(200));
    assertThat(r3.readEntity(String.class), containsString("COMPLETED"));
  }

  @Test
  void layawayDepositCannotExceedOutstandingBalance() {
    Response created =
        post(
            "/layaways",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":100.00}],"
                + "\"initialDeposit\":30.00,"
                + "\"paymentMethod\":\"CASH\"}",
            T);
    assertThat(created.getStatus(), is(201));
    String layawayId = extractLayawayId(created.readEntity(String.class));

    // Outstanding balance is $70 — a $71 deposit must be rejected, not silently overpay.
    Response overpay =
        post(
            "/layaways/" + layawayId + "/deposits",
            "{\"amount\":71.00,\"paymentMethod\":\"CARD\"}",
            T);
    assertThat(overpay.getStatus(), is(400));
    assertThat(overpay.readEntity(String.class), containsString("LAYAWAY_DEPOSIT_EXCEEDS_BALANCE"));

    // The exact remaining balance is still accepted.
    Response exact =
        post(
            "/layaways/" + layawayId + "/deposits",
            "{\"amount\":70.00,\"paymentMethod\":\"CARD\"}",
            T);
    assertThat(exact.getStatus(), is(200));
  }

  @Test
  void giftCardIssueReloadRedeem() {
    Response r1 =
        post(
            "/gift-cards",
            "{\"storeId\":\"" + S + "\"," + "\"amount\":50.00," + "\"currency\":\"USD\"}",
            T);
    assertThat(r1.getStatus(), is(201));
    String gcBody = r1.readEntity(String.class);
    assertThat(gcBody, containsString("ACTIVE"));
    String code = extractCode(gcBody);

    // lookup
    Response rg = get("/gift-cards/" + code, T);
    assertThat(rg.getStatus(), is(200));

    // reload
    Response r2 = post("/gift-cards/" + code + "/reload", "{\"amount\":20.00}", T);
    assertThat(r2.getStatus(), is(200));
    assertThat(r2.readEntity(String.class), containsString("70"));

    // redeem
    Response r3 = post("/gift-cards/" + code + "/redeem", "{\"amount\":30.00}", T);
    assertThat(r3.getStatus(), is(200));

    // tenant isolation — other tenant cannot see this card
    Response rIso = get("/gift-cards/" + code, "99999999-9999-9999-9999-999999999999");
    assertThat(rIso.getStatus(), is(404));
  }

  @Test
  void voidOnlineOrderFails() {
    Response r1 =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\","
                + "\"channel\":\"ONLINE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],"
                + "\"currency\":\"USD\"}",
            T,
            "it-void-online");
    String orderId = extractId(r1.readEntity(String.class));
    Response rv = post("/orders/" + orderId + "/void", "{\"reason\":\"test\"}", T);
    assertThat(rv.getStatus(), is(409));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  /**
   * JSON-B (Yasson) serialises record components alphabetically, so in LayawayResponse the
   * "deposits" array (with its own "id" fields) appears before the top-level "id" in the JSON
   * stream. A naïve first-occurrence search grabs a deposit UUID instead of the layaway UUID. This
   * helper skips past the closing "]" of the deposits array and then reads the next "id" value,
   * which is the layaway's own id.
   */
  private static String extractLayawayId(String json) {
    // Find where the "deposits" array key starts, then skip to its closing ']'
    int depositsKey = json.indexOf("\"deposits\":");
    int depositsArrayClose = json.indexOf("]", depositsKey);
    // The next "id" after the deposits array is the top-level layaway id
    int start = json.indexOf("\"id\":\"", depositsArrayClose) + 6;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static java.util.Set<String> extractAllIds(String json) {
    var ids = new java.util.HashSet<String>();
    int from = 0;
    while (true) {
      int start = json.indexOf("\"id\":\"", from);
      if (start < 0) break;
      start += 6;
      int end = json.indexOf("\"", start);
      ids.add(json.substring(start, end));
      from = end;
    }
    return ids;
  }

  /** Returns meta.nextCursor, or null when the field is absent/null (no further page). */
  private static String extractNextCursor(String json) {
    int key = json.indexOf("\"nextCursor\":");
    if (key < 0) return null;
    int valueStart = key + "\"nextCursor\":".length();
    if (json.startsWith("null", valueStart)) return null;
    int start = json.indexOf("\"", valueStart) + 1;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }

  private static String extractCode(String json) {
    int start = json.indexOf("\"code\":\"") + 8;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }
}
