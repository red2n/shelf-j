package com.shelfj.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Local projection of tenant operational status, fed by {@code TenantStatusChanged} Kafka events.
 * Services inject this to gate operations without a synchronous call to tenant-svc. Fail-open: a
 * missing row is treated as ACTIVE.
 */
@ApplicationScoped
public class TenantStatusRepository extends BaseJdbcRepository {

  /**
   * Applies the projection write and reports whether it actually took effect. The {@code WHERE}
   * clause silently no-ops a stale/out-of-order event (an older {@code changedAt} than what's
   * already stored), which callers need to know — logging "updated" regardless would misrepresent
   * the actual projection state after a reordered redelivery.
   *
   * @param tenantId the tenant whose status changed
   * @param status the new status, e.g. {@code "ACTIVE"}, {@code "SUSPENDED"}
   * @param changedAt when the status change occurred, per the source event's {@code occurredAt}
   * @return {@code true} if the row was inserted or updated; {@code false} if an existing, newer
   *     row was left untouched.
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean upsertTenantStatus(UUID tenantId, String status, Instant changedAt) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO tenant_status (tenant_id, status, status_changed_at)"
                    + " VALUES (?,?,?)"
                    + " ON CONFLICT (tenant_id) DO UPDATE SET"
                    + "   status = EXCLUDED.status,"
                    + "   status_changed_at = EXCLUDED.status_changed_at"
                    + " WHERE EXCLUDED.status_changed_at >= tenant_status.status_changed_at")) {
      ps.setObject(1, tenantId);
      ps.setString(2, status);
      ps.setObject(3, changedAt.atOffset(ZoneOffset.UTC));
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("upsert tenant status", e);
    }
  }

  /**
   * @param tenantId the tenant to check
   * @return {@code true} if the projection has no row for {@code tenantId} (fail-open) or the
   *     stored status is {@code "ACTIVE"} (case-insensitive); {@code false} otherwise
   * @throws com.shelfj.web.ApiException 500 {@code DB_ERROR} on any {@link SQLException}
   */
  public boolean isActive(UUID tenantId) {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement("SELECT status FROM tenant_status WHERE tenant_id = ?")) {
      ps.setObject(1, tenantId);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) return true;
        return "ACTIVE".equalsIgnoreCase(rs.getString("status"));
      }
    } catch (SQLException e) {
      throw dbError("check tenant status", e);
    }
  }
}
