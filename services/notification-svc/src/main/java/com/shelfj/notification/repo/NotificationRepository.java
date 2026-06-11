package com.shelfj.notification.repo;

import com.shelfj.notification.domain.Domain.ShortageAlert;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class NotificationRepository extends BaseJdbcRepository {

  /** Returns true if the event was inserted (new); false if already processed (duplicate). */
  public boolean markProcessedIfNew(UUID eventId, String consumer) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO processed_events (event_id, consumer) VALUES (?,?)"
                    + " ON CONFLICT (event_id) DO NOTHING")) {
      ps.setObject(1, eventId);
      ps.setString(2, consumer);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("mark processed event", e);
    }
  }

  public ShortageAlert insertAlert(ShortageAlert alert) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO shortage_alerts"
                      + " (id, tenant_id, store_id, variant_id, available, threshold, event_id)"
                      + " VALUES (?,?,?,?,?,?,?)"
                      + " RETURNING id, tenant_id, store_id, variant_id, available, threshold,"
                      + " event_id, alerted_at")) {
            ps.setObject(1, alert.id());
            ps.setObject(2, alert.tenantId());
            ps.setObject(3, alert.storeId());
            ps.setObject(4, alert.variantId());
            ps.setBigDecimal(5, alert.available());
            ps.setBigDecimal(6, alert.threshold());
            ps.setObject(7, alert.eventId());
            try (var rs = ps.executeQuery()) {
              rs.next();
              return mapAlert(rs);
            }
          }
        },
        "insert shortage alert");
  }

  public List<ShortageAlert> listAlerts(UUID tenantId, UUID storeId, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, available, threshold, event_id, alerted_at"
                + " FROM shortage_alerts WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    sb.append(" ORDER BY alerted_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
          ps.setInt(storeId != null ? 3 : 2, limit);
        },
        NotificationRepository::mapAlert,
        "list shortage alerts");
  }

  public List<ShortageAlert> listAlertsByVariant(UUID tenantId, UUID variantId, int limit) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, available, threshold, event_id, alerted_at"
            + " FROM shortage_alerts WHERE tenant_id = ? AND variant_id = ?"
            + " ORDER BY alerted_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
          ps.setInt(3, limit);
        },
        NotificationRepository::mapAlert,
        "list shortage alerts by variant");
  }

  private static ShortageAlert mapAlert(ResultSet rs) throws SQLException {
    return new ShortageAlert(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("available"),
        rs.getBigDecimal("threshold"),
        rs.getObject("event_id", UUID.class),
        rs.getObject("alerted_at", OffsetDateTime.class).toInstant());
  }
}
