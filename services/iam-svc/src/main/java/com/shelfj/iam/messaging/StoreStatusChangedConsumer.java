package com.shelfj.iam.messaging;

import com.shelfj.iam.repo.PosSessionRepository;
import com.shelfj.service.BaseStoreStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
class StoreStatusChangedConsumer extends BaseStoreStatusChangedConsumer {

  private static final Logger LOG = System.getLogger(StoreStatusChangedConsumer.class.getName());

  @Inject PosSessionRepository posSessions;

  @Override
  protected String consumerName() {
    return "iam-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-store-status";
  }

  /**
   * Updates the shared {@code store_status} projection (via the inherited handler), then — for a
   * non-ACTIVE status — ends every active POS session at that store and revokes the affected
   * cashiers' refresh tokens. Without this, a store suspension/closure only blocked *new* POS
   * sessions; staff already clocked in could keep ringing up sales for the rest of their shift.
   */
  @Override
  protected void handle(String topic, String value) {
    super.handle(topic, value);
    try (var reader = Json.createReader(new StringReader(value))) {
      JsonObject obj = reader.readObject();
      String status = obj.getString("status", "ACTIVE").toUpperCase(Locale.ROOT);
      if (!"ACTIVE".equals(status)) {
        UUID storeId = UUID.fromString(obj.getString("storeId"));
        int ended = posSessions.endAllForStore(storeId);
        if (ended > 0) {
          LOG.log(
              Level.INFO,
              "Ended {0} active POS session(s) for suspended/closed store {1}",
              ended,
              storeId);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Could not evaluate POS session termination for StoreStatusChanged payload: {0}",
          e.getMessage());
    }
  }
}
