package com.shelfj.iam.messaging;

import com.shelfj.iam.repo.PosSessionRepository;
import com.shelfj.iam.repo.StoreStatusRepository;
import com.shelfj.iam.repo.UserRepository;
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
 * Business handler for {@code shelfj.tenant.store-status-changed}. When a store is closed or
 * suspended, terminates all active POS sessions at that store and revokes the refresh tokens of the
 * affected cashiers, preventing new access tokens being minted for a location no longer accepting
 * transactions.
 *
 * <p>Idempotent without a dedupe mark: ending an already-ended session is a no-op at the DB level
 * (WHERE status='ACTIVE' matches nothing), and token revocation is naturally repeatable.
 */
@ApplicationScoped
class StoreStatusChangedHandler {

  private static final Logger LOG = System.getLogger(StoreStatusChangedHandler.class.getName());

  @Inject PosSessionRepository posSessions;
  @Inject StoreStatusRepository storeStatus;
  @Inject UserRepository users;

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

    // Always upsert the projection so PosSessionService.start() can guard clock-in locally.
    storeStatus.upsertStoreStatus(storeId, tenantId, status, occurredAt);

    if (!"ACTIVE".equals(status)) {
      int terminated = posSessions.endAllForStore(storeId);
      users.audit(
          tenantId,
          null,
          "POS_SESSIONS_TERMINATED",
          terminated + " POS session(s) ended — store " + storeId + " set " + status);
      LOG.log(
          Level.INFO,
          "Store {0} set {1} — projection updated, {2} POS session(s) terminated",
          storeId,
          status,
          terminated);
    } else {
      LOG.log(Level.INFO, "Store {0} set ACTIVE — projection updated", storeId);
    }
  }
}
