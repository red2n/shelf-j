package com.shelfj.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Payload tests for the events purchase-svc publishes.
 *
 * <p>These exist because SJ-D21 was invisible to every other kind of test. The event was
 * well-formed, inventory-svc parsed it without complaint, stock was booked in the right quantity
 * against the right store, and the resulting movement pointed at the wrong record — under a label
 * that named the record type it was not. Nothing failed; the traceability was simply wrong, and it
 * only became visible when a purchase order could have more than one receipt.
 */
class EventsTest {

  private static JsonObject payloadOf(com.shelfj.service.OutboxRow row) {
    try (var r = Json.createReader(new StringReader(row.payload()))) {
      return r.readObject();
    }
  }

  private static GoodsReceiptLine line(UUID variantId, String qty) {
    return new GoodsReceiptLine(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        variantId,
        new BigDecimal(qty),
        Instant.now());
  }

  /**
   * SJ-D21. inventory-svc writes these movements as {@code ref_type='GRN'}, so {@code refId} has to
   * be the goods receipt. It was the purchase order — survivable while an order could have only one
   * receipt, and wrong the moment partial receipt let it have several, because every delivery then
   * cited the same id under a label claiming to name a specific one.
   */
  @Test
  void goodsReceivedRefersToTheReceiptNotThePurchaseOrder() {
    UUID tenant = UUID.randomUUID();
    UUID grn = UUID.randomUUID();
    UUID store = UUID.randomUUID();
    UUID po = UUID.randomUUID();

    JsonObject p =
        payloadOf(
            Events.goodsReceived(tenant, grn, store, po, List.of(line(UUID.randomUUID(), "6"))));

    assertEquals(grn.toString(), p.getString("refId"), "refId must name the goods receipt");
    // The order is still carried, so a consumer that wants it needs no second lookup.
    assertEquals(po.toString(), p.getString("poId"));
    assertEquals(grn.toString(), p.getString("eventId"));
    assertEquals(store.toString(), p.getString("storeId"));
  }

  /**
   * The case that makes SJ-D21 matter: two receipts against one order must be distinguishable from
   * the stock movements they produce. They used to carry identical references.
   */
  @Test
  void twoReceiptsAgainstOneOrderAreDistinguishable() {
    UUID tenant = UUID.randomUUID();
    UUID store = UUID.randomUUID();
    UUID po = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();

    JsonObject a =
        payloadOf(
            Events.goodsReceived(tenant, first, store, po, List.of(line(UUID.randomUUID(), "6"))));
    JsonObject b =
        payloadOf(
            Events.goodsReceived(tenant, second, store, po, List.of(line(UUID.randomUUID(), "4"))));

    assertTrue(
        !a.getString("refId").equals(b.getString("refId")),
        "two deliveries against one order must not share a reference");
    // ...while still agreeing about which order they belong to.
    assertEquals(a.getString("poId"), b.getString("poId"));
  }

  @Test
  void everyReceiptLineIsCarried() {
    UUID v1 = UUID.randomUUID();
    UUID v2 = UUID.randomUUID();
    JsonObject p =
        payloadOf(
            Events.goodsReceived(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(line(v1, "6"), line(v2, "4"))));

    var lines = p.getJsonArray("lines");
    assertEquals(2, lines.size());
    assertEquals(v1.toString(), lines.getJsonObject(0).getString("variantId"));
    assertEquals(v2.toString(), lines.getJsonObject(1).getString("variantId"));
  }
}
