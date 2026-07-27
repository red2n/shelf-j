package com.shelfj.tenant.service;

import com.shelfj.service.OutboxRow;
import com.shelfj.tenant.domain.Domain.DeliveryArea;
import com.shelfj.tenant.domain.Domain.StaffAssignment;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.StoreWithZone;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.TenantInventoryConfig;
import com.shelfj.tenant.domain.Domain.TenantWithStore;
import com.shelfj.tenant.domain.Domain.Zone;
import com.shelfj.tenant.dto.Dtos.AssignStaffRequest;
import com.shelfj.tenant.dto.Dtos.CreateDeliveryAreaRequest;
import com.shelfj.tenant.dto.Dtos.CreateStoreRequest;
import com.shelfj.tenant.dto.Dtos.CreateTenantRequest;
import com.shelfj.tenant.dto.Dtos.CreateZoneRequest;
import com.shelfj.tenant.dto.Dtos.DeliveryAreaResponse;
import com.shelfj.tenant.dto.Dtos.FulfilmentResolveResponse;
import com.shelfj.tenant.dto.Dtos.OnboardRequest;
import com.shelfj.tenant.dto.Dtos.OnboardingStatus;
import com.shelfj.tenant.dto.Dtos.PatchStatusRequest;
import com.shelfj.tenant.dto.Dtos.TenantInventoryConfigResponse;
import com.shelfj.tenant.dto.Dtos.UpdateStoreRequest;
import com.shelfj.tenant.dto.Dtos.UpdateTenantRequest;
import com.shelfj.tenant.dto.Dtos.UpdateZoneRequest;
import com.shelfj.tenant.dto.Dtos.UpsertInventoryConfigRequest;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.repo.TenantRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Onboarding + location logic (Tenant → Stores → Zones). The brain of tenant-svc.
 *
 * <p>Each create writes its rows AND its event to the outbox in one transaction (golden rule #6). A
 * new tenant is created ACTIVE and bound to the authenticated owner; the first store auto-gets a
 * DEFAULT zone so stock always has a home. Topic prefix: {@code shelfj.tenant.*}.
 */
@ApplicationScoped
public class TenantService {

  @Inject TenantRepository repo;

  // --- onboarding ---

  /**
   * Create the business and bind the authenticated owner. Publishes TenantCreated (carries
   * ownerUserId). Also publishes UserRoleGranted to ensure the creator has OWNER role (flow guard:
   * user has no tenant claim in JWT yet, so they need role update before they can access admin
   * endpoints).
   */
  public Tenant createTenant(UUID ownerUserId, CreateTenantRequest req) {
    UUID tenantId = UUID.randomUUID();
    Instant nowTenant = Instant.now();
    var tenant =
        new Tenant(
            tenantId,
            req.businessName(),
            req.legalName(),
            Tenant.STATUS_ACTIVE,
            null,
            ownerUserId,
            req.country().toUpperCase(Locale.ROOT),
            req.currency().toUpperCase(Locale.ROOT),
            nowTenant,
            nowTenant);
    var event =
        new OutboxRow(
            "TenantCreated",
            "shelfj.tenant.tenant-created",
            tenantId,
            tenantId,
            Events.tenantCreated(
                tenantId, ownerUserId, req.businessName(), tenant.country(), tenant.currency()));
    var createdTenant = repo.createTenantWithOutbox(tenant, event);

    // Flow guard: grant OWNER role to tenant creator so they can access admin endpoints
    // before their JWT is refreshed with the new tenant claim
    var roleEvent =
        new OutboxRow(
            "UserRoleGranted",
            "shelfj.iam.user-role-granted",
            tenantId,
            ownerUserId,
            Events.userRoleGranted(tenantId, ownerUserId, "OWNER"));
    repo.publishEvent(roleEvent);

    return createdTenant;
  }

  /**
   * Combined onboarding: create tenant + first store in one shot. The tenantId is generated here so
   * the store call never needs it from the JWT — avoids the Kafka async race entirely.
   */
  public TenantWithStore onboard(UUID ownerUserId, OnboardRequest req) {
    // 1. create tenant (generates tenantId internally)
    CreateTenantRequest tenantReq =
        new CreateTenantRequest(req.businessName(), req.legalName(), req.country(), req.currency());
    Tenant tenant = createTenant(ownerUserId, tenantReq);

    // 2. create the first store using the freshly generated tenantId — no JWT needed
    CreateStoreRequest storeReq =
        new CreateStoreRequest(
            req.storeName(),
            req.storeCode(),
            req.storeType() == null ? Store.TYPE_STORE : req.storeType(),
            req.storeLine1(),
            null,
            req.storeCity(),
            null,
            req.storeCountry(),
            req.storePincode(),
            null,
            null,
            req.storeTimezone() == null ? "UTC" : req.storeTimezone(),
            null,
            null,
            null);
    StoreWithZone storeWithZone = createDefaultStore(tenant.id(), ownerUserId, storeReq);
    return new TenantWithStore(tenant, storeWithZone.store());
  }

  /**
   * Create the first/default store + its DEFAULT zone. Publishes StoreCreated + ZoneCreated.
   *
   * <p>{@code callerUserId} must be the tenant's owner. This endpoint is reachable with a
   * caller-supplied {@code tenantId} (the gateway's onboarding carve-out: a JWT with no tenant
   * claim yet still needs a way to name the tenant it just created — see
   * JwtAuthFilter#isOnboarding), so tenantId alone is not proof the caller is entitled to act on
   * that tenant.
   */
  public StoreWithZone createDefaultStore(
      UUID tenantId, UUID callerUserId, CreateStoreRequest req) {
    requireOwner(getTenant(tenantId), callerUserId);
    boolean isDefault = !repo.hasDefaultStore(tenantId);
    return createStoreInternal(tenantId, req, isDefault);
  }

  /** Add a store (not necessarily default). */
  public StoreWithZone addStore(UUID tenantId, CreateStoreRequest req) {
    return createStoreInternal(tenantId, req, false);
  }

  private StoreWithZone createStoreInternal(
      UUID tenantId, CreateStoreRequest req, boolean isDefault) {
    UUID storeId = UUID.randomUUID();
    String type = req.type() == null || req.type().isBlank() ? Store.TYPE_STORE : req.type();
    Instant nowStore = Instant.now();
    var store =
        new Store(
            storeId,
            tenantId,
            req.name(),
            req.code(),
            type,
            req.line1(),
            req.line2(),
            req.city(),
            req.state(),
            req.country(),
            req.pincode(),
            req.geoLat(),
            req.geoLng(),
            req.timezone() == null ? "UTC" : req.timezone(),
            req.businessHours(),
            "ACTIVE",
            isDefault,
            req.showPrices() == null || req.showPrices(),
            normalizePaymentMethods(req.enabledPaymentMethods(), Store.DEFAULT_PAYMENT_METHODS),
            nowStore,
            nowStore);

    // Always create a DEFAULT zone so stock has a home (golden rule of the location model).
    UUID zoneId = UUID.randomUUID();
    var defaultZone =
        new Zone(
            zoneId,
            tenantId,
            storeId,
            "Default",
            "DEFAULT",
            Zone.TYPE_DEFAULT,
            "ACTIVE",
            nowStore,
            nowStore);

    var storeEvent =
        new OutboxRow(
            "StoreCreated",
            "shelfj.tenant.store-created",
            tenantId,
            storeId,
            Events.storeCreated(tenantId, storeId, req.code(), type, isDefault));
    var zoneEvent =
        new OutboxRow(
            "ZoneCreated",
            "shelfj.tenant.zone-created",
            tenantId,
            zoneId,
            Events.zoneCreated(tenantId, storeId, zoneId, "DEFAULT", Zone.TYPE_DEFAULT));

    return repo.createStoreWithDefaultZone(store, defaultZone, storeEvent, zoneEvent);
  }

  /** Add a zone to a store. Publishes ZoneCreated. */
  public Zone addZone(UUID tenantId, UUID storeId, CreateZoneRequest req) {
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
    UUID zoneId = UUID.randomUUID();
    String type = req.type() == null || req.type().isBlank() ? "AISLE" : req.type();
    Instant nowZone = Instant.now();
    var zone =
        new Zone(
            zoneId, tenantId, storeId, req.name(), req.code(), type, "ACTIVE", nowZone, nowZone);
    var event =
        new OutboxRow(
            "ZoneCreated",
            "shelfj.tenant.zone-created",
            tenantId,
            zoneId,
            Events.zoneCreated(tenantId, storeId, zoneId, req.code(), type));
    return repo.createZoneWithOutbox(zone, event);
  }

  /** Assign a staff user to a store with a role. Publishes StaffAssigned. */
  public void assignStaff(UUID tenantId, AssignStaffRequest req) {
    UUID userId = parseUuid(req.userId(), "userId");
    UUID storeId = parseUuid(req.storeId(), "storeId");
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
    var assignment =
        new StaffAssignment(
            UUID.randomUUID(), tenantId, userId, storeId, req.role(), Instant.now());
    var event =
        new OutboxRow(
            "StaffAssigned",
            "shelfj.tenant.staff-assigned",
            tenantId,
            userId,
            Events.staffAssigned(tenantId, userId, storeId, req.role()));
    repo.createStaffWithOutbox(assignment, event);
  }

  // --- reads ---

  /** Cursor-paginated platform-wide tenant list (platform-admin). */
  public Cursor.Page<Tenant> listAllTenants(String after, int limit) {
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<Tenant> rows =
        repo.listAllTenants(
            key == null ? null : key.createdAt(), key == null ? null : key.id(), limit + 1);
    return Cursor.page(rows, limit, t -> t.createdAt() + "|" + t.id());
  }

  public Tenant getTenant(UUID tenantId) {
    return repo.findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  public List<Store> listStores(UUID tenantId) {
    return repo.listStores(tenantId);
  }

  /** Cursor-paginated stores (admin list). The cursor wraps the last row's created_at|id keyset. */
  public Cursor.Page<Store> listStores(UUID tenantId, String after, int limit) {
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<Store> rows =
        repo.listStores(
            tenantId,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, s -> s.createdAt() + "|" + s.id());
  }

  /** Cursor-paginated zones of one store (admin list). */
  public Cursor.Page<Zone> listZones(UUID tenantId, UUID storeId, String after, int limit) {
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<Zone> rows =
        repo.listZones(
            tenantId,
            storeId,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, z -> z.createdAt() + "|" + z.id());
  }

  /**
   * {@code callerUserId} must be the tenant's owner — see {@link #createDefaultStore} for why this
   * can't rely on tenantId alone.
   */
  public OnboardingStatus onboardingStatus(UUID tenantId, UUID callerUserId) {
    Tenant t = getTenant(tenantId);
    requireOwner(t, callerUserId);
    boolean active = Tenant.STATUS_ACTIVE.equals(t.status());
    boolean hasStore = repo.hasDefaultStore(tenantId);
    List<String> next = new ArrayList<>();
    if (!hasStore) next.add("Create your first store (POST /onboarding/stores)");
    if (hasStore) next.add("Add products, map zones, invite staff");
    return new OnboardingStatus(active, hasStore, next);
  }

  /**
   * Guards the two onboarding endpoints the gateway will forward a caller-supplied tenantId for
   * (see {@link #createDefaultStore}). Every other endpoint gets tenantId from a verified JWT
   * claim, where this check would be redundant; here it's the only thing standing between "any
   * authenticated user" and "this specific tenant's owner."
   */
  private static void requireOwner(Tenant tenant, UUID callerUserId) {
    if (!tenant.ownerUserId().equals(callerUserId)) {
      throw ApiException.forbidden(
          "TENANT_ACCESS_DENIED", "Caller is not the owner of this tenant");
    }
  }

  public Tenant patchTenantStatus(UUID tenantId, PatchStatusRequest req) {
    getTenant(tenantId);
    String status = req.status().toUpperCase(Locale.ROOT);
    if (!Tenant.STATUS_ACTIVE.equals(status) && !Tenant.STATUS_INACTIVE.equals(status)) {
      throw ApiException.badRequest("INVALID_STATUS", "status must be ACTIVE or INACTIVE");
    }
    // Publish the change so iam-svc (and anyone else) can react — deactivating a tenant must lock
    // its staff out, not just flip a row no other service can see.
    var event =
        new OutboxRow(
            "TenantStatusChanged",
            "shelfj.tenant.tenant-status-changed",
            tenantId,
            tenantId,
            Events.tenantStatusChanged(tenantId, status));
    return repo.updateTenantStatusWithOutbox(tenantId, status, event);
  }

  public Tenant updateTenant(UUID tenantId, UpdateTenantRequest req) {
    getTenant(tenantId);
    return repo.updateTenant(
        tenantId,
        req.businessName().trim(),
        req.legalName() == null ? null : req.legalName().trim());
  }

  public Store getStore(UUID tenantId, UUID storeId) {
    return repo.findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "No such store"));
  }

  public Store updateStore(UUID tenantId, UUID storeId, UpdateStoreRequest req) {
    Store existing = getStore(tenantId, storeId);
    return repo.updateStore(
        tenantId,
        storeId,
        req.name(),
        req.line1(),
        req.line2(),
        req.city(),
        req.state(),
        req.country(),
        req.pincode(),
        req.geoLat(),
        req.geoLng(),
        req.timezone() == null ? "UTC" : req.timezone(),
        req.businessHours(),
        // keep current value when the client omits the flag
        req.showPrices() == null ? existing.showPrices() : req.showPrices(),
        normalizePaymentMethods(req.enabledPaymentMethods(), existing.enabledPaymentMethods()));
  }

  /**
   * Validates and canonicalises the owner-selected tender list ({@code null} keeps {@code
   * fallback}). At least one method must remain enabled — a store that accepts no tender at all
   * cannot sell — and each must be a known method code.
   */
  private static String normalizePaymentMethods(java.util.List<String> methods, String fallback) {
    if (methods == null) return fallback;
    var canonical = new java.util.LinkedHashSet<String>();
    for (String m : methods) {
      if (m == null || m.isBlank()) continue;
      String upper = m.trim().toUpperCase(Locale.ROOT);
      if (!Store.PAYMENT_METHODS.contains(upper))
        throw ApiException.badRequest(
            "STORE_PAYMENT_METHOD_INVALID",
            "enabledPaymentMethods entries must be one of "
                + Store.PAYMENT_METHODS
                + " — got: "
                + m);
      canonical.add(upper);
    }
    if (canonical.isEmpty())
      throw ApiException.badRequest(
          "STORE_PAYMENT_METHODS_EMPTY", "at least one payment method must be enabled");
    return String.join(",", canonical);
  }

  public Store patchStoreStatus(UUID tenantId, UUID storeId, PatchStatusRequest req) {
    getStore(tenantId, storeId);
    String status = req.status().toUpperCase(Locale.ROOT);
    // Publish so iam-svc can terminate POS sessions for this store and other consumers can react.
    var event =
        new OutboxRow(
            "StoreStatusChanged",
            "shelfj.tenant.store-status-changed",
            tenantId,
            storeId,
            Events.storeStatusChanged(tenantId, storeId, status));
    return repo.updateStoreStatusWithOutbox(tenantId, storeId, status, event);
  }

  public Zone getZone(UUID tenantId, UUID zoneId) {
    return repo.findZone(tenantId, zoneId)
        .orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "No such zone"));
  }

  public Zone updateZone(UUID tenantId, UUID zoneId, UpdateZoneRequest req) {
    getZone(tenantId, zoneId);
    String type = req.type() == null || req.type().isBlank() ? "AISLE" : req.type();
    return repo.updateZone(tenantId, zoneId, req.name(), req.code(), type);
  }

  public Zone patchZoneStatus(UUID tenantId, UUID zoneId, PatchStatusRequest req) {
    getZone(tenantId, zoneId);
    return repo.updateZoneStatus(tenantId, zoneId, req.status());
  }

  /** Cursor-paginated staff assignments (admin list). */
  public Cursor.Page<com.shelfj.tenant.domain.Domain.StaffAssignment> listStaff(
      UUID tenantId, String after, int limit) {
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    var rows =
        repo.listStaff(
            tenantId,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, s -> s.createdAt() + "|" + s.id());
  }

  public void removeStaff(UUID tenantId, UUID userId, UUID storeId) {
    repo.removeStaff(tenantId, userId, storeId);
  }

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  public TenantInventoryConfigResponse upsertInventoryConfig(
      UUID tenantId, UpsertInventoryConfigRequest req) {
    // The read (existing) and the merge both happen inside repo.upsertInventoryConfigMerged's
    // transaction, with the row locked FOR UPDATE first — otherwise two concurrent partial
    // updates can each merge against the same stale snapshot and the second silently clobbers
    // fields the first one just set.
    TenantInventoryConfig merged =
        repo.upsertInventoryConfigMerged(
            tenantId,
            existing -> {
              Instant now = Instant.now();
              UUID id = existing != null ? existing.id() : UUID.randomUUID();
              Instant createdAt = existing != null ? existing.createdAt() : now;
              return new TenantInventoryConfig(
                  id,
                  tenantId,
                  req.lotControlEnabled() != null
                      ? req.lotControlEnabled()
                      : (existing != null ? existing.lotControlEnabled() : true),
                  req.serialControlEnabled() != null
                      ? req.serialControlEnabled()
                      : (existing != null ? existing.serialControlEnabled() : false),
                  req.gradeControlEnabled() != null
                      ? req.gradeControlEnabled()
                      : (existing != null ? existing.gradeControlEnabled() : false),
                  req.expiryTrackingEnabled() != null
                      ? req.expiryTrackingEnabled()
                      : (existing != null ? existing.expiryTrackingEnabled() : true),
                  req.costingMethod() != null
                      ? req.costingMethod()
                      : (existing != null
                          ? existing.costingMethod()
                          : TenantInventoryConfig.COSTING_FIFO),
                  req.defaultUom() != null
                      ? req.defaultUom()
                      : (existing != null ? existing.defaultUom() : "EA"),
                  req.reorderAlertEnabled() != null
                      ? req.reorderAlertEnabled()
                      : (existing != null ? existing.reorderAlertEnabled() : true),
                  req.autoReserveOnOrder() != null
                      ? req.autoReserveOnOrder()
                      : (existing != null ? existing.autoReserveOnOrder() : true),
                  createdAt,
                  now);
            });
    return Mappers.toDto(merged);
  }

  public TenantInventoryConfigResponse getInventoryConfig(UUID tenantId) {
    return repo.findInventoryConfig(tenantId)
        .map(Mappers::toDto)
        .orElseThrow(
            () ->
                new ApiException(
                    404, "INVENTORY_CONFIG_NOT_FOUND", "No inventory config found", List.of()));
  }

  // ── delivery areas ─────────────────────────────────────────────────────────

  public DeliveryAreaResponse addDeliveryArea(
      UUID tenantId, UUID storeId, CreateDeliveryAreaRequest req) {
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () ->
                new ApiException(
                    404, "STORE_NOT_FOUND", "No such store in this tenant", List.of()));
    String pincode = req.pincode().trim();
    if (pincode.isEmpty()) {
      throw ApiException.badRequest("DELIVERY_PINCODE_REQUIRED", "pincode is required");
    }
    int priority = req.priority() != null ? req.priority() : 100;
    var area =
        new DeliveryArea(UUID.randomUUID(), tenantId, storeId, pincode, priority, Instant.now());
    try {
      return Mappers.toDto(repo.insertDeliveryArea(area));
    } catch (RuntimeException e) {
      // Unique (tenant, store, pincode) — surface a clean 409.
      throw new ApiException(
          409,
          "DELIVERY_AREA_EXISTS",
          "This store already covers pincode " + pincode,
          List.of(),
          e);
    }
  }

  public List<DeliveryAreaResponse> listDeliveryAreas(UUID tenantId, UUID storeId) {
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () ->
                new ApiException(
                    404, "STORE_NOT_FOUND", "No such store in this tenant", List.of()));
    return repo.listDeliveryAreas(tenantId, storeId).stream().map(Mappers::toDto).toList();
  }

  public void deleteDeliveryArea(UUID tenantId, UUID storeId, UUID areaId) {
    if (!repo.deleteDeliveryArea(tenantId, storeId, areaId)) {
      throw new ApiException(404, "DELIVERY_AREA_NOT_FOUND", "No such delivery area", List.of());
    }
  }

  /**
   * Resolve the fulfilling store for a home-delivery pincode. When the tenant has no delivery areas
   * configured, falls back to the tenant's default (or first) store so single-store tenants keep
   * working without mapping. When areas exist but the pincode is unmapped → 404.
   */
  public FulfilmentResolveResponse resolveFulfilment(UUID tenantId, String pincode) {
    if (pincode == null || pincode.isBlank()) {
      throw ApiException.badRequest("FULFILMENT_PINCODE_REQUIRED", "pincode is required");
    }
    Optional<DeliveryArea> hit = repo.resolveDeliveryArea(tenantId, pincode);
    if (hit.isPresent()) {
      Store store =
          repo.findStore(tenantId, hit.get().storeId())
              .orElseThrow(
                  () ->
                      new ApiException(
                          404, "STORE_NOT_FOUND", "Mapped store no longer exists", List.of()));
      return new FulfilmentResolveResponse(
          store.id().toString(),
          store.name(),
          store.code(),
          hit.get().pincode(),
          hit.get().priority());
    }
    if (repo.hasAnyDeliveryAreas(tenantId)) {
      throw new ApiException(
          404,
          "FULFILMENT_AREA_NOT_COVERED",
          "No store delivers to pincode " + pincode.trim(),
          List.of());
    }
    // No areas configured → default/first store.
    List<Store> stores = repo.listStores(tenantId);
    Store store =
        stores.stream()
            .filter(Store::isDefault)
            .findFirst()
            .or(() -> stores.stream().findFirst())
            .orElseThrow(
                () ->
                    new ApiException(
                        404, "STORE_NOT_FOUND", "Tenant has no stores to fulfil from", List.of()));
    return new FulfilmentResolveResponse(
        store.id().toString(), store.name(), store.code(), pincode.trim(), 0);
  }

  private static UUID parseUuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
