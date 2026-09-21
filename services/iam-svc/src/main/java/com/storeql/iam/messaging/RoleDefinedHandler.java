package com.storeql.iam.messaging;

import com.storeql.iam.repo.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a redefined custom role (20.10) to every assignment made through it, so the next login
 * carries the new permissions. Idempotent on the event id; a malformed payload is logged and
 * skipped.
 */
@ApplicationScoped
public class RoleDefinedHandler {

  private static final Logger LOG = System.getLogger(RoleDefinedHandler.class.getName());
  static final String CONSUMER_NAME = "iam-svc/role-defined";

  @Inject UserRepository users;

  /**
   * @param json the {@code RoleDefined} payload
   */
  public void handle(String json) {
    UUID eventId;
    UUID tenantId;
    String code;
    Set<String> permissions;
    java.time.Instant updatedAt;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!"RoleDefined".equals(obj.getString("eventType", ""))) return;
      eventId = UUID.fromString(obj.getString("eventId"));
      tenantId = UUID.fromString(obj.getString("tenantId"));
      code = obj.getString("code");
      permissions = Permissions.parse(obj);
      updatedAt = Permissions.instant(obj, "updatedAt");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RoleDefined payload skipped: " + e.getMessage());
      return;
    }
    if (users.applyRolePermissionsOnce(
        eventId, CONSUMER_NAME, tenantId, code, permissions, updatedAt)) {
      LOG.log(Level.INFO, "Role {0} of tenant {1} now holds {2}", code, tenantId, permissions);
    }
  }
}
