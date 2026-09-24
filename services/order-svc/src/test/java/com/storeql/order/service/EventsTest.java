package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.ReturnItem;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * inventory-svc's OrderEventHandler requires a top-level {@code eventId} on every OrderFulfilled /
 * OrderReturned payload for per-line dedupe — without it the event is silently skipped as malformed
 * and stock is never deducted/restocked. Regression coverage for that bug.
 */
class EventsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID RETURN = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  @Test
  void orderFulfilledPayloadCarriesAParseableEventId() {
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var row = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));

    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertDoesNotThrow(() -> Ids.parse(json.getString("eventId")));
  }

  @Test
  void orderFulfilledGeneratesADistinctEventIdPerCall() {
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var first = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));
    var second = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));

    String firstId =
        Json.createReader(new StringReader(first.payload())).readObject().getString("eventId");
    String secondId =
        Json.createReader(new StringReader(second.payload())).readObject().getString("eventId");
    assertNotEquals(firstId, secondId);
  }

  @Test
  void orderReturnedPayloadCarriesAParseableEventId() {
    var item =
        new ReturnItem(Ids.newId(), TENANT, RETURN, VARIANT, BigDecimal.ONE, BigDecimal.TEN, null);
    var row =
        Events.orderReturned(
            TENANT, ORDER, RETURN, STORE, List.of(item), BigDecimal.TEN, "ORIGINAL", "GBP");

    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertDoesNotThrow(() -> Ids.parse(json.getString("eventId")));
    assertEquals("OrderReturned", json.getString("eventType"));
    // payment-svc reverses the captured payment from these fields for ORIGINAL-tender returns.
    assertEquals("ORIGINAL", json.getString("refundMethod"));
    assertEquals(0, BigDecimal.TEN.compareTo(json.getJsonNumber("refundAmount").bigDecimalValue()));
  }

  /**
   * Sales by category (19.x) is read line by line in reporting-svc, and OrderConfirmed is the event
   * that says a sale happened: it carries each line's variant, quantity and money.
   */
  @Test
  void orderConfirmedCarriesItsLines() {
    UUID other = Ids.newId();
    var cola =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            new BigDecimal("2"),
            new BigDecimal("2.00"),
            new BigDecimal("4.00"),
            null,
            null);
    var crisps =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            other,
            new BigDecimal("1.500"),
            new BigDecimal("1.00"),
            new BigDecimal("1.50"),
            null,
            null);
    var row =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "POS",
            null,
            new BigDecimal("5.50"),
            new BigDecimal("0.92"),
            "GBP",
            List.of(cola, crisps));

    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals("OrderConfirmed", json.getString("eventType"));
    assertEquals(
        0, new BigDecimal("5.50").compareTo(json.getJsonNumber("total").bigDecimalValue()));
    var lines = json.getJsonArray("lines");
    assertEquals(2, lines.size());
    var first = lines.getJsonObject(0);
    assertEquals(VARIANT.toString(), first.getString("variantId"));
    assertEquals(0, new BigDecimal("2").compareTo(first.getJsonNumber("qty").bigDecimalValue()));
    assertEquals(
        0, new BigDecimal("2.00").compareTo(first.getJsonNumber("unitPrice").bigDecimalValue()));
    assertEquals(
        0, new BigDecimal("4.00").compareTo(first.getJsonNumber("lineTotal").bigDecimalValue()));
    assertEquals(other.toString(), lines.getJsonObject(1).getString("variantId"));
  }

  @Test
  void orderConfirmedCarriesWhereADeliveryGoes() {
    // Dropship (consignment and dropship stock ownership): purchase-svc raises the supplier's order
    // from this event, so it must say how the order is fulfilled and where the goods go.
    var row =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "ONLINE",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of(),
            "DELIVERY",
            "12 High Street, Leeds, LS1 1AA",
            "Chris Carter",
            "07700900123");
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals("DELIVERY", json.getString("fulfilmentType"));
    assertEquals("12 High Street, Leeds, LS1 1AA", json.getString("deliveryAddress"));
    assertEquals("Chris Carter", json.getString("deliveryRecipientName"));
    assertEquals("07700900123", json.getString("deliveryRecipientPhone"));

    // A till sale delivers nowhere: the fields are there and null, never missing or empty.
    var till =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "POS",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of(),
            "INSTORE",
            null,
            null,
            null);
    JsonObject tillJson = Json.createReader(new StringReader(till.payload())).readObject();
    assertEquals("INSTORE", tillJson.getString("fulfilmentType"));
    assertEquals(true, tillJson.isNull("deliveryAddress"));
  }

  @Test
  void orderConfirmedWithNoLinesStillSaysSo() {
    var row =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "ONLINE",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of());
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals(0, json.getJsonArray("lines").size());
  }
}
