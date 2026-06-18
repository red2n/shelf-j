package com.shelfj.cart.messaging;

import com.shelfj.cart.repo.TenantStatusRepository;
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
 * Upserts the local tenant-status projection so {@link com.shelfj.cart.service.CartService} can
 * block cart operations for suspended/blocked tenants without a cross-service call.
 */
@ApplicationScoped
class TenantStatusChangedHandler {

  private static final Logger LOG = System.getLogger(TenantStatusChangedHandler.class.getName());

  @Inject TenantStatusRepository tenantStatus;

  void handle(String json) {
    UUID tenantId;
    String status;
    Instant occurredAt;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      tenantId = UUID.fromString(obj.getString("tenantId"));
      status = obj.getString("status").toUpperCase(java.util.Locale.ROOT);
      occurredAt = Instant.parse(obj.getString("occurredAt"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed TenantStatusChanged payload skipped: " + e.getMessage());
      return;
    }
    tenantStatus.upsertTenantStatus(tenantId, status, occurredAt);
    LOG.log(Level.INFO, "Tenant {0} projection updated to {1}", tenantId, status);
  }
}
