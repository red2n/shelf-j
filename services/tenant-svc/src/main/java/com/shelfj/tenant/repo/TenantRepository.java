package com.shelfj.tenant.repo;

import com.shelfj.service.OutboxStore;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.StoreWithZone;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.Zone;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Persistence for tenants/stores/zones/staff + outbox. Multi-row writes that must be atomic with
 * their events (create tenant, create store+default-zone) run in one transaction with the outbox
 * insert (golden rule #6). Every store/zone/staff query filters tenant_id FIRST (golden rule #3).
 * Implements {@link OutboxStore}.
 */
@ApplicationScoped
public class TenantRepository implements OutboxStore {

  @Inject DataSource dataSource;

  public record OutboxRow(
      String eventType, String topic, UUID tenantId, UUID aggregateId, String payload) {}

  // --- create tenant + TenantCreated outbox (atomic) ---
  public Tenant createTenantWithOutbox(Tenant t, OutboxRow event) {
    return inTx(
        c -> {
          insertTenant(c, t);
          insertOutbox(c, event);
          return t;
        },
        "create tenant");
  }

  // --- create store + DEFAULT zone + StoreCreated + ZoneCreated (atomic) ---

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

  public void createStaffWithOutbox(
      com.shelfj.tenant.domain.Domain.StaffAssignment s, OutboxRow event) {
    inTx(
        c -> {
          insertStaff(c, s);
          insertOutbox(c, event);
          return null;
        },
        "assign staff");
  }

  // --- lookups (tenant-scoped) ---

  public Optional<Tenant> findTenant(UUID tenantId) {
    return one(
        "SELECT id, name, legal_name, status, plan_id, owner_user_id, country, currency, created_at FROM tenants WHERE id = ?",
        tenantId,
        TenantRepository::mapTenant);
  }

  public List<Store> listStores(UUID tenantId) {
    return many(
        "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode, geo_lat, geo_lng, timezone, business_hours, status, is_default, created_at FROM stores WHERE tenant_id = ? ORDER BY created_at",
        tenantId,
        TenantRepository::mapStore);
  }

  public Optional<Store> findStore(UUID tenantId, UUID storeId) {
    String sql =
        "SELECT id, tenant_id, name, code, type, line1, line2, city, state, country, pincode, geo_lat, geo_lng, timezone, business_hours, status, is_default, created_at FROM stores WHERE tenant_id = ? AND id = ?";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapStore(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw dbError("find store", e);
    }
  }

  public List<Zone> listZones(UUID tenantId, UUID storeId) {
    String sql =
        "SELECT id, tenant_id, store_id, name, code, type, status, created_at FROM zones WHERE tenant_id = ? AND store_id = ? ORDER BY created_at";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      try (ResultSet rs = ps.executeQuery()) {
        List<Zone> out = new ArrayList<>();
        while (rs.next()) out.add(mapZone(rs));
        return out;
      }
    } catch (SQLException e) {
      throw dbError("list zones", e);
    }
  }

  public boolean hasDefaultStore(UUID tenantId) {
    String sql = "SELECT 1 FROM stores WHERE tenant_id = ? AND is_default = true LIMIT 1";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw dbError("check default store", e);
    }
  }

  // --- inserts ---

  private void insertTenant(Connection c, Tenant t) throws SQLException {
    String sql =
        "INSERT INTO tenants (id, name, legal_name, status, plan_id, owner_user_id, country, currency, created_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, t.id());
      ps.setString(2, t.name());
      ps.setString(3, t.legalName());
      ps.setString(4, t.status());
      ps.setObject(5, t.planId());
      ps.setObject(6, t.ownerUserId());
      ps.setString(7, t.country());
      ps.setString(8, t.currency());
      ps.setTimestamp(9, Timestamp.from(t.createdAt()));
      ps.executeUpdate();
    }
  }

  private void insertStore(Connection c, Store s) throws SQLException {
    String sql =
        "INSERT INTO stores (id, tenant_id, name, code, type, line1, line2, city, state, country, pincode, "
            + "geo_lat, geo_lng, timezone, business_hours, status, is_default, created_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
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
      ps.setTimestamp(18, Timestamp.from(s.createdAt()));
      ps.executeUpdate();
    }
  }

  private void insertZone(Connection c, Zone z) throws SQLException {
    String sql =
        "INSERT INTO zones (id, tenant_id, store_id, name, code, type, status, created_at) VALUES (?,?,?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, z.id());
      ps.setObject(2, z.tenantId());
      ps.setObject(3, z.storeId());
      ps.setString(4, z.name());
      ps.setString(5, z.code());
      ps.setString(6, z.type());
      ps.setString(7, z.status());
      ps.setTimestamp(8, Timestamp.from(z.createdAt()));
      ps.executeUpdate();
    }
  }

  private void insertStaff(Connection c, com.shelfj.tenant.domain.Domain.StaffAssignment s)
      throws SQLException {
    String sql =
        "INSERT INTO staff_assignments (id, tenant_id, user_id, store_id, role, created_at) VALUES (?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, s.id());
      ps.setObject(2, s.tenantId());
      ps.setObject(3, s.userId());
      ps.setObject(4, s.storeId());
      ps.setString(5, s.role());
      ps.setTimestamp(6, Timestamp.from(s.createdAt()));
      ps.executeUpdate();
    }
  }

  private void insertOutbox(Connection c, OutboxRow o) throws SQLException {
    String sql =
        "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload) VALUES (?,?,?,?,?,?)";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, o.eventType());
      ps.setString(3, o.topic());
      ps.setObject(4, o.tenantId());
      ps.setObject(5, o.aggregateId());
      ps.setString(6, o.payload());
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

  // --- outbox drain (OutboxStore) ---
  @Override
  public List<PendingOutbox> pendingOutbox(int limit) {
    String sql =
        "SELECT id, topic, payload FROM outbox WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?";
    List<PendingOutbox> out = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next())
          out.add(
              new PendingOutbox(
                  rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload")));
      }
      return out;
    } catch (SQLException e) {
      throw dbError("read outbox", e);
    }
  }

  @Override
  public void markPublished(UUID id) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ?")) {
      ps.setObject(1, id);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("mark outbox published", e);
    }
  }

  // --- tx + mapping helpers ---

  private interface TxWork<R> {
    R run(Connection c) throws SQLException;
  }

  private <R> R inTx(TxWork<R> work, String what) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        R result = work.run(c);
        c.commit();
        return result;
      } catch (SQLException e) {
        c.rollback();
        if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
          throw new ApiException(
              409, "CODE_ALREADY_EXISTS", "A record with that code already exists", List.of(), e);
        }
        throw dbError(what, e);
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw dbError(what + " (connection)", e);
    }
  }

  private <T> Optional<T> one(String sql, UUID arg, RowMapper<T> mapper) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw dbError("query", e);
    }
  }

  private <T> List<T> many(String sql, UUID arg, RowMapper<T> mapper) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        List<T> out = new ArrayList<>();
        while (rs.next()) out.add(mapper.map(rs));
        return out;
      }
    } catch (SQLException e) {
      throw dbError("query list", e);
    }
  }

  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

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
        rs.getTimestamp("created_at").toInstant());
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
        rs.getTimestamp("created_at").toInstant());
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
        rs.getTimestamp("created_at").toInstant());
  }

  private static final String UNIQUE_VIOLATION = "23505";

  private static ApiException dbError(String what, Throwable cause) {
    return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of(), cause);
  }
}
