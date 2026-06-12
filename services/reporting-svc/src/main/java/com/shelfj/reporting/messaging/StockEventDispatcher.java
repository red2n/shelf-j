package com.shelfj.reporting.messaging;

import com.shelfj.reporting.repo.ReportingRepository;
import com.shelfj.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Routes incoming inventory events to the correct projection update. One dispatcher handles all
 * stock-related topics so each handler concern is a single private method (SRP).
 *
 * <p>Idempotency: the stock-delta projections dedupe on eventId atomically with their writes
 * (golden rule #7); transfer events are naturally idempotent. Malformed payloads are skipped; write
 * failures propagate so the consumer loop redelivers instead of losing the event.
 */
@ApplicationScoped
class StockEventDispatcher {

  private static final Logger LOG = System.getLogger(StockEventDispatcher.class.getName());
  private static final String CONSUMER = "reporting-svc/stock-events";

  @Inject ReportingService service;
  @Inject ReportingRepository repo;

  void dispatch(String topic, String json) {
    JsonObject obj;
    UUID eventId;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed stock event on {0} skipped: {1}", topic, e.getMessage());
      return;
    }

    try {
      switch (topic) {
        case "shelfj.inventory.stock-received" ->
            applyDelta(eventId, obj, qty(obj), "StockReceived");
        case "shelfj.inventory.stock-deducted" -> handleDeducted(eventId, obj);
        case "shelfj.inventory.stock-adjusted" ->
            applyDelta(eventId, obj, new BigDecimal(obj.get("delta").toString()), "StockAdjusted");
        case "shelfj.inventory.transfer-order-shipped" -> handleTransferShipped(eventId, obj);
        case "shelfj.inventory.transfer-order-received" ->
            // delete-by-event is naturally idempotent — no dedupe mark needed
            service.applyTransferReceived(eventId);
        default -> LOG.log(Level.WARNING, "Unknown topic {0} — ignored", topic);
      }
    } catch (RuntimeException e) {
      // A field missing from the payload throws the same shapes on every redelivery — skip
      // those; anything else (DB down etc.) propagates so the record is retried.
      if (isMalformed(e)) {
        LOG.log(Level.WARNING, "Malformed stock event on {0} skipped: {1}", topic, e.getMessage());
        return;
      }
      throw e;
    }
  }

  private void applyDelta(UUID eventId, JsonObject obj, BigDecimal delta, String eventType) {
    UUID tenantId = UUID.fromString(obj.getString("tenantId"));
    UUID storeId = UUID.fromString(obj.getString("storeId"));
    UUID variantId = UUID.fromString(obj.getString("variantId"));
    service.applyStockDeltaOnce(eventId, CONSUMER, tenantId, storeId, variantId, delta, eventType);
  }

  private void handleDeducted(UUID eventId, JsonObject obj) {
    // StockDeducted now carries storeId/variantId/qty (enriched in inventory-svc)
    if (!obj.containsKey("storeId") || !obj.containsKey("variantId")) {
      LOG.log(Level.WARNING, "StockDeducted missing storeId/variantId — skipped");
      return;
    }
    applyDelta(eventId, obj, qty(obj).negate(), "StockDeducted");
  }

  private void handleTransferShipped(UUID eventId, JsonObject obj) {
    UUID tenantId = UUID.fromString(obj.getString("tenantId"));
    UUID fromStoreId = UUID.fromString(obj.getString("fromStoreId"));
    UUID toStoreId = UUID.fromString(obj.getString("toStoreId"));
    // Dedupe BEFORE inserting: supply-line ids are random, so a redelivered event would
    // otherwise add duplicate rows. (Currently the event carries no line details and the
    // lists are empty placeholders — see applyTransferShipped.)
    if (!repo.markProcessedIfNew(eventId, CONSUMER)) {
      return;
    }
    service.applyTransferShipped(tenantId, eventId, fromStoreId, toStoreId, List.of(), List.of());
  }

  private static BigDecimal qty(JsonObject obj) {
    return new BigDecimal(obj.get("qty").toString());
  }

  private static boolean isMalformed(RuntimeException e) {
    return e instanceof NullPointerException
        || e instanceof IllegalArgumentException
        || e instanceof ClassCastException
        || e instanceof jakarta.json.JsonException;
  }
}
