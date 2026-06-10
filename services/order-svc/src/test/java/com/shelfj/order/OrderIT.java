package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
    System.setProperty("shelfj.db.user", PG.username());
    System.setProperty("shelfj.db.password", PG.password());
    System.setProperty("shelfj.db.schema", "order");
    System.setProperty("shelfj.consul.enabled", "false");
    System.setProperty("shelfj.kafka.enabled", "false");
  }

  private static final String T = "11111111-1111-1111-1111-111111111111";
  private static final String S = "22222222-2222-2222-2222-222222222222";
  private static final String V = "33333333-3333-3333-3333-333333333333";

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
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String path, String tenant) {
    return target.path(path).request().header("X-Tenant-Id", tenant).get();
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
            T);
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
            T);
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
            T);
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

  private static String extractCode(String json) {
    int start = json.indexOf("\"code\":\"") + 8;
    int end = json.indexOf("\"", start);
    return json.substring(start, end);
  }
}
