package com.shelfj.iam.messaging;

import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import com.shelfj.iam.repo.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/**
 * Business handler for {@code shelfj.tenant.tenant-created} events.
 * Stamps {@code tenant_id} + OWNER role on the registering user, idempotently.
 * Separated from {@link TenantCreatedConsumer} so Kafka lifecycle and domain
 * logic each have a single reason to change (SRP).
 */
@ApplicationScoped
class TenantCreatedHandler {

    private static final Logger LOG = System.getLogger(TenantCreatedHandler.class.getName());
    static final String CONSUMER_NAME = "iam-svc/tenant-created";

    @Inject UserRepository users;

    void handle(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            JsonObject obj = reader.readObject();
            UUID eventId     = UUID.fromString(obj.getString("eventId"));
            UUID tenantId    = UUID.fromString(obj.getString("tenantId"));
            UUID ownerUserId = UUID.fromString(obj.getString("ownerUserId"));

            if (!users.markProcessedIfNew(eventId, CONSUMER_NAME)) {
                return;
            }
            boolean changed = users.bindOwner(ownerUserId, tenantId, "OWNER");
            users.audit(tenantId, ownerUserId, "OWNER_BOUND", "via TenantCreated");
            LOG.log(Level.INFO, "Bound user {0} as OWNER of tenant {1} (changed={2})",
                    ownerUserId, tenantId, changed);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to handle TenantCreated: " + e.getMessage());
        }
    }
}
