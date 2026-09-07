package com.shelfj.tenant.repo;

import com.shelfj.events.EventPayload;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.tenant.domain.Domain.DeliveryArea;
import com.shelfj.tenant.domain.Domain.StaffAssignment;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.StoreWithZone;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.TenantCurrency;
import com.shelfj.tenant.domain.Domain.TenantInventoryConfig;
import com.shelfj.tenant.domain.Domain.Zone;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for tenants/stores/zones/staff + outbox. Every store/zone/staff query filters
 * tenant_id FIRST (golden rule #3). Multi-row writes atomic with their outbox events (golden rule
 * #6).
 */
@ApplicationScoped
public class TenantRepository extends BaseOutboxRepository {

  // ─────────────────────────────────────────────── create (atomic with outbox)

  public Tenant createTenantWithOutbox(Tenant t, OutboxRow event) {
    return inTx(
        c -> {
          insertTenant(c, t);
          insertOutbox(c, event);
          return t;
        },
        "create tenant");
  }

  public StoreWithZone createStoreWithDefaultZone(
      Store store, Zone defaultZone, OutboxRow storeEvent, OutboxRow zoneEvent) {
    return inTx(
        c -> {
          assertTenantActive(c, store.tenantId());
          insertStore(c, store);
          insertZone(c, defaultZone);
          insertOutbox(c, storeEvent);
          insertOutbox(c, zoneEvent);
          return new StoreWithZone(store, defaultZone);
        },
        "create store");
  }

  public Zone createZoneWithOutbox(Zone zone, OutboxRow event) {
    return inTx(
        c -> {
          insertZone(c, zone);
          insertOutbox(c, event);
          return zone;
        },
        "create zone");
  }

  public void createStaffWithOutbox(StaffAssignment s, OutboxRow event) {
    inTx(
        c -> {
          insertStaff(c, s);
          insertOutbox(c, event);
          return null;
        },
        "assign staff");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "CODE_ALREADY_EXISTS", "A record with that code already exists", List.of(), e);
    return dbError(what, e);
  }

  // ─────────────────────────────────────────────────────── tenant reads/writes

  public Optional<Tenant> findTenant(UUID tenantId) {
    return one(
        "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
            + " created_at, updated_at FROM tenants WHERE id = ?",
        tenantId,
        TenantRepository::mapTenant);
  }

  public Tenant updateTenantStatus(UUID tenantId, String status) {
    Instant now = Instant.now();
    exec(
        "UPDATE tenants SET status = ?, updated_at = ? WHERE id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
        },
        "update tenant status");
    return findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  /**
   * Flip the tenant status AND publish the change event in one transaction (golden rule #6), so a
   * suspension can never be applied locally without other services (iam-svc) hearing about it.
   */
  public Tenant updateTenantStatusWithOutbox(UUID tenantId, String status, OutboxRow event) {
    Instant now = Instant.now();
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE tenants SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            if (ps.executeUpdate() == 0) {
              throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found");
            }
          }
          // Cascade: when a tenant is suspended, mark all its ACTIVE stores SUSPENDED too so
          // the gateway's TenantStatusGate cache refresh reflects closure immediately, AND
          // publish a StoreStatusChanged per affected store — otherwise every other service's
          // *local* store_status projection (cart-svc, order-svc, iam-svc) never learns of the
          // cascade and keeps reporting those stores as ACTIVE.
          // Stores are NOT auto-reactivated when the tenant is re-enabled — that is an
          // explicit operator action (PATCH /admin/stores/{id}/status).
          if (!"ACTIVE".equals(status)) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE stores SET status = 'SUSPENDED', updated_at = ?"
                        + " WHERE tenant_id = ? AND status = 'ACTIVE' RETURNING id")) {
              ps.setObject(1, now.atOffset(ZoneOffset.UTC));
              ps.setObject(2, tenantId);
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  UUID storeId = rs.getObject("id", UUID.class);
                  String payload =
                      EventPayload.base("StoreStatusChanged", tenantId, storeId)
                          + ",\"storeId\":\""
                          + storeId
                          + "\",\"status\":\"SUSPENDED\"}";
                  insertOutbox(
                      c,
                      new OutboxRow(
                          "StoreStatusChanged",
                          "shelfj.tenant.store-status-changed",
                          tenantId,
                          storeId,
                          payload));
                }
              }
            }
          }
          insertOutbox(c, event);
          return null;
        },
        "update tenant status");
    return findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  public Tenant updateTenant(UUID tenantId, String businessName, String legalName) {
    Instant now = Instant.now();
    exec(
        "UPDATE tenants SET name = ?, legal_name = ?, updated_at = ? WHERE id = ?",
        ps -> {
          ps.setString(1, businessName);
          ps.setString(2, legalName);
          ps.setObject(3, now.atOffset(ZoneOffset.UTC));
          ps.setObject(4, tenantId);
        },
        "update tenant");
    return findTenant(tenantId)
        .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "Tenant not found"));
  }

  // ──────────────────────────────────────────────────────── store reads/writes

  public List<Store> listStores(UUID tenantId) {
    return many(
        "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
            + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices, enabled_payment_methods, created_at, updated_at"
            + " FROM stores WHERE tenant_id = ? ORDER BY created_at",
        tenantId,
        TenantRepository::mapStore);
  }

  /** Keyset page of stores: rows strictly after the cursor in (created_at, id) order. */
  public List<Store> listStores(UUID tenantId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices,"
                + " enabled_payment_methods, created_at, updated_at"
                + " FROM stores WHERE tenant_id = ?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapStore,
        "list stores page");
  }

  public Optional<Store> findStore(UUID tenantId, UUID storeId) {
    return query(
            "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices, enabled_payment_methods, created_at, updated_at"
                + " FROM stores WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
            },
            TenantRepository::mapStore,
            "find store")
        .stream()
        .findFirst();
  }

  public Store updateStore(
      UUID tenantId,
      UUID storeId,
      String name,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      BigDecimal geoLat,
      BigDecimal geoLng,
      String timezone,
      String businessHours,
      boolean showPrices,
      String enabledPaymentMethods) {
    Instant now = Instant.now();
    exec(
        "UPDATE stores SET name=?, line1=?, line2=?, city=?, state=?, country=?, pincode=?,"
            + " geo_lat=?, geo_lng=?, timezone=?, business_hours=?, show_prices=?,"
            + " enabled_payment_methods=?, updated_at=?"
            + " WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, line1);
          ps.setString(3, line2);
          ps.setString(4, city);
          ps.setString(5, state);
          ps.setString(6, country);
          ps.setString(7, pincode);
          ps.setBigDecimal(8, geoLat);
          ps.setBigDecimal(9, geoLng);
          ps.setString(10, timezone);
          ps.setString(11, businessHours);
          ps.setBoolean(12, showPrices);
          ps.setString(13, enabledPaymentMethods);
          ps.setObject(14, now.atOffset(ZoneOffset.UTC));
          ps.setObject(15, tenantId);
          ps.setObject(16, storeId);
        },
        "update store");
    return findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  public Store updateStoreStatus(UUID tenantId, UUID storeId, String status) {
    Instant now = Instant.now();
    exec(
        "UPDATE stores SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, storeId);
        },
        "update store status");
    return findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  /**
   * Flip a store's status AND publish the change event in one transaction (golden rule #6), so a
   * store closure can never be applied locally without other services (iam-svc) hearing about it.
   */
  public Store updateStoreStatusWithOutbox(
      UUID tenantId, UUID storeId, String status, OutboxRow event) {
    Instant now = Instant.now();
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE stores SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, now.atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, storeId);
            if (ps.executeUpdate() == 0) {
              throw ApiException.notFound("STORE_NOT_FOUND", "Store not found");
            }
          }
          insertOutbox(c, event);
          return null;
        },
        "update store status");
    return findStore(tenantId, storeId)
        .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND", "Store not found"));
  }

  public boolean hasDefaultStore(UUID tenantId) {
    return !query(
            "SELECT 1 FROM stores WHERE tenant_id = ? AND is_default = true LIMIT 1",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getInt(1),
            "check default store")
        .isEmpty();
  }

  // ──────────────────────────────────────────────────────── zone reads/writes

  /** Keyset page of a store's zones: rows strictly after the cursor in (created_at, id) order. */
  public List<Zone> listZones(
      UUID tenantId, UUID storeId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, name, code, type, status, created_at, updated_at"
                + " FROM zones WHERE tenant_id = ? AND store_id = ?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, storeId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapZone,
        "list zones page");
  }

  public Optional<Zone> findZone(UUID tenantId, UUID zoneId) {
    return query(
            "SELECT id, tenant_id, store_id, name, code, type, status, created_at, updated_at"
                + " FROM zones WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, zoneId);
            },
            TenantRepository::mapZone,
            "find zone")
        .stream()
        .findFirst();
  }

  public Zone updateZone(UUID tenantId, UUID zoneId, String name, String code, String type) {
    Instant now = Instant.now();
    exec(
        "UPDATE zones SET name = ?, code = ?, type = ?, updated_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, code);
          ps.setString(3, type);
          ps.setObject(4, now.atOffset(ZoneOffset.UTC));
          ps.setObject(5, tenantId);
          ps.setObject(6, zoneId);
        },
        "update zone");
    return findZone(tenantId, zoneId)
        .orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "Zone not found"));
  }

  public Zone updateZoneStatus(UUID tenantId, UUID zoneId, String status) {
    Instant now = Instant.now();
    exec(
        "UPDATE zones SET status = ?, updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, zoneId);
        },
        "update zone status");
    return findZone(tenantId, zoneId)
        .orElseThrow(() -> ApiException.notFound("ZONE_NOT_FOUND", "Zone not found"));
  }

  // ──────────────────────────────────────────────────────── staff reads/writes

  /** Keyset page of staff assignments: rows strictly after the cursor in (created_at, id) order. */
  public List<StaffAssignment> listStaff(
      UUID tenantId, Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, user_id, store_id, role, created_at"
                + " FROM staff_assignments WHERE tenant_id = ?");
    if (afterCreatedAt != null && afterId != null) sql.append(" AND (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapStaff,
        "list staff page");
  }

  public void removeStaff(UUID tenantId, UUID userId, UUID storeId) {
    exec(
        "DELETE FROM staff_assignments WHERE tenant_id = ? AND user_id = ? AND store_id = ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, userId);
          ps.setObject(3, storeId);
        },
        "remove staff");
  }

  // ─────────────────────────────────────────────────────────── inserts

  private void insertTenant(Connection c, Tenant t) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO tenants"
                + " (id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
                + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, t.id());
      ps.setString(2, t.name());
      ps.setString(3, t.legalName());
      ps.setString(4, t.status());
      ps.setObject(5, t.planId());
      ps.setObject(6, t.ownerUserId());
      ps.setString(7, t.country());
      ps.setString(8, t.currency());
      ps.setObject(9, t.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(10, t.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertStore(Connection c, Store s) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO stores"
                + " (id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices,"
                + " enabled_payment_methods, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, s.id());
      ps.setObject(2, s.tenantId());
      ps.setString(3, s.name());
      ps.setString(4, s.code());
      ps.setString(5, s.type());
      ps.setString(6, s.line1());
      ps.setString(7, s.line2());
      ps.setString(8, s.city());
      ps.setString(9, s.state());
      ps.setString(10, s.country());
      ps.setString(11, s.pincode());
      ps.setBigDecimal(12, s.geoLat());
      ps.setBigDecimal(13, s.geoLng());
      ps.setString(14, s.timezone());
      ps.setString(15, s.businessHours());
      ps.setString(16, s.status());
      ps.setBoolean(17, s.isDefault());
      ps.setBoolean(18, s.showPrices());
      ps.setString(19, s.enabledPaymentMethods());
      ps.setObject(20, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(21, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertZone(Connection c, Zone z) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO zones"
                + " (id, tenant_id, store_id, name, code, type, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, z.id());
      ps.setObject(2, z.tenantId());
      ps.setObject(3, z.storeId());
      ps.setString(4, z.name());
      ps.setString(5, z.code());
      ps.setString(6, z.type());
      ps.setString(7, z.status());
      ps.setObject(8, z.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(9, z.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void insertStaff(Connection c, StaffAssignment s) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO staff_assignments"
                + " (id, tenant_id, user_id, store_id, role, created_at)"
                + " VALUES (?,?,?,?,?,?)")) {
      ps.setObject(1, s.id());
      ps.setObject(2, s.tenantId());
      ps.setObject(3, s.userId());
      ps.setObject(4, s.storeId());
      ps.setString(5, s.role());
      ps.setObject(6, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void assertTenantActive(Connection c, UUID tenantId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT status FROM tenants WHERE id = ?")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("TENANT_NOT_FOUND", "Tenant does not exist");
        if (!Tenant.STATUS_ACTIVE.equals(rs.getString(1)))
          throw ApiException.unprocessable("TENANT_NOT_ACTIVE", "Tenant is not active");
      }
    }
  }

  // ─────────────────────────────────────────────────── single-arg query helpers

  private <T> Optional<T> one(String sql, UUID arg, RowMapper<T> mapper) {
    return query(sql, ps -> ps.setObject(1, arg), mapper, "query").stream().findFirst();
  }

  private <T> List<T> many(String sql, UUID arg, RowMapper<T> mapper) {
    return query(sql, ps -> ps.setObject(1, arg), mapper, "query list");
  }

  // ──────────────────────────────────────────────────────────────── row mappers

  private static Tenant mapTenant(ResultSet rs) throws SQLException {
    return new Tenant(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("legal_name"),
        rs.getString("status"),
        rs.getObject("plan_id", UUID.class),
        rs.getObject("owner_user_id", UUID.class),
        rs.getString("country"),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Store mapStore(ResultSet rs) throws SQLException {
    return new Store(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("code"),
        rs.getString("type"),
        rs.getString("line1"),
        rs.getString("line2"),
        rs.getString("city"),
        rs.getString("state"),
        rs.getString("country"),
        rs.getString("pincode"),
        rs.getBigDecimal("geo_lat"),
        rs.getBigDecimal("geo_lng"),
        rs.getString("timezone"),
        rs.getString("business_hours"),
        rs.getString("status"),
        rs.getBoolean("is_default"),
        rs.getBoolean("show_prices"),
        rs.getString("enabled_payment_methods"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Zone mapZone(ResultSet rs) throws SQLException {
    return new Zone(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("name"),
        rs.getString("code"),
        rs.getString("type"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static StaffAssignment mapStaff(ResultSet rs) throws SQLException {
    return new StaffAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("role"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Gap #53: Inventory org config ────────────────────────────────────────

  public TenantInventoryConfig upsertInventoryConfig(TenantInventoryConfig cfg) {
    return inTx(c -> upsertInventoryConfigTx(c, cfg), "upsert inventory config");
  }

  /**
   * Locks the tenant's config row (if any) for the duration of the transaction, lets {@code merge}
   * compute the new value from it, then atomically upserts the result — closing the race where two
   * concurrent partial updates each read the same stale snapshot and the second silently clobbers
   * fields the first one just set. {@code SELECT ... FOR UPDATE} serializes concurrent callers on
   * the same tenant_id; a first-ever insert for a tenant has no row to lock, but {@code ON
   * CONFLICT} already makes concurrent first-inserts safe on its own.
   */
  public TenantInventoryConfig upsertInventoryConfigMerged(
      UUID tenantId, java.util.function.UnaryOperator<TenantInventoryConfig> merge) {
    return inTx(
        c -> {
          TenantInventoryConfig existing = lockInventoryConfigForUpdate(c, tenantId);
          TenantInventoryConfig merged = merge.apply(existing);
          return upsertInventoryConfigTx(c, merged);
        },
        "upsert inventory config (merged)");
  }

  private TenantInventoryConfig lockInventoryConfigForUpdate(Connection c, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, lot_control_enabled, serial_control_enabled,"
                + " grade_control_enabled, expiry_tracking_enabled, costing_method,"
                + " default_uom, reorder_alert_enabled, auto_reserve_on_order,"
                + " created_at, updated_at"
                + " FROM tenant_inventory_config WHERE tenant_id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapInventoryConfig(rs) : null;
      }
    }
  }

  private TenantInventoryConfig upsertInventoryConfigTx(Connection c, TenantInventoryConfig cfg)
      throws SQLException {
    String sql =
        """
        INSERT INTO tenant_inventory_config
          (id, tenant_id, lot_control_enabled, serial_control_enabled,
           grade_control_enabled, expiry_tracking_enabled, costing_method,
           default_uom, reorder_alert_enabled, auto_reserve_on_order,
           created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (tenant_id) DO UPDATE SET
          lot_control_enabled     = EXCLUDED.lot_control_enabled,
          serial_control_enabled  = EXCLUDED.serial_control_enabled,
          grade_control_enabled   = EXCLUDED.grade_control_enabled,
          expiry_tracking_enabled = EXCLUDED.expiry_tracking_enabled,
          costing_method          = EXCLUDED.costing_method,
          default_uom             = EXCLUDED.default_uom,
          reorder_alert_enabled   = EXCLUDED.reorder_alert_enabled,
          auto_reserve_on_order   = EXCLUDED.auto_reserve_on_order,
          updated_at              = now()
        RETURNING *
        """;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, cfg.id());
      ps.setObject(2, cfg.tenantId());
      ps.setBoolean(3, cfg.lotControlEnabled());
      ps.setBoolean(4, cfg.serialControlEnabled());
      ps.setBoolean(5, cfg.gradeControlEnabled());
      ps.setBoolean(6, cfg.expiryTrackingEnabled());
      ps.setString(7, cfg.costingMethod());
      ps.setString(8, cfg.defaultUom());
      ps.setBoolean(9, cfg.reorderAlertEnabled());
      ps.setBoolean(10, cfg.autoReserveOnOrder());
      ps.setObject(11, OffsetDateTime.ofInstant(cfg.createdAt(), ZoneOffset.UTC));
      ps.setObject(12, OffsetDateTime.ofInstant(cfg.updatedAt(), ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("upsert inventory config returned no row");
        }
        return mapInventoryConfig(rs);
      }
    }
  }

  /**
   * Keyset page of every tenant on the platform (platform-admin), ordered by {@code (created_at,
   * id)} ascending. Was a flat unbounded scan of {@code tenants}; now paginated like {@link
   * #listStores(UUID, Instant, UUID, int)} so a growing platform doesn't turn this into an
   * ever-larger single response.
   */
  public List<Tenant> listAllTenants(Instant afterCreatedAt, UUID afterId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
                + " created_at, updated_at FROM tenants");
    if (afterCreatedAt != null && afterId != null) sql.append(" WHERE (created_at, id) > (?, ?)");
    sql.append(" ORDER BY created_at, id LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          if (afterCreatedAt != null && afterId != null) {
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
        },
        TenantRepository::mapTenant,
        "list all tenants page");
  }

  public Optional<TenantInventoryConfig> findInventoryConfig(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, lot_control_enabled, serial_control_enabled,"
                      + " grade_control_enabled, expiry_tracking_enabled, costing_method,"
                      + " default_uom, reorder_alert_enabled, auto_reserve_on_order,"
                      + " created_at, updated_at"
                      + " FROM tenant_inventory_config WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(mapInventoryConfig(rs)) : Optional.empty();
            }
          }
        },
        "find inventory config");
  }

  private static TenantInventoryConfig mapInventoryConfig(ResultSet rs) throws SQLException {
    return new TenantInventoryConfig(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getBoolean("lot_control_enabled"),
        rs.getBoolean("serial_control_enabled"),
        rs.getBoolean("grade_control_enabled"),
        rs.getBoolean("expiry_tracking_enabled"),
        rs.getString("costing_method"),
        rs.getString("default_uom"),
        rs.getBoolean("reorder_alert_enabled"),
        rs.getBoolean("auto_reserve_on_order"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  /**
   * Every tenant's declared currency, or just one when {@code scope} is given.
   *
   * <p>Tenants with no currency recorded are skipped in SQL rather than filtered in Java: they have
   * nothing to announce, and emitting an event with a null currency would only give the consumer
   * something to reject.
   *
   * @param scope a single tenant to read, or {@code null} for every tenant on the platform
   * @return one row per tenant that has a currency, ordered so a replay is reproducible
   */
  public List<TenantCurrency> findTenantCurrencies(UUID scope) {
    String sql =
        "SELECT id, currency FROM tenants WHERE currency IS NOT NULL"
            + (scope == null ? "" : " AND id = ?")
            + " ORDER BY created_at, id";
    return query(
        sql,
        ps -> {
          if (scope != null) ps.setObject(1, scope);
        },
        rs -> new TenantCurrency(rs.getObject("id", UUID.class), rs.getString("currency")),
        "list tenant currencies");
  }

  /**
   * Write a batch of events to the outbox in one transaction, so a replay either announces every
   * tenant or none of them. A partial replay is the worst outcome: it leaves some projections fixed
   * and some not, with nothing to say which.
   *
   * @param events the outbox rows to write; an empty list is a no-op
   * @return the number of rows written
   */
  public int publishEvents(List<OutboxRow> events) {
    if (events.isEmpty()) return 0;
    return inTx(
        c -> {
          for (OutboxRow event : events) insertOutbox(c, event);
          return events.size();
        },
        "publish event batch");
  }

  /**
   * Publish a single event to the outbox. Flow guard: used to emit role grants, status changes,
   * etc.
   */
  public void publishEvent(OutboxRow event) {
    inTx(
        c -> {
          insertOutbox(c, event);
          return null;
        },
        "publish event");
  }

  // ── delivery areas ─────────────────────────────────────────────────────────

  public DeliveryArea insertDeliveryArea(DeliveryArea a) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO delivery_areas (id, tenant_id, store_id, pincode, priority,"
                      + " created_at) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, a.id());
            ps.setObject(2, a.tenantId());
            ps.setObject(3, a.storeId());
            ps.setString(4, a.pincode());
            ps.setInt(5, a.priority());
            ps.setObject(6, a.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return null;
        },
        "insert delivery area");
    return a;
  }

  public List<DeliveryArea> listDeliveryAreas(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, pincode, priority, created_at FROM delivery_areas"
            + " WHERE tenant_id=? AND store_id=? ORDER BY priority ASC, pincode ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        TenantRepository::mapDeliveryArea,
        "list delivery areas");
  }

  public boolean deleteDeliveryArea(UUID tenantId, UUID storeId, UUID areaId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM delivery_areas WHERE tenant_id=? AND store_id=? AND id=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, areaId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete delivery area");
  }

  /**
   * Lowest-priority (highest precedence) store covering the pincode, or empty if none mapped. When
   * no delivery_areas exist for the tenant at all, callers should fall back to the default store.
   */
  public Optional<DeliveryArea> resolveDeliveryArea(UUID tenantId, String pincode) {
    List<DeliveryArea> rows =
        query(
            "SELECT id, tenant_id, store_id, pincode, priority, created_at FROM delivery_areas"
                + " WHERE tenant_id=? AND lower(pincode)=lower(?) ORDER BY priority ASC LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, pincode.trim());
            },
            TenantRepository::mapDeliveryArea,
            "resolve delivery area");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public boolean hasAnyDeliveryAreas(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT 1 FROM delivery_areas WHERE tenant_id=? LIMIT 1")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next();
            }
          }
        },
        "has any delivery areas");
  }

  private static DeliveryArea mapDeliveryArea(ResultSet rs) throws SQLException {
    return new DeliveryArea(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("pincode"),
        rs.getInt("priority"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
