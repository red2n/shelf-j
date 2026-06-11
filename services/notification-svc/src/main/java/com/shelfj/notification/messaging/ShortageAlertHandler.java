package com.shelfj.notification.messaging;

import com.shelfj.notification.repo.NotificationRepository;
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
 * Handles StockBelowThreshold events published by inventory-svc. Deduplicates on eventId then
 * stores a ShortageAlert row. Separated from {@link ShortageAlertConsumer} (SRP).
 *
 * <p>Expected payload: {@code {eventId, tenantId, storeId, variantId, available, threshold}}.
 */
@ApplicationScoped
class ShortageAlertHandler {

  private static final Logger LOG = System.getLogger(ShortageAlertHandler.class.getName());
  static final String CONSUMER_NAME = "notification-svc/shortage-alert";

  @Inject NotificationService service;
  @Inject NotificationRepository repo;

  void handle(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      UUID eventId = UUID.fromString(obj.getString("eventId"));

      if (!repo.markProcessedIfNew(eventId, CONSUMER_NAME)) {
        LOG.log(Level.DEBUG, "StockBelowThreshold {0} already processed — skipped", eventId);
        return;
      }

      UUID tenantId = UUID.fromString(obj.getString("tenantId"));
      UUID storeId = UUID.fromString(obj.getString("storeId"));
      UUID variantId = UUID.fromString(obj.getString("variantId"));
      BigDecimal available = new BigDecimal(obj.get("available").toString());
      BigDecimal threshold = new BigDecimal(obj.get("threshold").toString());

      service.recordShortageAlert(tenantId, storeId, variantId, available, threshold, eventId);
      LOG.log(
          Level.WARNING,
          "SHORTAGE_ALERT tenant={0} store={1} variant={2} available={3} threshold={4}",
          tenantId,
          storeId,
          variantId,
          available,
          threshold);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to handle StockBelowThreshold: " + e.getMessage());
    }
  }
}
