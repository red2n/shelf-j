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
 * <p>Idempotency: every message is deduped via {@code processed_events} before any projection
 * change. The same event processed twice has the same result as once (golden rule #7).
 */
@ApplicationScoped
class StockEventDispatcher {

  private static final Logger LOG = System.getLogger(StockEventDispatcher.class.getName());
  private static final String CONSUMER = "reporting-svc/stock-events";

  @Inject ReportingService service;
  @Inject ReportingRepository repo;

  void dispatch(String topic, String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      UUID eventId = UUID.fromString(obj.getString("eventId"));

      if (!repo.markProcessedIfNew(eventId, CONSUMER)) {
        return;
      }

      UUID tenantId = UUID.fromString(obj.getString("tenantId"));

      switch (topic) {
        case "shelfj.inventory.stock-received" -> handleReceived(tenantId, obj);
        case "shelfj.inventory.stock-deducted" -> handleDeducted(tenantId, obj);
        case "shelfj.inventory.stock-adjusted" -> handleAdjusted(tenantId, obj);
        case "shelfj.inventory.transfer-order-shipped" ->
            handleTransferShipped(tenantId, eventId, obj);
        case "shelfj.inventory.transfer-order-received" -> service.applyTransferReceived(eventId);
        default -> LOG.log(Level.WARNING, "Unknown topic {0} — ignored", topic);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to dispatch stock event: " + e.getMessage());
    }
  }

  private void handleReceived(UUID tenantId, JsonObject obj) {
    UUID storeId = UUID.fromString(obj.getString("storeId"));
    UUID variantId = UUID.fromString(obj.getString("variantId"));
    BigDecimal qty = new BigDecimal(obj.get("qty").toString());
    service.applyStockReceived(tenantId, storeId, variantId, qty);
  }

  private void handleDeducted(UUID tenantId, JsonObject obj) {
    // StockDeducted now carries storeId/variantId/qty (enriched in inventory-svc)
    if (!obj.containsKey("storeId") || !obj.containsKey("variantId")) {
      LOG.log(Level.WARNING, "StockDeducted missing storeId/variantId — skipped");
      return;
    }
    UUID storeId = UUID.fromString(obj.getString("storeId"));
    UUID variantId = UUID.fromString(obj.getString("variantId"));
    BigDecimal qty = new BigDecimal(obj.get("qty").toString());
    service.applyStockDeducted(tenantId, storeId, variantId, qty);
  }

  private void handleAdjusted(UUID tenantId, JsonObject obj) {
    UUID storeId = UUID.fromString(obj.getString("storeId"));
    UUID variantId = UUID.fromString(obj.getString("variantId"));
    BigDecimal delta = new BigDecimal(obj.get("delta").toString());
    service.applyStockAdjusted(tenantId, storeId, variantId, delta);
  }

  private void handleTransferShipped(UUID tenantId, UUID eventId, JsonObject obj) {
    UUID fromStoreId = UUID.fromString(obj.getString("fromStoreId"));
    UUID toStoreId = UUID.fromString(obj.getString("toStoreId"));
    // TransferOrderShipped event doesn't carry line details — record at order level with qty=0
    // as a placeholder; full line data would require enriching that event (future work).
    service.applyTransferShipped(tenantId, eventId, fromStoreId, toStoreId, List.of(), List.of());
  }
}
