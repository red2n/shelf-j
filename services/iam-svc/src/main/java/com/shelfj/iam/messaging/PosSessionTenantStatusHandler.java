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
 * Business handler for {@code shelfj.tenant.tenant-status-changed}: ends every active POS session
 * tenant-wide once a tenant goes non-ACTIVE and revokes the affected cashiers' refresh tokens.
 * Separated from {@link TenantStatusChangedConsumer} so Kafka lifecycle and domain logic each have
 * a single reason to change (SRP) — the shared {@code tenant_status} projection update is handled
 * separately by the inherited {@code BaseTenantStatusChangedConsumer}/{@code
 * TenantStatusChangedHandler}.
 *
 * <p>Login/refresh are already blocked by {@link com.shelfj.iam.service.AuthService} once
 * suspended; this closes the gap for staff who were already clocked into a POS session when the
 * suspension happened. A malformed payload is logged and skipped rather than propagated, since it
 * will never parse on redelivery either.
 */
@ApplicationScoped
class PosSessionTenantStatusHandler {

  private static final Logger LOG = System.getLogger(PosSessionTenantStatusHandler.class.getName());

  @Inject PosSessionRepository posSessions;

  void handle(String json) {
    UUID tenantId;
    String status;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      tenantId = UUID.fromString(obj.getString("tenantId"));
      status = obj.getString("status", "ACTIVE").toUpperCase(Locale.ROOT);
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Malformed TenantStatusChanged payload skipped (POS session handler): " + e.getMessage());
      return;
    }
    if ("ACTIVE".equals(status)) {
      return;
    }
    int ended = posSessions.endAllForTenant(tenantId);
    if (ended > 0) {
      LOG.log(
          Level.INFO,
          "Ended {0} active POS session(s) for suspended/blocked tenant {1}",
          ended,
          tenantId);
    }
  }
}
