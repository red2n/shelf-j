package com.shelfj.tenant.repo;

import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.tenant.domain.Domain.StaffAssignment;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.StoreWithZone;
import com.shelfj.tenant.domain.Domain.Tenant;
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
            + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices, created_at, updated_at"
            + " FROM stores WHERE tenant_id = ? ORDER BY created_at",
        tenantId,
        TenantRepository::mapStore);
  }

  public Optional<Store> findStore(UUID tenantId, UUID storeId) {
    return query(
            "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode,"
                + " geo_lat, geo_lng, timezone, business_hours, status, is_default, show_prices, created_at, updated_at"
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
      boolean showPrices) {
    Instant now = Instant.now();
    exec(
        "UPDATE stores SET name=?, line1=?, line2=?, city=?, state=?, country=?, pincode=?,"
            + " geo_lat=?, geo_lng=?, timezone=?, business_hours=?, show_prices=?, updated_at=?"
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
          ps.setObject(13, now.atOffset(ZoneOffset.UTC));
          ps.setObject(14, tenantId);
          ps.setObject(15, storeId);
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

  public boolean hasDefaultStore(UUID tenantId) {
    return !query(
            "SELECT 1 FROM stores WHERE tenant_id = ? AND is_default = true LIMIT 1",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getInt(1),
            "check default store")
        .isEmpty();
  }

  // ──────────────────────────────────────────────────────── zone reads/writes

  public List<Zone> listZones(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, name, code, type, status, created_at, updated_at"
            + " FROM zones WHERE tenant_id = ? AND store_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        TenantRepository::mapZone,
        "list zones");
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

  public List<StaffAssignment> listStaff(UUID tenantId) {
    return many(
        "SELECT id, tenant_id, user_id, store_id, role, created_at"
            + " FROM staff_assignments WHERE tenant_id = ? ORDER BY created_at",
        tenantId,
        TenantRepository::mapStaff);
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
                + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
      ps.setObject(19, s.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(20, s.createdAt().atOffset(ZoneOffset.UTC));
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
    return inTx(
        c -> {
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
              rs.next();
              return mapInventoryConfig(rs);
            }
          }
        },
        "upsert inventory config");
  }

  public List<Tenant> listAllTenants() {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency,"
                      + " created_at, updated_at FROM tenants ORDER BY created_at DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
              List<Tenant> result = new java.util.ArrayList<>();
              while (rs.next()) result.add(mapTenant(rs));
              return result;
            }
          }
        },
        "list all tenants");
  }

  public Optional<TenantInventoryConfig> findInventoryConfig(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM tenant_inventory_config WHERE tenant_id = ?")) {
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
}
