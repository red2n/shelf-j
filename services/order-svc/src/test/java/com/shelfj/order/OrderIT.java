package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
    // Disable checkout stock holds so ONLINE orders don't need a live inventory-svc.
    System.setProperty("shelfj.order.inventory.reserve-enforce", "false");
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

  private Response listSpecialOrders(String tenant, int limit, String after) {
    WebTarget t = target.path("/admin/special-orders").queryParam("limit", limit);
    if (after != null) t = t.queryParam("after", after);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", "OWNER").get();
  }

  private Response listPosLog(String tenant, int limit, String after) {
    WebTarget t = target.path("/admin/pos-log").queryParam("limit", limit);
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

  @Test
  void paymentRefundedFlipsOrderToPartiallyThenFullyRefunded() {
    // place + confirm a POS order (total = 2 × 10.00 = 20.00)
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":2,\"unitPrice\":10.00}],\"currency\":\"GBP\"}",
            T,
            "it-refund-status");
    assertThat(placed.getStatus(), is(201));
    String orderId = extractId(placed.readEntity(String.class));
    assertThat(post("/orders/" + orderId + "/confirm", "{}", T).getStatus(), is(200));

    UUID tenant = UUID.fromString(T);
    UUID order = UUID.fromString(orderId);

    // A 12.00 refund on a 20.00 order → PARTIALLY_REFUNDED (as PaymentEventHandler would call it).
    UUID e1 = UUID.randomUUID();
    orderService.applyRefund(e1, tenant, order, new java.math.BigDecimal("12.00"));
    assertThat(
        get("/orders/" + orderId, T).readEntity(String.class),
        containsString("PARTIALLY_REFUNDED"));

    // Redelivery of the SAME refund event must not add again — a broken dedupe would push
    // cumulative to 24 ≥ 20 and prematurely show REFUNDED.
    orderService.applyRefund(e1, tenant, order, new java.math.BigDecimal("12.00"));
    assertThat(
        get("/orders/" + orderId, T).readEntity(String.class),
        containsString("PARTIALLY_REFUNDED"));

    // The remaining 8.00 (distinct event) → cumulative 20.00 = total → REFUNDED.
    orderService.applyRefund(UUID.randomUUID(), tenant, order, new java.math.BigDecimal("8.00"));
    String finalBody = get("/orders/" + orderId, T).readEntity(String.class);
    // REFUNDED present and PARTIALLY_REFUNDED absent together prove the status is exactly REFUNDED.
    assertThat(finalBody, containsString("REFUNDED"));
    assertThat(finalBody, not(containsString("PARTIALLY_REFUNDED")));
  }

  @Test
  void sweeperCancelsExpiredPendingOrdersButNotConfirmedOnes() {
    String orderJson =
        "{\"storeId\":\""
            + S
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
            + "\"items\":[{\"variantId\":\""
            + V
            + "\",\"qty\":1,\"unitPrice\":10.00}],\"currency\":\"GBP\"}";

    // A: placed, left PENDING (client never paid).
    String aId = extractId(post("/orders", orderJson, T, "it-sweep-a").readEntity(String.class));
    // B: placed then confirmed.
    String bId = extractId(post("/orders", orderJson, T, "it-sweep-b").readEntity(String.class));
    assertThat(post("/orders/" + bId + "/confirm", "{}", T).getStatus(), is(200));

    // TTL of 0h → every still-PENDING order is expired. B is CONFIRMED so the status guard skips
    // it.
    orderService.sweepExpiredPendingOrders(0, 200);

    assertThat(get("/orders/" + aId, T).readEntity(String.class), containsString("CANCELLED"));
    String bBody = get("/orders/" + bId, T).readEntity(String.class);
    assertThat(bBody, containsString("CONFIRMED"));
    assertThat(bBody, not(containsString("CANCELLED")));
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
  void listSpecialOrdersPaginatesWithCursor() {
    // Dedicated tenant so special orders created by other tests never leak into these pages.
    String tenant = "55555555-5555-5555-5555-555555555555";
    var allNames = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      String name = "Cust" + i;
      Response r =
          post(
              "/admin/special-orders",
              "{\"storeId\":\""
                  + S
                  + "\",\"customerName\":\""
                  + name
                  + "\",\"items\":[{\"variantId\":\""
                  + V
                  + "\",\"qty\":1,\"unitPrice\":1.00}]}",
              tenant);
      assertThat(r.getStatus(), is(201));
      allNames.add(name);
    }

    // Each special order's own "id" plus its single item's "id" both match a naive "id":"..."
    // scan, so page membership is checked via the per-order customerName instead (unique, and
    // absent from the nested item objects).
    Response p1 = listSpecialOrders(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllCustomerNames(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    Response p2 = listSpecialOrders(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllCustomerNames(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen, is(allNames));
  }

  private static java.util.Set<String> extractAllCustomerNames(String json) {
    var names = new java.util.HashSet<String>();
    int from = 0;
    while (true) {
      int start = json.indexOf("\"customerName\":\"", from);
      if (start < 0) break;
      start += "\"customerName\":\"".length();
      int end = json.indexOf('"', start);
      names.add(json.substring(start, end));
      from = end;
    }
    return names;
  }

  @Test
  void listPosLogPaginatesWithCursor() {
    // Dedicated tenant so POSLog entries created by other tests never leak into these pages.
    String tenant = "66666666-6666-6666-6666-666666666666";
    var allIds = new java.util.HashSet<String>();
    for (int i = 0; i < 3; i++) {
      Response placed =
          post(
              "/orders",
              "{\"storeId\":\""
                  + S
                  + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                  + "\"items\":[{\"variantId\":\""
                  + V
                  + "\",\"qty\":1,\"unitPrice\":1.00}],\"currency\":\"USD\"}",
              tenant,
              "it-poslog-" + i);
      assertThat(placed.getStatus(), is(201));
      String orderId = extractId(placed.readEntity(String.class));
      Response logged = post("/admin/pos-log/orders/" + orderId, "", tenant);
      assertThat(logged.getStatus(), is(201));
      allIds.add(extractId(logged.readEntity(String.class)));
    }

    Response p1 = listPosLog(tenant, 2, null);
    assertThat(p1.getStatus(), is(200));
    String body1 = p1.readEntity(String.class);
    java.util.Set<String> page1 = extractAllIds(body1);
    assertThat(page1.size(), is(2));
    String cursor = extractNextCursor(body1);
    assertThat(cursor, org.hamcrest.Matchers.notNullValue());

    Response p2 = listPosLog(tenant, 2, cursor);
    assertThat(p2.getStatus(), is(200));
    String body2 = p2.readEntity(String.class);
    java.util.Set<String> page2 = extractAllIds(body2);
    assertThat(page2.size(), is(1));
    assertThat(extractNextCursor(body2), org.hamcrest.Matchers.nullValue());

    java.util.Set<String> seen = new java.util.HashSet<>(page1);
    seen.addAll(page2);
    assertThat(seen, is(allIds));
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

  @Test
  void orderByIdReadsAreObjectLevelAuthorized() {
    String owningCustomer = UUID.randomUUID().toString();
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"customerId\":\""
                + owningCustomer
                + "\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":5.00}],\"currency\":\"USD\"}",
            T,
            "it-idor-guard");
    assertThat(placed.getStatus(), is(201));
    String orderId = extractId(placed.readEntity(String.class));

    // The owning customer may read their order, its history and its returns.
    assertThat(getAs("/orders/" + orderId, T, owningCustomer, "CUSTOMER").getStatus(), is(200));
    assertThat(
        getAs("/orders/" + orderId + "/history", T, owningCustomer, "CUSTOMER").getStatus(),
        is(200));
    assertThat(
        getAs("/orders/" + orderId + "/returns", T, owningCustomer, "CUSTOMER").getStatus(),
        is(200));

    // Another authenticated customer in the same tenant gets 404 (not 403 — no existence oracle).
    String otherCustomer = UUID.randomUUID().toString();
    assertThat(getAs("/orders/" + orderId, T, otherCustomer, "CUSTOMER").getStatus(), is(404));
    assertThat(
        getAs("/orders/" + orderId + "/history", T, otherCustomer, "CUSTOMER").getStatus(),
        is(404));
    assertThat(
        getAs("/orders/" + orderId + "/returns", T, otherCustomer, "CUSTOMER").getStatus(),
        is(404));

    // Staff read any order in the tenant.
    assertThat(get("/orders/" + orderId, T).getStatus(), is(200));

    // A service-to-service lookup (X-Tenant-Id only, no principal) keeps working — payment-svc
    // verifies online payment claims through this exact shape (see payment-svc OrderClient).
    Response s2s = target.path("/orders/" + orderId).request().header("X-Tenant-Id", T).get();
    assertThat(s2s.getStatus(), is(200));
  }

  @Test
  void placeOrderRejectsNonPositiveItemQty() {
    // items is @NotNull @Valid — a zero qty must be rejected by cascading Bean Validation instead
    // of silently placing an order for nothing.
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":0,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            T,
            "it-reject-zero-qty");
    assertThat(r.getStatus(), is(400));
  }

  @Test
  void cancelRejectsABlankReason() {
    Response placed =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\","
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}],\"currency\":\"USD\"}",
            T,
            "it-cancel-validation");
    assertThat(placed.getStatus(), is(201));
    String orderId = extractId(placed.readEntity(String.class));

    // A body with a blank reason violates VoidRequest's @NotBlank and must be rejected — this
    // constraint previously went unenforced because the resource never called Validations.validate.
    assertThat(post("/orders/" + orderId + "/cancel", "{\"reason\":\"\"}", T).getStatus(), is(400));

    // A body with a real reason still works.
    assertThat(
        post("/orders/" + orderId + "/cancel", "{\"reason\":\"customer changed mind\"}", T)
            .getStatus(),
        is(200));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Response getAs(String path, String tenant, String userId, String roles) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", userId)
        .header("X-Roles", roles)
        .get();
  }

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
