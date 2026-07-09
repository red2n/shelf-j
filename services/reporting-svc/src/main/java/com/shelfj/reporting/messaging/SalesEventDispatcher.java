package com.shelfj.reporting.messaging;

import com.shelfj.reporting.service.ReportingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Routes order/payment events to the sales projection (N4). {@code OrderConfirmed} records a sale
 * (idempotent on the order PK); {@code PaymentRefunded} accumulates a refund against that sale
 * (deduped on eventId). One dispatcher per domain keeps each concern a single private method (SRP).
 *
 * <p>Malformed payloads are skipped (they never parse on redelivery); write failures propagate so
 * the consumer loop redelivers instead of losing the event.
 */
@ApplicationScoped
class SalesEventDispatcher {

  private static final Logger LOG = System.getLogger(SalesEventDispatcher.class.getName());
  private static final String CONSUMER = "reporting-svc/sales-events";

  @Inject ReportingService service;

  void dispatch(String topic, String json) {
    JsonObject obj;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed sales event on {0} skipped: {1}", topic, e.getMessage());
      return;
    }

    try {
      switch (topic) {
        case "shelfj.order.order-confirmed" -> handleOrderConfirmed(obj);
        case "shelfj.payment.payment-refunded" -> handleRefunded(obj);
        default -> LOG.log(Level.WARNING, "Unknown topic {0} — ignored", topic);
      }
    } catch (RuntimeException e) {
      if (isMalformed(e)) {
        LOG.log(Level.WARNING, "Malformed sales event on {0} skipped: {1}", topic, e.getMessage());
        return;
      }
      throw e;
    }
  }

  private void handleOrderConfirmed(JsonObject obj) {
    UUID tenantId = UUID.fromString(obj.getString("tenantId"));
    UUID orderId = UUID.fromString(obj.getString("orderId"));
    UUID storeId = optUuid(obj, "storeId");
    String channel = obj.getString("channel", null);
    UUID customerId = optUuid(obj, "customerId");
    BigDecimal gross = obj.getJsonNumber("total").bigDecimalValue();
    String currency = obj.getString("currency", "GBP");
    service.recordSale(tenantId, orderId, storeId, channel, customerId, gross, currency);
  }

  private void handleRefunded(JsonObject obj) {
    UUID eventId = UUID.fromString(obj.getString("eventId"));
    UUID tenantId = UUID.fromString(obj.getString("tenantId"));
    UUID orderId = UUID.fromString(obj.getString("orderId"));
    BigDecimal amount = obj.getJsonNumber("amount").bigDecimalValue();
    service.applySalesRefund(eventId, CONSUMER, tenantId, orderId, amount);
  }

  private static UUID optUuid(JsonObject obj, String key) {
    return obj.containsKey(key) && !obj.isNull(key) ? UUID.fromString(obj.getString(key)) : null;
  }

  private static boolean isMalformed(RuntimeException e) {
    return e instanceof NullPointerException
        || e instanceof IllegalArgumentException
        || e instanceof ClassCastException
        || e instanceof jakarta.json.JsonException;
  }
}
