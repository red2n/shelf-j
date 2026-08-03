package com.shelfj.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Local projection of store operational status, fed by {@code StoreStatusChanged} Kafka events.
 * Services inject this to gate operations without a synchronous call to tenant-svc. Fail-open: a
 * missing row is treated as ACTIVE.
 */
@ApplicationScoped
public class StoreStatusRepository extends BaseJdbcRepository {

  /**
   * Applies the projection write and reports whether it actually took effect. The {@code WHERE}
   * clause silently no-ops a stale/out-of-order event (an older {@code changedAt} than what's
   * already stored), which callers need to know — logging "updated" regardless would misrepresent
   * the actual projection state after a reordered redelivery.
   *
   * @param storeId the store whose status changed
   * @param tenantId the tenant that owns {@code storeId}
   * @param status the new status, e.g. {@code "ACTIVE"}, {@code "CLOSED"}
   * @param changedAt when the status change occurred, per the source event's {@code occurredAt}
   * @return {@code true} if the row was inserted or updated; {@code false} if an existing, newer
   *     row was left untouched.
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean upsertStoreStatus(UUID storeId, UUID tenantId, String status, Instant changedAt) {
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
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("upsert store status", e);
    }
  }

  /**
   * True if {@code storeId} belongs to {@code tenantId} and is active.
   *
   * <p>Fail-open applies only when this store has never been projected here at all (a Kafka
   * ordering race — the caller's own write can race this table's consumer). A store that HAS been
   * projected under a <em>different</em> tenant is a cross-tenant reference, not a race, and must
   * never pass regardless of its status: without this check, a caller could name any active store
   * UUID from any tenant and this method would confirm it, since {@code store_id} alone (the old
   * signature) can't tell a wrong-tenant store apart from one this projection just hasn't heard
   * about yet.
   *
   * @param tenantId the tenant the caller expects to own {@code storeId}
   * @param storeId the store to check
   * @return {@code true} if the projection has no row for {@code storeId} (fail-open), or the row
   *     exists under {@code tenantId} with status {@code "ACTIVE"} (case-insensitive); {@code
   *     false} if the row belongs to a different tenant or has a non-active status
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean isActive(UUID tenantId, UUID storeId) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement("SELECT tenant_id, status FROM store_status WHERE store_id = ?")) {
      ps.setObject(1, storeId);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) return true;
        if (!tenantId.equals(rs.getObject("tenant_id", UUID.class))) return false;
        return "ACTIVE".equalsIgnoreCase(rs.getString("status"));
      }
    } catch (SQLException e) {
      throw dbError("check store status", e);
    }
  }
}
