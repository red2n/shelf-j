package com.shelfj.inventory.messaging;

import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Gap #50 — POS→SIM direction. Handles OrderFulfilled and OrderReturned events from order-svc.
 *
 * <ul>
 *   <li>OrderFulfilled → FIFO-deduct stock for each line item (SALE movement).
 *   <li>OrderReturned → receive stock back for each returned line item (RETURN movement).
 * </ul>
 *
 * <p>Each line is deduped on a deterministic per-line id INSIDE the line's transaction, so a
 * redelivered event skips lines that already committed and retries only the rest. A 4xx business
 * rejection (e.g. insufficient stock) skips just that line, as before; transient failures propagate
 * so the consumer loop redelivers the event.
 *
 * <p>Expected payload shape: {@code {eventId, eventType, tenantId, orderId, storeId, items:
 * [{variantId, qty}]}}.
 */
@ApplicationScoped
class OrderEventHandler {

  private static final Logger LOG = System.getLogger(OrderEventHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/order-sync";

  @Inject InventoryService service;

  void handle(String json) {
    UUID eventId;
    String eventType;
    UUID tenantId;
    UUID orderId;
    UUID storeId;
    JsonArray items;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      eventType = obj.getString("eventType", "");
      tenantId = UUID.fromString(obj.getString("tenantId"));
      orderId = UUID.fromString(obj.getString("orderId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      items = obj.getJsonArray("items");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }
    if (items == null || items.isEmpty()) {
      return;
    }

    boolean fulfil = "OrderFulfilled".equals(eventType);
    boolean returned = "OrderReturned".equals(eventType);
    if (!fulfil && !returned) {
      return;
    }

    for (int i = 0; i < items.size(); i++) {
      JsonObject line = items.getJsonObject(i);
      UUID variantId = UUID.fromString(line.getString("variantId"));
      BigDecimal qty = new BigDecimal(line.get("qty").toString());
      UUID dedupeId = lineDedupeId(eventId, i);
      try {
        if (fulfil) {
          service.deductSaleFromOrderOnce(
              dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
        } else {
          service.receiveReturnFromOrderOnce(
              dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
        }
      } catch (ApiException e) {
        if (e.status() >= 500) {
          throw e; // transient — let the consumer loop redeliver; completed lines are deduped
        }
        // business rejection (e.g. insufficient stock) — skip this line, as before
        LOG.log(
            Level.WARNING,
            "{0} line variant {1} skipped: {2}",
            eventType,
            variantId,
            e.getMessage());
      }
    }
    LOG.log(Level.INFO, "{0} {1}: processed {2} line(s)", eventType, orderId, items.size());
  }

  /** Deterministic per-line dedupe id: stable across redeliveries of the same event. */
  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return UUID.nameUUIDFromBytes(
        (CONSUMER_NAME + ":" + eventId + ":" + lineIndex).getBytes(StandardCharsets.UTF_8));
  }
}
