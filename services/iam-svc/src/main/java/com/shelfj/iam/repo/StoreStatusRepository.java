package com.shelfj.iam.repo;

import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Local projection of store operational status, fed by {@code StoreStatusChanged} Kafka events.
 * Used by {@link com.shelfj.iam.service.PosSessionService} to guard clock-in without a synchronous
 * call to tenant-svc.
 *
 * <p>Fail-open: a missing row means ACTIVE. This preserves back-compatibility for stores created
 * before the projection existed and keeps the POS available during a brief event-delivery lag.
 */
@ApplicationScoped
public class StoreStatusRepository extends BaseJdbcRepository {

  /**
   * Upsert the projection. Guarded by {@code status_changed_at} so a stale or out-of-order event
   * can never overwrite a newer status.
   */
  public void upsertStoreStatus(UUID storeId, UUID tenantId, String status, Instant changedAt) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO store_status (store_id, tenant_id, status, status_changed_at)"
                    + " VALUES (?,?,?,?)"
                    + " ON CONFLICT (store_id) DO UPDATE SET"
                    + "   status = EXCLUDED.status,"
                    + "   status_changed_at = EXCLUDED.status_changed_at"
                    + " WHERE EXCLUDED.status_changed_at >= store_status.status_changed_at")) {
      ps.setObject(1, storeId);
      ps.setObject(2, tenantId);
      ps.setString(3, status);
      ps.setObject(4, changedAt.atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("upsert store status", e);
    }
  }

  /**
   * Whether the store is accepting new POS sessions. Returns {@code true} when the store's status
   * is ACTIVE or when no projection row exists yet (fail-open).
   */
  public boolean isActive(UUID storeId) {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement("SELECT status FROM store_status WHERE store_id = ?")) {
      ps.setObject(1, storeId);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) return true; // no row → fail-open
        return "ACTIVE".equalsIgnoreCase(rs.getString("status"));
      }
    } catch (SQLException e) {
      throw dbError("check store status", e);
    }
  }
}
