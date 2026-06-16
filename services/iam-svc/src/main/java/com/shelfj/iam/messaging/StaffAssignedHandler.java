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
 *
 * <p>The dedupe mark and the bind commit in one transaction (see {@code bindStaffOnce}); a
 * malformed payload is logged and skipped, while a failed write propagates so the consumer loop
 * redelivers the record instead of losing it.
 */
@ApplicationScoped
class StaffAssignedHandler {

  private static final Logger LOG = System.getLogger(StaffAssignedHandler.class.getName());
  static final String CONSUMER_NAME = "iam-svc/staff-assigned";

  @Inject UserRepository users;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID userId;
    UUID storeId;
    String role;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      userId = UUID.fromString(obj.getString("userId"));
      storeId = UUID.fromString(obj.getString("storeId"));
      role = obj.getString("role");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed StaffAssigned payload skipped: " + e.getMessage());
      return;
    }

    boolean processed =
        users.bindStaffOnce(eventId, CONSUMER_NAME, userId, tenantId, role, storeId);
    if (processed) {
      LOG.log(
          Level.INFO,
          "Bound user {0} as {1} of tenant {2} store {3}",
          userId,
          role,
          tenantId,
          storeId);
    }
  }
}
