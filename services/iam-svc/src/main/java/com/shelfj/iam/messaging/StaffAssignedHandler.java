package com.shelfj.iam.messaging;

import com.shelfj.iam.repo.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Business handler for {@code shelfj.tenant.staff-assigned} events. Stamps {@code tenant_id} + the
 * assigned store-scoped role on the staff user, idempotently. Without this, staff assigned via
 * tenant-svc never get a tenant on their iam-svc row and their JWTs carry {@code tenant=null}.
 * Separated from {@link StaffAssignedConsumer} so Kafka lifecycle and domain logic each have a
 * single reason to change (SRP).
 */
@ApplicationScoped
class StaffAssignedHandler {

  private static final Logger LOG = System.getLogger(StaffAssignedHandler.class.getName());
  static final String CONSUMER_NAME = "iam-svc/staff-assigned";

  @Inject UserRepository users;

  void handle(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      UUID eventId = UUID.fromString(obj.getString("eventId"));
      UUID tenantId = UUID.fromString(obj.getString("tenantId"));
      UUID userId = UUID.fromString(obj.getString("userId"));
      UUID storeId = UUID.fromString(obj.getString("storeId"));
      String role = obj.getString("role");

      if (!users.markProcessedIfNew(eventId, CONSUMER_NAME)) {
        return;
      }
      boolean changed = users.bindStaff(userId, tenantId, role, storeId);
      users.audit(tenantId, userId, "STAFF_BOUND", role + " @ store " + storeId);
      LOG.log(
          Level.INFO,
          "Bound user {0} as {1} of tenant {2} store {3} (changed={4})",
          userId,
          role,
          tenantId,
          storeId,
          changed);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to handle StaffAssigned: " + e.getMessage());
    }
  }
}
