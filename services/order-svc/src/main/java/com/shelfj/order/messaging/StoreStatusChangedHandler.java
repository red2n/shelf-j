package com.shelfj.order.messaging;

import com.shelfj.order.repo.StoreStatusRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.UUID;

/**
 * Business handler for {@code shelfj.tenant.store-status-changed}. Upserts the local store-status
 * projection so {@link com.shelfj.order.service.OrderService} can block order placement for stores
 * that are closed or suspended without a cross-service call at request time.
 *
 * <p>Idempotent: the upsert is guarded by {@code status_changed_at}.
 */
@ApplicationScoped
class StoreStatusChangedHandler {

  private static final Logger LOG = System.getLogger(StoreStatusChangedHandler.class.getName());

  @Inject StoreStatusRepository storeStatus;

  void handle(String json) {
    UUID tenantId;
    UUID storeId;
    String status;
    Instant occurredAt;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      tenantId = UUID.fromString(obj.getString("tenantId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      status = obj.getString("status").toUpperCase(java.util.Locale.ROOT);
      occurredAt = Instant.parse(obj.getString("occurredAt"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StoreStatusChanged payload skipped: " + e.getMessage());
      return;
    }

    storeStatus.upsertStoreStatus(storeId, tenantId, status, occurredAt);
    LOG.log(Level.INFO, "Store {0} projection updated to {1}", storeId, status);
  }
}
