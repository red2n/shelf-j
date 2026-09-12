package com.shelfj.order.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.ReturnItem;
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
    assertDoesNotThrow(() -> UUID.fromString(json.getString("eventId")));
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
    assertDoesNotThrow(() -> UUID.fromString(json.getString("eventId")));
    assertEquals("OrderReturned", json.getString("eventType"));
    // payment-svc reverses the captured payment from these fields for ORIGINAL-tender returns.
    assertEquals("ORIGINAL", json.getString("refundMethod"));
    assertEquals(0, BigDecimal.TEN.compareTo(json.getJsonNumber("refundAmount").bigDecimalValue()));
  }
}
