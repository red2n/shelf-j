package com.shelfj.iam.messaging;

import com.shelfj.iam.repo.RefreshTokenRepository;
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
 * Business handler for {@code shelfj.tenant.tenant-status-changed}. Maintains iam-svc's local
 * tenant-status projection so {@code login}/{@code refresh} can reject a deactivated tenant's
 * staff. When a tenant goes INACTIVE it also revokes that tenant's refresh tokens, so
 * already-issued sessions can't mint new access tokens (existing short-lived access tokens lapse on
 * their own TTL).
 *
 * <p>Idempotent without a dedupe mark: the upsert is timestamp-guarded (a stale/out-of-order event
 * can't overwrite a newer status) and the token revoke is naturally repeatable.
 */
@ApplicationScoped
class TenantStatusChangedHandler {

  private static final Logger LOG = System.getLogger(TenantStatusChangedHandler.class.getName());

  @Inject UserRepository users;
  @Inject RefreshTokenRepository refreshTokens;

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

    users.upsertTenantStatus(tenantId, status, occurredAt);
    if (!"ACTIVE".equals(status)) {
      refreshTokens.revokeAllForTenant(tenantId);
      LOG.log(Level.INFO, "Tenant {0} set {1} — sessions revoked", tenantId, status);
    } else {
      LOG.log(Level.INFO, "Tenant {0} set ACTIVE", tenantId);
    }
  }
}
