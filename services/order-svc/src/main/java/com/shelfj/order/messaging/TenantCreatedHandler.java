package com.shelfj.order.messaging;

import com.shelfj.service.TenantStatusRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Business handler for {@code shelfj.tenant.tenant-created} events. Projects the tenant's trading
 * currency onto this service's local {@code tenant_status} row so {@code OrderService} can stamp
 * money-bearing rows with the tenant's own currency instead of a hardcoded literal, without a
 * synchronous call to tenant-svc on the checkout path.
 *
 * <p>Separated from {@link TenantCreatedConsumer} so Kafka lifecycle and domain logic each have a
 * single reason to change (SRP). The dedupe mark and the projection write commit in one transaction
 * (see {@code projectTenantCurrencyOnce}); a malformed payload is logged and skipped, while a
 * failed write propagates so the consumer loop redelivers the record instead of losing it.
 */
@ApplicationScoped
class TenantCreatedHandler {

  private static final Logger LOG = System.getLogger(TenantCreatedHandler.class.getName());
  static final String CONSUMER_NAME = "order-svc/tenant-created";

  @Inject TenantStatusRepository tenantStatus;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    String currency;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      currency = obj.getString("currency", null);
    } catch (RuntimeException e) {
      // Malformed payload will never parse on redelivery either — log and skip.
      LOG.log(Level.WARNING, "Malformed TenantCreated payload skipped: " + e.getMessage());
      return;
    }

    // tenant-svc validates currency as a 3-character code before it ever reaches the outbox, so
    // anything else here means a producer contract change. Skip rather than project a value that
    // would then be stamped onto orders — the configured default is the safer fallback.
    if (currency == null || currency.trim().length() != 3) {
      LOG.log(
          Level.WARNING,
          "TenantCreated for tenant {0} carried no usable currency ({1}) — not projected",
          tenantId,
          currency);
      return;
    }

    boolean projected =
        tenantStatus.projectTenantCurrencyOnce(eventId, CONSUMER_NAME, tenantId, currency.trim());
    if (projected) {
      LOG.log(Level.INFO, "Projected currency {0} for tenant {1}", currency, tenantId);
    }
  }
}
