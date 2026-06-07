package com.shelfj.tenant.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.shelfj.tenant.domain.Domain.StaffAssignment;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.Zone;
import com.shelfj.tenant.dto.Dtos.AssignStaffRequest;
import com.shelfj.tenant.dto.Dtos.CreateStoreRequest;
import com.shelfj.tenant.dto.Dtos.CreateTenantRequest;
import com.shelfj.tenant.dto.Dtos.CreateZoneRequest;
import com.shelfj.tenant.dto.Dtos.OnboardingStatus;
import com.shelfj.tenant.repo.TenantRepository;
import com.shelfj.tenant.repo.TenantRepository.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Onboarding + location logic (Tenant → Stores → Zones). The brain of tenant-svc.
 *
 * <p>Each create writes its rows AND its event to the outbox in one transaction (golden rule #6). A new tenant is
 * created ACTIVE and bound to the authenticated owner; the first store auto-gets a DEFAULT zone so stock always has
 * a home. Topic prefix: {@code shelfj.tenant.*}.</p>
 */
@ApplicationScoped
public class TenantService {

    @Inject
    TenantRepository repo;

    // --- onboarding ---

    /** Create the business and bind the authenticated owner. Publishes TenantCreated (carries ownerUserId). */
    public Tenant createTenant(UUID ownerUserId, CreateTenantRequest req) {
        UUID tenantId = UUID.randomUUID();
        var tenant = new Tenant(tenantId, req.businessName(), req.legalName(), Tenant.STATUS_ACTIVE,
                null, ownerUserId, req.country().toUpperCase(), req.currency().toUpperCase(), Instant.now());
        var event = new OutboxRow("TenantCreated", "shelfj.tenant.tenant-created", tenantId, tenantId,
                Events.tenantCreated(tenantId, ownerUserId, req.businessName(), tenant.country(), tenant.currency()));
        return repo.createTenantWithOutbox(tenant, event);
    }

    /** Create the first/default store + its DEFAULT zone. Publishes StoreCreated + ZoneCreated. */
    public TenantRepository.StoreWithZone createDefaultStore(UUID tenantId, CreateStoreRequest req) {
        boolean isDefault = !repo.hasDefaultStore(tenantId);
        return createStoreInternal(tenantId, req, isDefault);
    }

    /** Add a store (not necessarily default). */
    public TenantRepository.StoreWithZone addStore(UUID tenantId, CreateStoreRequest req) {
        return createStoreInternal(tenantId, req, false);
    }

    private TenantRepository.StoreWithZone createStoreInternal(UUID tenantId, CreateStoreRequest req, boolean isDefault) {
        UUID storeId = UUID.randomUUID();
        String type = req.type() == null || req.type().isBlank() ? Store.TYPE_STORE : req.type();
        var store = new Store(storeId, tenantId, req.name(), req.code(), type,
                req.line1(), req.line2(), req.city(), req.state(), req.country(), req.pincode(),
                req.geoLat(), req.geoLng(), req.timezone() == null ? "UTC" : req.timezone(),
                req.businessHours(), "ACTIVE", isDefault, Instant.now());

        // Always create a DEFAULT zone so stock has a home (golden rule of the location model).
        UUID zoneId = UUID.randomUUID();
        var defaultZone = new Zone(zoneId, tenantId, storeId, "Default", "DEFAULT", Zone.TYPE_DEFAULT, "ACTIVE", Instant.now());

        var storeEvent = new OutboxRow("StoreCreated", "shelfj.tenant.store-created", tenantId, storeId,
                Events.storeCreated(tenantId, storeId, req.code(), type, isDefault));
        var zoneEvent = new OutboxRow("ZoneCreated", "shelfj.tenant.zone-created", tenantId, zoneId,
                Events.zoneCreated(tenantId, storeId, zoneId, "DEFAULT", Zone.TYPE_DEFAULT));

        return repo.createStoreWithDefaultZone(store, defaultZone, storeEvent, zoneEvent);
    }

    /** Add a zone to a store. Publishes ZoneCreated. */
    public Zone addZone(UUID tenantId, UUID storeId, CreateZoneRequest req) {
        repo.findStore(tenantId, storeId)
                .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
        UUID zoneId = UUID.randomUUID();
        String type = req.type() == null || req.type().isBlank() ? "AISLE" : req.type();
        var zone = new Zone(zoneId, tenantId, storeId, req.name(), req.code(), type, "ACTIVE", Instant.now());
        var event = new OutboxRow("ZoneCreated", "shelfj.tenant.zone-created", tenantId, zoneId,
                Events.zoneCreated(tenantId, storeId, zoneId, req.code(), type));
        return repo.createZoneWithOutbox(zone, event);
    }

    /** Assign a staff user to a store with a role. Publishes StaffAssigned. */
    public void assignStaff(UUID tenantId, AssignStaffRequest req) {
        UUID userId = parseUuid(req.userId(), "userId");
        UUID storeId = parseUuid(req.storeId(), "storeId");
        repo.findStore(tenantId, storeId)
                .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
        var assignment = new StaffAssignment(UUID.randomUUID(), tenantId, userId, storeId, req.role(), Instant.now());
        var event = new OutboxRow("StaffAssigned", "shelfj.tenant.staff-assigned", tenantId, userId,
                Events.staffAssigned(tenantId, userId, storeId, req.role()));
        repo.createStaffWithOutbox(assignment, event);
    }

    // --- reads ---

    public Tenant getTenant(UUID tenantId) {
        return repo.findTenant(tenantId)
                .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
    }

    public List<Store> listStores(UUID tenantId) { return repo.listStores(tenantId); }

    public List<Zone> listZones(UUID tenantId, UUID storeId) {
        repo.findStore(tenantId, storeId)
                .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
        return repo.listZones(tenantId, storeId);
    }

    public OnboardingStatus onboardingStatus(UUID tenantId) {
        Tenant t = getTenant(tenantId);
        boolean active = Tenant.STATUS_ACTIVE.equals(t.status());
        boolean hasStore = repo.hasDefaultStore(tenantId);
        List<String> next = new ArrayList<>();
        if (!hasStore) next.add("Create your first store (POST /onboarding/stores)");
        if (hasStore) next.add("Add products, map zones, invite staff");
        return new OnboardingStatus(active, hasStore, next);
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_UUID", field + " must be a UUID");
        }
    }
}
