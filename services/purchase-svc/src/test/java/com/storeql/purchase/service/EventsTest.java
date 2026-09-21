package com.storeql.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.LandedCost;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

  private static JsonObject payloadOf(com.storeql.service.OutboxRow row) {
    try (var r = Json.createReader(new StringReader(row.payload()))) {
      return r.readObject();
    }
  }

  private static GoodsReceiptLine line(UUID variantId, String qty) {
    return new GoodsReceiptLine(
        Ids.newId(), Ids.newId(), Ids.newId(), variantId, new BigDecimal(qty), Instant.now());
  }

  /**
   * SJ-D21. inventory-svc writes these movements as {@code ref_type='GRN'}, so {@code refId} has to
   * be the goods receipt. It was the purchase order — survivable while an order could have only one
   * receipt, and wrong the moment partial receipt let it have several, because every delivery then
   * cited the same id under a label claiming to name a specific one.
   */
  @Test
  void goodsReceivedRefersToTheReceiptNotThePurchaseOrder() {
    UUID tenant = Ids.newId();
    UUID grn = Ids.newId();
    UUID store = Ids.newId();
    UUID po = Ids.newId();

    JsonObject p =
        payloadOf(
            Events.goodsReceived(
                tenant, grn, store, po, List.of(line(Ids.newId(), "6")), Map.of()));

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
    UUID tenant = Ids.newId();
    UUID store = Ids.newId();
    UUID po = Ids.newId();
    UUID first = Ids.newId();
    UUID second = Ids.newId();

    JsonObject a =
        payloadOf(
            Events.goodsReceived(
                tenant, first, store, po, List.of(line(Ids.newId(), "6")), Map.of()));
    JsonObject b =
        payloadOf(
            Events.goodsReceived(
                tenant, second, store, po, List.of(line(Ids.newId(), "4")), Map.of()));

    assertTrue(
        !a.getString("refId").equals(b.getString("refId")),
        "two deliveries against one order must not share a reference");
    // ...while still agreeing about which order they belong to.
    assertEquals(a.getString("poId"), b.getString("poId"));
  }

  @Test
  void everyReceiptLineIsCarried() {
    UUID v1 = Ids.newId();
    UUID v2 = Ids.newId();
    JsonObject p =
        payloadOf(
            Events.goodsReceived(
                Ids.newId(),
                Ids.newId(),
                Ids.newId(),
                Ids.newId(),
                List.of(line(v1, "6"), line(v2, "4")),
                Map.of()));

    var lines = p.getJsonArray("lines");
    assertEquals(2, lines.size());
    assertEquals(v1.toString(), lines.getJsonObject(0).getString("variantId"));
    assertEquals(v2.toString(), lines.getJsonObject(1).getString("variantId"));
  }

  /**
   * 07.x: the batch inventory-svc creates knows what it cost only if the receipt says so. The
   * order's price rides along per line; a line the order does not price carries no cost at all,
   * which valuation reports as unvalued rather than pretending it was free.
   */
  @Test
  void theOrdersPriceRidesAlongAsTheBatchCost() {
    UUID priced = Ids.newId();
    UUID unpriced = Ids.newId();
    JsonObject p =
        payloadOf(
            Events.goodsReceived(
                Ids.newId(),
                Ids.newId(),
                Ids.newId(),
                Ids.newId(),
                List.of(line(priced, "6"), line(unpriced, "4")),
                Map.of(priced, new BigDecimal("2.50"))));

    var lines = p.getJsonArray("lines");
    assertEquals("2.50", lines.getJsonObject(0).getJsonNumber("costPrice").toString());
    assertFalse(lines.getJsonObject(1).containsKey("costPrice"), "no price known, no price sent");
  }

  /**
   * A landed charge is announced with what each unit rose by, keyed on the charge so a reversal
   * follows what it reverses; the reversal is the same shape under its own event id.
   */
  @Test
  void aLandedChargeCarriesEachLinesUpliftAndKeysOnTheCharge() {
    UUID tenant = Ids.newId();
    UUID chargeId = Ids.newId();
    UUID gr = Ids.newId();
    UUID variant = Ids.newId();
    var charge =
        new LandedCost.Charge(
            chargeId,
            tenant,
            gr,
            Ids.newId(),
            Ids.newId(),
            "FREIGHT",
            "BY_VALUE",
            "GBP",
            new BigDecimal("10.00"),
            "CN-1",
            null,
            null,
            LandedCost.STATUS_APPLIED,
            Instant.now(),
            null,
            null,
            null,
            null,
            null);
    var line =
        new LandedCost.Line(
            Ids.newId(),
            tenant,
            chargeId,
            Ids.newId(),
            variant,
            new BigDecimal("8"),
            new BigDecimal("20.00"),
            new BigDecimal("10.00"),
            new BigDecimal("1.2500"));
    var applied = Events.landedCost(Events.LANDED_COST_APPLIED, charge, List.of(line), chargeId);
    JsonObject p = payloadOf(applied);

    assertEquals("storeql.purchase.landed-cost-applied", applied.topic());
    assertEquals(chargeId, applied.aggregateId());
    assertEquals("LandedCostApplied", p.getString("eventType"));
    assertEquals(gr.toString(), p.getString("refId"), "refId names the receipt whose batches move");
    var l = p.getJsonArray("lines").getJsonObject(0);
    assertEquals(variant.toString(), l.getString("variantId"));
    assertEquals("1.2500", l.getJsonNumber("perUnit").toString());
    assertEquals("10.00", l.getJsonNumber("amount").toString());

    UUID reversalId = Ids.derived(chargeId, "landed-cost-reversal");
    JsonObject r =
        payloadOf(
            Events.landedCost(Events.LANDED_COST_REVERSED, charge, List.of(line), reversalId));
    assertEquals("LandedCostReversed", r.getString("eventType"));
    assertEquals(reversalId.toString(), r.getString("eventId"));
    assertEquals(chargeId.toString(), r.getString("landedCostId"));
  }
}
