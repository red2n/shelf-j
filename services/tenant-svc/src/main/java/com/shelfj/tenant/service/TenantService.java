package com.shelfj.tenant.service;

import com.shelfj.ids.Ids;
import com.shelfj.service.OutboxRow;
import com.shelfj.tenant.domain.Domain;
import com.shelfj.tenant.domain.Domain.DeliveryArea;
import com.shelfj.tenant.domain.Domain.StaffAssignment;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.StoreWithZone;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.TenantInventoryConfig;
import com.shelfj.tenant.domain.Domain.TenantRole;
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
import com.shelfj.web.Permissions;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
  private static final System.Logger LOG = System.getLogger(TenantService.class.getName());

  @Inject PlanService plans;

  /**
   * What a business owes for the plan it lands on (21.9). Starting it is best effort at sign-up —
   * see {@code createTenant}.
   */
  @Inject SubscriptionService subscriptions;

  // --- onboarding ---

  /**
   * Create the business and bind the authenticated owner. Publishes TenantCreated (carries
   * ownerUserId). Also publishes UserRoleGranted to ensure the creator has OWNER role (flow guard:
   * user has no tenant claim in JWT yet, so they need role update before they can access admin
   * endpoints).
   */
  public Tenant createTenant(UUID ownerUserId, CreateTenantRequest req) {
    UUID tenantId = Ids.newId();
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
            nowTenant,
            null,
            null,
            null);
    var event =
        new OutboxRow(
            "TenantCreated",
            "shelfj.tenant.tenant-created",
            tenantId,
            tenantId,
            Events.tenantCreated(
                tenantId, ownerUserId, req.businessName(), tenant.country(), tenant.currency()));
    var createdTenant = repo.createTenantWithOutbox(tenant, event);

    // A business signs up on whatever the platform sells by default (21.8). Best effort: one on no
    // plan is unrestricted, so failing to place it is safe where failing to create it is not.
    plans.putOnDefaultPlan(tenantId);

    // And signing up is subscribing (21.9): the plan it landed on decides what it owes and when.
    // Best effort for the same reason, and with one more: a business that exists and is not billed
    // is a commercial problem somebody can fix afterwards, where a sign-up that fails because the
    // platform has not filled in its own VAT details is a customer lost at the door.
    try {
      subscriptions.start(tenantId, java.time.LocalDate.now(), ownerUserId);
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "{0} was created but not subscribed: {1}",
          tenantId,
          e.getMessage());
    }

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
    // Checked before the tenant exists: a refused zone must not leave a business with no store.
    requireTimezone(req.storeTimezone());
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
            req.storeTimezone(),
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

  /**
   * Add a store (not necessarily default).
   *
   * <p>Unlike {@link #createDefaultStore} this is an ordinary admin call, reached with a
   * JWT-supplied tenant, so it needs no owner check. The store still gets a DEFAULT zone.
   *
   * @param tenantId owning tenant
   * @param req the store's name, code, type, address and settings
   * @return the new store with its DEFAULT zone
   */
  public StoreWithZone addStore(UUID tenantId, CreateStoreRequest req) {
    return createStoreInternal(tenantId, req, false);
  }

  private static final java.util.Set<String> ZONES = java.time.ZoneId.getAvailableZoneIds();

  /**
   * The IANA zone a store trades in. Required and never defaulted (SJ-D54): no country has one
   * right answer, and a store on the wrong clock opens its tills and dates its receipts an hour
   * out.
   *
   * @throws ApiException 400 {@code STORE_TIMEZONE_REQUIRED} when absent, {@code
   *     STORE_TIMEZONE_INVALID} when it is not an IANA zone
   */
  private static String requireTimezone(String zone) {
    if (zone == null || zone.isBlank()) {
      throw ApiException.badRequest(
          "STORE_TIMEZONE_REQUIRED",
          "timezone is required: the IANA zone the store trades in, such as Europe/London");
    }
    String trimmed = zone.trim();
    if (!ZONES.contains(trimmed)) {
      throw ApiException.badRequest(
          "STORE_TIMEZONE_INVALID",
          "timezone must be an IANA zone such as Europe/London or Asia/Kolkata");
    }
    return trimmed;
  }

  private StoreWithZone createStoreInternal(
      UUID tenantId, CreateStoreRequest req, boolean isDefault) {
    // What the business is sold decides how many stores it may open (21.8).
    plans.requireRoomForAnotherStore(tenantId);
    UUID storeId = Ids.newId();
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
            requireTimezone(req.timezone()),
            req.businessHours(),
            "ACTIVE",
            isDefault,
            req.showPrices() == null || req.showPrices(),
            normalizePaymentMethods(req.enabledPaymentMethods(), Store.DEFAULT_PAYMENT_METHODS),
            nowStore,
            nowStore);

    // Always create a DEFAULT zone so stock has a home (golden rule of the location model).
    UUID zoneId = Ids.newId();
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
    // cart-svc, order-svc and iam-svc gate on a local store_status projection that lets through a
    // store it has no row for (the caller's own write can race this event). Announcing the status
    // at creation gives every projection a row naming the owner, so another tenant cannot trade
    // against this store's id while its status has never changed.
    var statusEvent =
        new OutboxRow(
            "StoreStatusChanged",
            "shelfj.tenant.store-status-changed",
            tenantId,
            storeId,
            Events.storeStatusChanged(tenantId, storeId, store.status()));

    return repo.createStoreWithDefaultZone(
        store, defaultZone, List.of(storeEvent, zoneEvent, statusEvent));
  }

  /** Add a zone to a store. Publishes ZoneCreated. */
  public Zone addZone(UUID tenantId, UUID storeId, CreateZoneRequest req) {
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
    UUID zoneId = Ids.newId();
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

  /**
   * Assigns a staff user a role at a store and publishes {@code StaffAssigned}.
   *
   * <p>The role is a built-in tier or one of the tenant's own (20.10). A custom role is resolved
   * here: the event carries the tier iam-svc binds, the code, and the permissions the role holds
   * now — so a login made tomorrow carries them, and a role redefined later reaches its holders
   * through {@code RoleDefined}.
   *
   * @throws ApiException 400 {@code STAFF_ROLE_UNKNOWN} for a role that is neither; 403 {@code
   *     PERMISSION_DENIED} without {@code staff.manage}; 404 {@code STORE_NOT_FOUND}
   */
  public void assignStaff(TenantContext ctx, AssignStaffRequest req) {
    ctx.requirePermission(Permissions.STAFF_MANAGE);
    UUID tenantId = ctx.requireTenantId();
    UUID userId = parseUuid(req.userId(), "userId");
    UUID storeId = parseUuid(req.storeId(), "storeId");
    // What the business is sold decides how many people may work for it (21.8).
    plans.requireRoomForAnotherStaffMember(tenantId, userId);
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () -> ApiException.notFound("STORE_NOT_FOUND", "No such store in this tenant"));
    String role = req.role().trim().toUpperCase(Locale.ROOT);
    String baseTier;
    String roleCode = null;
    Set<String> permissions = null;
    Instant roleUpdatedAt = null;
    if (Domain.STAFF_TIERS.contains(role)) {
      baseTier = role;
    } else {
      TenantRole custom =
          repo.findRole(tenantId, role)
              .orElseThrow(
                  () ->
                      ApiException.badRequest(
                          "STAFF_ROLE_UNKNOWN",
                          "role must be one of "
                              + Domain.STAFF_TIERS
                              + " or one of this tenant's own roles, not "
                              + req.role()));
      baseTier = custom.baseTier();
      roleCode = custom.code();
      permissions = custom.permissions();
      roleUpdatedAt = custom.updatedAt();
    }
    var assignment =
        new StaffAssignment(Ids.newId(), tenantId, userId, storeId, role, baseTier, Instant.now());
    var event =
        new OutboxRow(
            "StaffAssigned",
            "shelfj.tenant.staff-assigned",
            tenantId,
            userId,
            Events.staffAssigned(
                tenantId, userId, storeId, baseTier, roleCode, permissions, roleUpdatedAt));
    repo.createStaffWithOutbox(assignment, event);
  }

  // ── custom roles (20.10) ──────────────────────────────────────────────────

  private static final java.util.regex.Pattern ROLE_CODE =
      java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,31}");

  /**
   * The permission catalogue with the tiers holding each by default: what a role-definition screen
   * shows beside its checkboxes.
   */
  public List<com.shelfj.tenant.dto.Dtos.PermissionResponse> permissionCatalogue() {
    return Permissions.catalogue().entrySet().stream()
        .map(
            e ->
                new com.shelfj.tenant.dto.Dtos.PermissionResponse(
                    e.getKey(),
                    e.getValue(),
                    Permissions.TIERS.stream()
                        .filter(t -> Permissions.defaultsFor(t).contains(e.getKey()))
                        .sorted()
                        .toList()))
        .toList();
  }

  /**
   * Defines a custom role and publishes {@code RoleDefined}.
   *
   * <p>A role stands on one tier and holds a subset of that tier's default permissions — never
   * more, so nothing the tier refuses by path becomes reachable by naming a permission. Its code is
   * upper snake case and cannot be a built-in role's name, because a token carrying {@code MANAGER}
   * must mean one thing.
   *
   * @throws ApiException 400 {@code ROLE_CODE_INVALID}, {@code ROLE_CODE_RESERVED}, {@code
   *     ROLE_TIER_INVALID}, {@code ROLE_PERMISSION_UNKNOWN}, {@code ROLE_PERMISSION_OUTSIDE_TIER};
   *     403 {@code PERMISSION_DENIED}; 409 {@code ROLE_ALREADY_EXISTS}
   */
  public TenantRole defineRole(
      TenantContext ctx, com.shelfj.tenant.dto.Dtos.DefineRoleRequest req) {
    ctx.requirePermission(Permissions.STAFF_MANAGE);
    UUID tenantId = ctx.requireTenantId();
    String code = req.code().trim().toUpperCase(Locale.ROOT);
    if (!ROLE_CODE.matcher(code).matches()) {
      throw ApiException.badRequest(
          "ROLE_CODE_INVALID",
          "a role code is 2-32 characters of upper-case letters, digits and underscores");
    }
    if (Domain.STAFF_TIERS.contains(code)
        || "PLATFORM_ADMIN".equals(code)
        || "CUSTOMER".equals(code)
        || "GUEST".equals(code)) {
      throw ApiException.badRequest(
          "ROLE_CODE_RESERVED", code + " is a built-in role and cannot be redefined");
    }
    String tier = req.baseTier().trim().toUpperCase(Locale.ROOT);
    Set<String> permissions = checkedPermissions(tier, req.permissions());
    Instant now = Instant.now();
    TenantRole role =
        new TenantRole(
            Ids.newId(),
            tenantId,
            code,
            req.name().trim(),
            tier,
            permissions,
            blankToNull(req.description()),
            now,
            now);
    repo.createRole(role, roleDefinedEvent(role));
    return role;
  }

  /**
   * Renames a role or changes what it holds, and publishes {@code RoleDefined} so iam-svc rewrites
   * every assignment made through it; holders carry the new set from their next login. The tier
   * cannot change: an assignment's tier is bound on the login, and a role that changed tier under
   * it would leave the token saying one thing and the role another.
   *
   * @throws ApiException 404 {@code ROLE_NOT_FOUND}; the 400s of {@link #defineRole}
   */
  public TenantRole updateRole(
      TenantContext ctx, String code, com.shelfj.tenant.dto.Dtos.UpdateRoleRequest req) {
    ctx.requirePermission(Permissions.STAFF_MANAGE);
    TenantRole existing = getRole(ctx.requireTenantId(), code);
    Set<String> permissions = checkedPermissions(existing.baseTier(), req.permissions());
    TenantRole role =
        new TenantRole(
            existing.id(),
            existing.tenantId(),
            existing.code(),
            req.name().trim(),
            existing.baseTier(),
            permissions,
            blankToNull(req.description()),
            existing.createdAt(),
            Instant.now());
    if (!repo.updateRole(role, roleDefinedEvent(role))) {
      throw ApiException.notFound("ROLE_NOT_FOUND", "No such role: " + code);
    }
    return role;
  }

  /**
   * @throws ApiException 404 {@code ROLE_NOT_FOUND}
   */
  public TenantRole getRole(UUID tenantId, String code) {
    return repo.findRole(tenantId, code.trim().toUpperCase(Locale.ROOT))
        .orElseThrow(() -> ApiException.notFound("ROLE_NOT_FOUND", "No such role: " + code));
  }

  /** The tenant's custom roles, by code. */
  public List<TenantRole> listRoles(UUID tenantId) {
    return repo.listRoles(tenantId);
  }

  /**
   * Deletes a custom role nobody holds.
   *
   * @throws ApiException 404 {@code ROLE_NOT_FOUND}; 409 {@code ROLE_IN_USE}
   */
  public void deleteRole(TenantContext ctx, String code) {
    ctx.requirePermission(Permissions.STAFF_MANAGE);
    if (!repo.deleteRole(ctx.requireTenantId(), code.trim().toUpperCase(Locale.ROOT))) {
      throw ApiException.notFound("ROLE_NOT_FOUND", "No such role: " + code);
    }
  }

  private static Set<String> checkedPermissions(String tier, List<String> requested) {
    if (!Permissions.TIERS.contains(tier)) {
      throw ApiException.badRequest(
          "ROLE_TIER_INVALID", "baseTier must be one of " + Permissions.TIERS + ", not " + tier);
    }
    Set<String> allowed = Permissions.defaultsFor(tier);
    Set<String> out = new java.util.LinkedHashSet<>();
    for (String p : requested) {
      String code = p.trim();
      if (!Permissions.isKnown(code)) {
        throw ApiException.badRequest(
            "ROLE_PERMISSION_UNKNOWN", code + " is not a permission; see /admin/roles/permissions");
      }
      if (!allowed.contains(code)) {
        throw ApiException.badRequest(
            "ROLE_PERMISSION_OUTSIDE_TIER",
            code
                + " is not held by a "
                + tier
                + "; a role can only narrow its tier, never widen it");
      }
      out.add(code);
    }
    return Set.copyOf(out);
  }

  private static OutboxRow roleDefinedEvent(TenantRole role) {
    return new OutboxRow(
        "RoleDefined",
        "shelfj.tenant.role-defined",
        role.tenantId(),
        role.id(),
        Events.roleDefined(
            role.tenantId(), role.code(), role.baseTier(), role.permissions(), role.updatedAt()));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
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

  /**
   * Reads one tenant.
   *
   * @param tenantId the tenant to read
   * @return the tenant
   * @throws ApiException {@code TENANT_NOT_FOUND} (404) when no such tenant exists
   */
  public Tenant getTenant(UUID tenantId) {
    return repo.findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  /**
   * The one active business a network's delivery addressed this way lands with (07.13, the
   * transport seam).
   *
   * <p>By electronic address when one is named — the participant identifier an access point
   * delivered to — else by VAT identifier, as a document with no address names its buyer. A
   * suspended business receives nothing; and an address two active businesses both claim places
   * nothing, since the platform cannot tell which of them the document is for.
   *
   * @param scheme the address's EAS scheme, with {@code id}; both or neither
   * @param id the identifier within the scheme
   * @param vatNumber the buyer's VAT identifier, read when no address is given
   * @return the business
   * @throws ApiException {@code 400 TENANT_EINVOICE_ADDRESS_INVALID} for half an address, {@code
   *     400 TENANT_RECEIVER_UNNAMED} for neither an address nor a VAT number, {@code 404
   *     TENANT_NOT_FOUND} when no active business holds it, {@code 409
   *     TENANT_EINVOICE_ADDRESS_SHARED} when more than one does
   */
  public Tenant receiver(String scheme, String id, String vatNumber) {
    String named;
    List<Tenant> holders;
    if (present(scheme) || present(id)) {
      if (!present(scheme) || !present(id)) {
        throw ApiException.badRequest(
            "TENANT_EINVOICE_ADDRESS_INVALID", "an address is a scheme and an id together");
      }
      named = scheme.strip() + ":" + id.strip();
      holders = repo.findTenantsByEinvoiceAddress(scheme.strip(), id.strip());
    } else if (present(vatNumber)) {
      named = vatNumber.replace(" ", "").toUpperCase(Locale.ROOT);
      holders = repo.findTenantsByVatNumber(named);
    } else {
      throw ApiException.badRequest(
          "TENANT_RECEIVER_UNNAMED", "name an electronic address (scheme and id) or a VAT number");
    }
    List<Tenant> active =
        holders.stream().filter(t -> Tenant.STATUS_ACTIVE.equals(t.status())).toList();
    if (active.isEmpty()) {
      throw ApiException.notFound("TENANT_NOT_FOUND", "no active business holds " + named);
    }
    if (active.size() > 1) {
      throw ApiException.conflict(
          "TENANT_EINVOICE_ADDRESS_SHARED",
          active.size() + " businesses hold " + named + ", so a delivery to it lands nowhere");
    }
    return active.get(0);
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  /**
   * Every store in the tenant, unpaginated.
   *
   * <p>For internal use where the whole set is wanted at once; the API list uses the cursor-paged
   * overload.
   *
   * @param tenantId owning tenant
   * @return the tenant's stores
   */
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

  /**
   * Activates or deactivates a tenant and announces the change.
   *
   * <p>The event matters as much as the row: deactivating a tenant must lock its staff out across
   * iam-svc, cart-svc and order-svc, not just flip a column they cannot see.
   *
   * @param tenantId the tenant whose status to change
   * @param req the new status, {@code ACTIVE} or {@code INACTIVE}
   * @return the tenant with its new status
   * @throws ApiException {@code TENANT_NOT_FOUND} (404) when no such tenant exists; {@code
   *     INVALID_STATUS} (400) when the status is neither ACTIVE nor INACTIVE
   */
  public Tenant patchTenantStatus(UUID tenantId, PatchStatusRequest req) {
    getTenant(tenantId);
    String status = req.status().toUpperCase(Locale.ROOT);
    if (!Tenant.STATUS_ACTIVE.equals(status) && !Tenant.STATUS_INACTIVE.equals(status)) {
      throw ApiException.badRequest("INVALID_STATUS", "status must be ACTIVE or INACTIVE");
    }
    return repo.updateTenantStatusWithOutbox(tenantId, status, tenantStatusEvent(tenantId, status));
  }

  /**
   * The event a status change publishes, built in one place.
   *
   * <p>Deactivating a business must lock its staff out and close its storefront, not just flip a
   * row no other service can see — that is what this event is for. It is built here rather than at
   * each call site because dunning (21.12) writes the status itself and then announces it, and two
   * copies of the announcement would drift on what it says.
   */
  private static OutboxRow tenantStatusEvent(UUID tenantId, String status) {
    return new OutboxRow(
        "TenantStatusChanged",
        "shelfj.tenant.tenant-status-changed",
        tenantId,
        tenantId,
        Events.tenantStatusChanged(tenantId, status));
  }

  /**
   * Announces a status the dunning side has already written (21.12).
   *
   * <p>Dunning writes {@code tenants.status} itself, because it also writes <em>why</em> — the
   * reason is what lets a payment lift a suspension the platform imposed and not one an
   * administrator imposed. So it needs the announcement without the write, and this is that half.
   *
   * @param status the status already on the row
   */
  public void announceStatus(UUID tenantId, String status) {
    repo.publishEvent(tenantStatusEvent(tenantId, status));
  }

  /**
   * Re-announces tenants' declared currencies so downstream projections can be rebuilt.
   *
   * <p>Exists because a projection fed only by TenantCreated can never cover a tenant that was
   * onboarded before the consumer did. order-svc stamps every money-bearing row with the tenant's
   * currency read from such a projection, and falls back to a platform-wide default when it is
   * missing — so a tenant predating that consumer trades in the wrong currency indefinitely, with
   * nothing to signal it. There was no way to fill that gap without either a cross-service read of
   * this service's tables or a synchronous call on the checkout path; this is the third option.
   *
   * <p>Safe to run repeatedly. Consumers dedupe on eventId and each replay carries fresh ones, so a
   * second run re-applies the same projection rather than being skipped — which is what makes it a
   * repair tool rather than a one-shot migration.
   *
   * @param scope a single tenant to re-announce, or {@code null} for every tenant
   * @return how many tenants were announced; zero when {@code scope} names a tenant with no
   *     currency recorded, or no tenant at all
   */
  public int republishTenantCurrencies(UUID scope) {
    List<OutboxRow> events =
        repo.findTenantCurrencies(scope).stream()
            .map(
                tc ->
                    new OutboxRow(
                        "TenantCurrencyDeclared",
                        "shelfj.tenant.tenant-currency-declared",
                        tc.tenantId(),
                        tc.tenantId(),
                        Events.tenantCurrencyDeclared(tc.tenantId(), tc.currency())))
            .toList();
    return repo.publishEvents(events);
  }

  /**
   * Renames a tenant.
   *
   * <p>Country and currency are not editable here — they are stamped at onboarding and downstream
   * services have already projected them.
   *
   * @param tenantId the tenant to update
   * @param req the new business name and optional legal name
   * @return the updated tenant
   * @throws ApiException {@code TENANT_NOT_FOUND} (404) when no such tenant exists
   */
  public Tenant updateTenant(UUID tenantId, UpdateTenantRequest req) {
    Tenant existing = getTenant(tenantId);
    // The e-invoicing identity (07.13, 18.9): omitted is unchanged, empty removes it.
    String vatNumber;
    try {
      vatNumber =
          req.vatNumber() == null
              ? existing.vatNumber()
              // India's e-invoices name a business by its GSTIN, which carries a state code where
              // a VAT identifier carries a country prefix.
              : "IN".equals(existing.country())
                  ? com.shelfj.einvoice.Gstin.parse(req.vatNumber())
                  : com.shelfj.einvoice.VatIdentifier.parse(req.vatNumber());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "TENANT_VAT_NUMBER_INVALID", e.getMessage(), List.of(), e);
    }
    com.shelfj.einvoice.ElectronicAddress address;
    try {
      address =
          req.einvoiceScheme() != null || req.einvoiceId() != null
              ? com.shelfj.einvoice.ElectronicAddress.parse(req.einvoiceScheme(), req.einvoiceId())
              : existing.einvoiceId() == null
                  ? null
                  : new com.shelfj.einvoice.ElectronicAddress(
                      existing.einvoiceScheme(), existing.einvoiceId());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "TENANT_EINVOICE_ADDRESS_INVALID", e.getMessage(), List.of(), e);
    }
    return repo.updateTenant(
        tenantId,
        req.businessName().trim(),
        req.legalName() == null ? null : req.legalName().trim(),
        vatNumber,
        address == null ? null : address.scheme(),
        address == null ? null : address.id());
  }

  /**
   * Reads one store.
   *
   * @param tenantId owning tenant
   * @param storeId the store to read
   * @return the store
   * @throws ApiException {@code STORE_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public Store getStore(UUID tenantId, UUID storeId) {
    return repo.findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "No such store"));
  }

  /**
   * Updates a store's address, hours and trading settings.
   *
   * <p>{@code showPrices} and {@code enabledPaymentMethods} are preserved when the request omits
   * them, so a partial update cannot silently switch a shop to catalogue-only or strip its tenders.
   *
   * @param tenantId owning tenant
   * @param storeId the store to update
   * @param req the replacement details; null {@code showPrices}/{@code enabledPaymentMethods} keep
   *     the current values
   * @return the updated store
   * @throws ApiException {@code STORE_NOT_FOUND} (404) when it does not exist in this tenant;
   *     {@code STORE_PAYMENT_METHOD_INVALID} or {@code STORE_PAYMENT_METHODS_EMPTY} (400) when the
   *     tender list is unusable
   */
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
        // An update that leaves the zone out keeps it; it used to reset it to UTC (SJ-D54).
        req.timezone() == null || req.timezone().isBlank()
            ? existing.timezone()
            : requireTimezone(req.timezone()),
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

  /**
   * Changes a store's trading status and announces the change.
   *
   * <p>Published so iam-svc can terminate that store's POS sessions and cart/order-svc can stop
   * accepting trade against it.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose status to change
   * @param req the new status, one of {@link Store#STATUSES}
   * @return the store with its new status
   * @throws ApiException {@code STORE_NOT_FOUND} (404) when it does not exist in this tenant;
   *     {@code INVALID_STATUS} (400) when the status is not a known one
   */
  public Store patchStoreStatus(UUID tenantId, UUID storeId, PatchStatusRequest req) {
    getStore(tenantId, storeId);
    String status = req.status().toUpperCase(Locale.ROOT);
    if (!Store.STATUSES.contains(status)) {
      throw ApiException.badRequest("INVALID_STATUS", "status must be one of " + Store.STATUSES);
    }
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

  /**
   * Reads one zone.
   *
   * @param tenantId owning tenant
   * @param zoneId the zone to read
   * @return the zone
   * @throws ApiException {@code ZONE_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public Zone getZone(UUID tenantId, UUID zoneId) {
    return repo.findZone(tenantId, zoneId)
        .orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "No such zone"));
  }

  /**
   * Renames or retypes a zone.
   *
   * @param tenantId owning tenant
   * @param zoneId the zone to update
   * @param req the new name, code and type; a blank type falls back to {@code AISLE}
   * @return the updated zone
   * @throws ApiException {@code ZONE_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public Zone updateZone(UUID tenantId, UUID zoneId, UpdateZoneRequest req) {
    getZone(tenantId, zoneId);
    String type = req.type() == null || req.type().isBlank() ? "AISLE" : req.type();
    return repo.updateZone(tenantId, zoneId, req.name(), req.code(), type);
  }

  /**
   * Changes a zone's status.
   *
   * <p>Unlike a store status change, this publishes no event — no other service projects zone
   * status; inventory-svc only references the zone id a batch sits in.
   *
   * @param tenantId owning tenant
   * @param zoneId the zone whose status to change
   * @param req the new status
   * @return the zone with its new status
   * @throws ApiException {@code ZONE_NOT_FOUND} (404) when it does not exist in this tenant
   */
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

  /**
   * Unassigns a staff member from a store.
   *
   * <p>Removes the assignment row only; it does not delete the person's iam-svc login.
   *
   * @param tenantId owning tenant
   * @param userId the staff member to unassign
   * @param storeId the store to unassign them from
   */
  public void removeStaff(TenantContext ctx, UUID userId, UUID storeId) {
    ctx.requirePermission(Permissions.STAFF_MANAGE);
    UUID tenantId = ctx.requireTenantId();
    // SJ-D51: the removal used to stop at this table, and the role stayed on the login for good.
    repo.removeStaffWithOutbox(
        tenantId,
        userId,
        storeId,
        tier ->
            new OutboxRow(
                "StaffRemoved",
                "shelfj.tenant.staff-removed",
                tenantId,
                userId,
                Events.staffRemoved(tenantId, userId, storeId, tier)));
  }

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  /**
   * Creates or partially updates the tenant's inventory parameters.
   *
   * <p>A null field on the request keeps the stored value, so callers can send only what they mean
   * to change. The read and the merge both happen inside one transaction with the row locked, so
   * two concurrent partial updates cannot each merge against the same stale snapshot.
   *
   * @param tenantId owning tenant
   * @param req the fields to change; nulls keep their current values
   * @return the merged configuration as stored
   */
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
              UUID id = existing != null ? existing.id() : Ids.newId();
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

  /**
   * Reads the tenant's inventory parameters.
   *
   * @param tenantId owning tenant
   * @return the stored configuration
   * @throws ApiException {@code INVENTORY_CONFIG_NOT_FOUND} (404) when the tenant has never set one
   */
  public TenantInventoryConfigResponse getInventoryConfig(UUID tenantId) {
    return repo.findInventoryConfig(tenantId)
        .map(Mappers::toDto)
        .orElseThrow(
            () ->
                new ApiException(
                    404, "INVENTORY_CONFIG_NOT_FOUND", "No inventory config found", List.of()));
  }

  // ── delivery areas ─────────────────────────────────────────────────────────

  /**
   * Maps a delivery pincode to a store.
   *
   * @param tenantId owning tenant
   * @param storeId the store that will fulfil this pincode
   * @param req the pincode and optional priority, defaulting to 100; lower wins when two stores
   *     cover the same pincode
   * @return the created delivery area
   * @throws ApiException {@code STORE_NOT_FOUND} (404) when the store does not exist in this
   *     tenant; {@code DELIVERY_PINCODE_REQUIRED} (400) when the pincode is blank; {@code
   *     DELIVERY_AREA_EXISTS} (409) when this store already covers it
   */
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
    var area = new DeliveryArea(Ids.newId(), tenantId, storeId, pincode, priority, Instant.now());
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

  /**
   * Lists the pincodes one store delivers to.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose areas to list
   * @return the delivery areas, empty when none are mapped
   * @throws ApiException {@code STORE_NOT_FOUND} (404) when the store does not exist in this tenant
   */
  public List<DeliveryAreaResponse> listDeliveryAreas(UUID tenantId, UUID storeId) {
    repo.findStore(tenantId, storeId)
        .orElseThrow(
            () ->
                new ApiException(
                    404, "STORE_NOT_FOUND", "No such store in this tenant", List.of()));
    return repo.listDeliveryAreas(tenantId, storeId).stream().map(Mappers::toDto).toList();
  }

  /**
   * Unmaps a pincode from a store.
   *
   * <p>Removing the tenant's last delivery area returns fulfilment to the default-store fallback
   * described on {@link #resolveFulfilment}.
   *
   * @param tenantId owning tenant
   * @param storeId the store the area belongs to
   * @param areaId the delivery area to remove
   * @throws ApiException {@code DELIVERY_AREA_NOT_FOUND} (404) when no such area exists
   */
  public void deleteDeliveryArea(UUID tenantId, UUID storeId, UUID areaId) {
    if (!repo.deleteDeliveryArea(tenantId, storeId, areaId)) {
      throw new ApiException(404, "DELIVERY_AREA_NOT_FOUND", "No such delivery area", List.of());
    }
  }

  /**
   * Resolve the fulfilling store for a home-delivery pincode. When the tenant has no delivery areas
   * configured, falls back to the tenant's default (or first) store so single-store tenants keep
   * working without mapping. When areas exist but the pincode is unmapped → 404.
   *
   * @param tenantId owning tenant
   * @param pincode the delivery pincode to resolve
   * @return the fulfilling store, with the matched pincode and its priority
   * @throws ApiException {@code FULFILMENT_PINCODE_REQUIRED} (400) when the pincode is blank;
   *     {@code FULFILMENT_AREA_NOT_COVERED} (404) when areas exist but none covers it; {@code
   *     STORE_NOT_FOUND} (404) when the mapped store is gone or the tenant has no stores
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
