package com.shelfj.inventory.messaging;

import com.shelfj.inventory.repo.InventoryRepository;
import com.shelfj.inventory.service.InventoryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Gap #50 — POS→SIM direction. Handles OrderFulfilled and OrderReturned events from order-svc.
 *
 * <ul>
 *   <li>OrderFulfilled → FIFO-deduct stock for each line item (SALE movement).
 *   <li>OrderReturned → receive stock back for each returned line item (RETURN movement).
 * </ul>
 *
 * <p>Idempotent: deduplicates on eventId via processed_events. Each event may carry multiple line
 * items; each is processed independently — partial-failure logs a warning per line so the remainder
 * still processes.
 *
 * <p>Expected payload shape: {@code {eventId, eventType, tenantId, orderId, storeId, items:
 * [{variantId, qty}]}}.
 */
@ApplicationScoped
class OrderEventHandler {

  private static final Logger LOG = System.getLogger(OrderEventHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/order-sync";

  @Inject InventoryService service;
  @Inject InventoryRepository repo;

  void handle(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      UUID eventId = UUID.fromString(obj.getString("eventId"));

      if (!repo.markProcessedIfNew(eventId, CONSUMER_NAME)) return;

      String eventType = obj.getString("eventType", "");
      UUID tenantId = UUID.fromString(obj.getString("tenantId"));
      UUID orderId = UUID.fromString(obj.getString("orderId"));
      UUID storeId = UUID.fromString(obj.getString("storeId"));
      JsonArray items = obj.getJsonArray("items");
      if (items == null || items.isEmpty()) return;

      if ("OrderFulfilled".equals(eventType)) {
        for (int i = 0; i < items.size(); i++) {
          JsonObject line = items.getJsonObject(i);
          UUID variantId = UUID.fromString(line.getString("variantId"));
          BigDecimal qty = new BigDecimal(line.get("qty").toString());
          try {
            service.deductSaleFromOrder(tenantId, storeId, variantId, qty, orderId);
          } catch (Exception e) {
            LOG.log(
                Level.WARNING, "deductSale failed for variant {0}: {1}", variantId, e.getMessage());
          }
        }
        LOG.log(Level.INFO, "OrderFulfilled {0}: deducted {1} line(s)", orderId, items.size());

      } else if ("OrderReturned".equals(eventType)) {
        for (int i = 0; i < items.size(); i++) {
          JsonObject line = items.getJsonObject(i);
          UUID variantId = UUID.fromString(line.getString("variantId"));
          BigDecimal qty = new BigDecimal(line.get("qty").toString());
          try {
            service.receiveReturnFromOrder(tenantId, storeId, variantId, qty, orderId);
          } catch (Exception e) {
            LOG.log(
                Level.WARNING,
                "receiveReturn failed for variant {0}: {1}",
                variantId,
                e.getMessage());
          }
        }
        LOG.log(Level.INFO, "OrderReturned {0}: restocked {1} line(s)", orderId, items.size());
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "OrderEvent handle error: " + e.getMessage());
    }
  }
}
