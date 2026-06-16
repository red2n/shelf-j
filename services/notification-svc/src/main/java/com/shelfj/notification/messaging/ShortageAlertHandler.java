package com.shelfj.notification.messaging;

import com.shelfj.notification.service.NotificationService;
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
 * Handles StockBelowThreshold events published by inventory-svc. The eventId dedupe and the
 * ShortageAlert insert commit in one transaction (see {@code insertAlertOnce}). Separated from
 * {@link ShortageAlertConsumer} (SRP). Malformed payloads are skipped; write failures propagate so
 * the consumer loop redelivers instead of losing the event.
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, variantId, available, threshold}}.
 */
@ApplicationScoped
class ShortageAlertHandler {

  private static final Logger LOG = System.getLogger(ShortageAlertHandler.class.getName());
  static final String CONSUMER_NAME = "notification-svc/shortage-alert";

  @Inject NotificationService service;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID storeId;
    UUID variantId;
    BigDecimal available;
    BigDecimal threshold;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      variantId = UUID.fromString(obj.getString("variantId"));
      available = new BigDecimal(obj.get("available").toString());
      threshold = new BigDecimal(obj.get("threshold").toString());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StockBelowThreshold payload skipped: " + e.getMessage());
      return;
    }

    boolean recorded =
        service.recordShortageAlertOnce(
            CONSUMER_NAME, tenantId, storeId, variantId, available, threshold, eventId);
    if (recorded) {
      LOG.log(
          Level.WARNING,
          "SHORTAGE_ALERT tenant={0} store={1} variant={2} available={3} threshold={4}",
          tenantId,
          storeId,
          variantId,
          available,
          threshold);
    }
  }
}
