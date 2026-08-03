package com.shelfj.iam.messaging;

import com.shelfj.iam.repo.PosSessionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.UUID;

/**
 * Business handler for {@code shelfj.tenant.store-status-changed}: ends every active POS session at
 * a store that just went non-ACTIVE and revokes the affected cashiers' refresh tokens. Separated
 * from {@link StoreStatusChangedConsumer} so Kafka lifecycle and domain logic each have a single
 * reason to change (SRP) — the shared {@code store_status} projection update is handled separately
 * by the inherited {@code BaseStoreStatusChangedConsumer}/{@code StoreStatusChangedHandler}.
 *
 * <p>A malformed payload is logged and skipped rather than propagated, since it will never parse on
 * redelivery either.
 */
@ApplicationScoped
class PosSessionStoreStatusHandler {

  private static final Logger LOG = System.getLogger(PosSessionStoreStatusHandler.class.getName());

  @Inject PosSessionRepository posSessions;

  void handle(String json) {
    UUID storeId;
    String status;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      storeId = UUID.fromString(obj.getString("storeId"));
      status = obj.getString("status", "ACTIVE").toUpperCase(Locale.ROOT);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Malformed StoreStatusChanged payload skipped (POS session handler): " + e.getMessage());
      return;
    }
    if ("ACTIVE".equals(status)) {
      return;
    }
    int ended = posSessions.endAllForStore(storeId);
    if (ended > 0) {
      LOG.log(
          Level.INFO,
          "Ended {0} active POS session(s) for suspended/closed store {1}",
          ended,
          storeId);
    }
  }
}
