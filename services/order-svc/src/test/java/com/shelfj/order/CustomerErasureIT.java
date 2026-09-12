package com.shelfj.order;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.shelfj.ids.Ids;
import com.shelfj.order.service.OrderService;
import com.shelfj.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A customer a shop erased stops being identifiable in that shop's orders (SJ-D43).
 *
 * <p>Settled orders lose the customer's name, phone and address at once. An open order keeps its
 * delivery details until it finishes — they are needed to deliver it — and is redacted then. The
 * sale itself stays: amounts and lines are tax records.
 */
@HelidonTest
class CustomerErasureIT {

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
    System.setProperty("shelfj.order.pricing.enforce", "false");
    System.setProperty("shelfj.order.inventory.reserve-enforce", "false");
    System.setProperty("shelfj.order.erasure-sweeper.enabled", "false");
  }

  private static final String T = "01a090ae-611e-702b-8e05-b3c421241865";
  private static final String S = "01a090ae-611e-7036-97eb-b0b2629f1654";
  private static final String V = "01a090ae-611e-703b-9569-17e55100ef17";

  @Inject WebTarget target;
  @Inject OrderService orderService;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private Response post(String path, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", "OWNER")
        .header("Idempotency-Key", Ids.newId().toString())
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String id(String json) {
    var m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(json);
    return m.find() ? m.group(1) : null;
  }

  private UUID place(String customer, String channel, String fulfilment, String extra) {
    Response r =
        post(
            "/orders",
            "{\"storeId\":\""
                + S
                + "\",\"channel\":\""
                + channel
                + "\",\"fulfilmentType\":\""
                + fulfilment
                + "\",\"customerId\":\""
                + customer
                + "\","
                + extra
                + "\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}]}");
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    return UUID.fromString(id(body));
  }

  private void pay(UUID order) {
    orderService.handlePaymentCaptured(
        UUID.fromString(T), order, Ids.newId(), new BigDecimal("10.00"));
  }

  private static String column(String table, UUID id, String column) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement("SELECT " + column + " FROM \"order\"." + table + " WHERE id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final String DELIVERY =
      "\"deliveryLine1\":\"12 High Street\",\"deliveryCity\":\"London\","
          + "\"deliveryPostalCode\":\"EC1A 1BB\",\"deliveryRecipientName\":\"Chris Carter\","
          + "\"deliveryRecipientPhone\":\"07700900123\",";

  @Test
  @DisplayName("A finished sale loses the customer's details at once, and keeps the sale")
  void settledOrdersAreRedactedNow() {
    String customer = Ids.newId().toString();
    UUID order = place(customer, "POS", "INSTORE", "\"contactPhone\":\"07700900999\",");
    pay(order); // a till sale: FULFILLED
    assertThat(column("orders", order, "contact_phone"), is("07700900999"));

    orderService.handleCustomerErased(UUID.fromString(T), UUID.fromString(customer), Ids.newId());

    assertThat(column("orders", order, "contact_phone"), nullValue());
    // The tax record stays.
    assertThat(column("orders", order, "total"), notNullValue());
    assertThat(column("orders", order, "status"), is("FULFILLED"));
  }

  @Test
  @DisplayName("An open delivery keeps its address until it is done, then loses it")
  void openOrdersAreHeldUntilTheyFinish() {
    String customer = Ids.newId().toString();
    UUID order = place(customer, "ONLINE", "DELIVERY", DELIVERY);
    pay(order); // online: CONFIRMED, not yet delivered

    orderService.handleCustomerErased(UUID.fromString(T), UUID.fromString(customer), Ids.newId());
    // Still needed to deliver it.
    assertThat(column("orders", order, "delivery_line1"), is("12 High Street"));
    assertThat(column("orders", order, "delivery_recipient_phone"), is("07700900123"));

    assertThat(post("/orders/" + order + "/fulfil", "{}").getStatus(), is(200));
    orderService.sweepErasures();

    assertThat(column("orders", order, "delivery_line1"), nullValue());
    assertThat(column("orders", order, "delivery_postal_code"), nullValue());
    assertThat(column("orders", order, "delivery_recipient_name"), nullValue());
    assertThat(column("orders", order, "delivery_recipient_phone"), nullValue());
  }

  @Test
  @DisplayName("A redelivered erasure changes nothing a second time")
  void redeliveryIsANoOp() {
    String customer = Ids.newId().toString();
    UUID event = Ids.newId();
    assertThat(
        orderService.handleCustomerErased(UUID.fromString(T), UUID.fromString(customer), event),
        is(true));
    assertThat(
        orderService.handleCustomerErased(UUID.fromString(T), UUID.fromString(customer), event),
        is(false));
  }

  @Test
  @DisplayName("Another customer's orders are untouched")
  void onlyTheErasedCustomer() {
    String erased = Ids.newId().toString();
    String kept = Ids.newId().toString();
    UUID theirs = place(kept, "POS", "INSTORE", "\"contactPhone\":\"07700900555\",");
    pay(theirs);

    orderService.handleCustomerErased(UUID.fromString(T), UUID.fromString(erased), Ids.newId());
    orderService.sweepErasures();

    assertThat(column("orders", theirs, "contact_phone"), is("07700900555"));
  }

  @Test
  @DisplayName("A receipt's email address and a held basket's name go at once, even on open sales")
  void receiptsAndParkedSalesAtOnce() {
    String customer = Ids.newId().toString();
    UUID open = place(customer, "ONLINE", "DELIVERY", DELIVERY);
    // The row an emailed receipt leaves behind. Written directly: sending one calls
    // notification-svc,
    // which is not running here, and the send is not what this test is about.
    UUID receiptId = Ids.newId();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "INSERT INTO \"order\".order_receipts"
                    + " (id, tenant_id, order_id, receipt_type, emailed_to, print_count)"
                    + " VALUES (?, ?, ?, 'EMAIL', 'chris@example.com', 1)")) {
      ps.setObject(1, receiptId);
      ps.setObject(2, UUID.fromString(T));
      ps.setObject(3, open);
      ps.executeUpdate();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }

    Response parked =
        post(
            "/pos/parked-sales",
            "{\"storeId\":\""
                + S
                + "\",\"customerId\":\""
                + customer
                + "\",\"customerName\":\"Chris Carter\",\"items\":[{\"variantId\":\""
                + V
                + "\",\"qty\":1,\"unitPrice\":10.00}]}");
    assertThat(parked.getStatus(), is(201));
    UUID parkedId = UUID.fromString(id(parked.readEntity(String.class)));

    orderService.handleCustomerErased(UUID.fromString(T), UUID.fromString(customer), Ids.newId());

    assertThat(column("order_receipts", receiptId, "emailed_to"), nullValue());
    assertThat(column("parked_sales", parkedId, "customer_name"), nullValue());
    // The open order itself is still deliverable.
    assertThat(column("orders", open, "delivery_line1"), is("12 High Street"));
  }
}
