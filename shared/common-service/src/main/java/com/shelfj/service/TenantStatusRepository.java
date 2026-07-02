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

  public void upsertTenantStatus(UUID tenantId, String status, Instant changedAt) {
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
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("upsert tenant status", e);
    }
  }

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
