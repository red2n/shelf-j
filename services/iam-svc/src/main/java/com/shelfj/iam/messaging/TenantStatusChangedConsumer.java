package com.shelfj.iam.messaging;

import com.shelfj.iam.repo.PosSessionRepository;
import com.shelfj.service.BaseTenantStatusChangedConsumer;
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
class TenantStatusChangedConsumer extends BaseTenantStatusChangedConsumer {

  private static final Logger LOG = System.getLogger(TenantStatusChangedConsumer.class.getName());

  @Inject PosSessionRepository posSessions;

  @Override
  protected String consumerName() {
    return "iam-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-tenant-status";
  }

  /**
   * Updates the shared {@code tenant_status} projection (via the inherited handler), then — for a
   * non-ACTIVE status — ends every active POS session tenant-wide and revokes the affected
   * cashiers' refresh tokens. Login/refresh are already blocked by {@link
   * com.shelfj.iam.service.AuthService} once suspended; this closes the gap for staff who were
   * already clocked into a POS session when the suspension happened.
   */
  @Override
  protected void handle(String topic, String value) {
    super.handle(topic, value);
    try (var reader = Json.createReader(new StringReader(value))) {
      JsonObject obj = reader.readObject();
      String status = obj.getString("status", "ACTIVE").toUpperCase(Locale.ROOT);
      if (!"ACTIVE".equals(status)) {
        UUID tenantId = UUID.fromString(obj.getString("tenantId"));
        int ended = posSessions.endAllForTenant(tenantId);
        if (ended > 0) {
          LOG.log(
              Level.INFO,
              "Ended {0} active POS session(s) for suspended/blocked tenant {1}",
              ended,
              tenantId);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Could not evaluate POS session termination for TenantStatusChanged payload: {0}",
          e.getMessage());
    }
  }
}
