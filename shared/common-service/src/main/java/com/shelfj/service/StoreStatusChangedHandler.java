package com.shelfj.service;

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
 * Upserts the local store-status projection on receipt of a {@code StoreStatusChanged} event.
 * Shared by every service that maintains a {@code store_status} table. Business logic lives here;
 * the per-service consumer class only contributes its consumer-name and group-id.
 */
@ApplicationScoped
public class StoreStatusChangedHandler {

  private static final Logger LOG = System.getLogger(StoreStatusChangedHandler.class.getName());

  @Inject StoreStatusRepository storeStatus;

  /**
   * Parses the event payload and upserts the local projection.
   *
   * @param json the raw Kafka record value; expected fields: {@code tenantId}, {@code storeId},
   *     {@code status}, {@code occurredAt} (ISO-8601 instant)
   * @implNote never throws — a malformed payload (missing field, bad UUID, unparseable instant) is
   *     logged at {@code WARNING} and the record is skipped (acked, not retried), since a
   *     redelivered malformed payload would fail identically forever
   */
  public void handle(String json) {
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
    boolean applied = storeStatus.upsertStoreStatus(storeId, tenantId, status, occurredAt);
    if (applied) {
      LOG.log(Level.INFO, "Store {0} projection updated to {1}", storeId, status);
    } else {
      LOG.log(
          Level.DEBUG,
          "Store {0} projection left unchanged — stale/out-of-order event ({1} at {2})",
          storeId,
          status,
          occurredAt);
    }
  }
}
