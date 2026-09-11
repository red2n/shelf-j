package com.shelfj.notification.repo;

import com.shelfj.notification.domain.Domain.NotificationLog;
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

  /**
   * Insert a shortage alert, deduped on its eventId: the processed_events mark and the insert
   * commit in ONE transaction so a redelivered event is skipped and a crashed write is retried —
   * never duplicated and never lost. Returns false if the event was already processed.
   */
  public boolean insertAlertOnce(String consumerName, ShortageAlert alert) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, alert.eventId(), consumerName)) {
            return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO shortage_alerts"
                      + " (id, tenant_id, store_id, variant_id, available, threshold, event_id)"
                      + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, alert.id());
            ps.setObject(2, alert.tenantId());
            ps.setObject(3, alert.storeId());
            ps.setObject(4, alert.variantId());
            ps.setBigDecimal(5, alert.available());
            ps.setBigDecimal(6, alert.threshold());
            ps.setObject(7, alert.eventId());
            ps.executeUpdate();
          }
          return true;
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

  // ── Outbound notification log (N1) ────────────────────────────────────────

  /** True if this event already produced a notification of this type (send-once guard). */
  public boolean alreadyNotified(UUID eventId, String type) {
    return !query(
            "SELECT 1 FROM notification_log WHERE event_id = ? AND type = ?",
            ps -> {
              ps.setObject(1, eventId);
              ps.setString(2, type);
            },
            rs -> Boolean.TRUE,
            "check notification log")
        .isEmpty();
  }

  /**
   * Record a delivered notification. {@code ON CONFLICT (event_id, type) DO NOTHING} makes a
   * concurrent/redelivered send a no-op even if the {@link #alreadyNotified} pre-check raced.
   */
  public void recordNotification(
      UUID tenantId,
      UUID subjectId,
      UUID eventId,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status) {
    exec(
        "INSERT INTO notification_log"
            + " (id, tenant_id, subject_id, event_id, type, channel, recipient, subject, body,"
            + " status)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (event_id, type) DO NOTHING",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, tenantId);
          ps.setObject(3, subjectId);
          ps.setObject(4, eventId);
          ps.setString(5, type);
          ps.setString(6, channel);
          ps.setString(7, recipient);
          ps.setString(8, subject);
          ps.setString(9, body);
          ps.setString(10, status);
        },
        "record notification");
  }

  /**
   * Erases the messages one shop sent about one customer (SJ-D43). The row stays, so the send is
   * still accounted for; who it went to and what it said do not. Idempotent: a redacted row is not
   * touched again.
   */
  public int redactForCustomer(UUID tenantId, UUID customerId) {
    return redact(
        "UPDATE notification_log SET recipient = '[erased]', subject = '[erased]', body = '',"
            + " redacted_at = now()"
            + " WHERE tenant_id = ? AND subject_id = ? AND redacted_at IS NULL",
        tenantId,
        customerId);
  }

  /**
   * Erases the platform's own messages about a deleted account — the ones sent with no shop, such
   * as WELCOME. Messages a shop sent stay with that shop, which erases them itself.
   */
  public int redactForAccount(UUID userId) {
    return redact(
        "UPDATE notification_log SET recipient = '[erased]', subject = '[erased]', body = '',"
            + " redacted_at = now()"
            + " WHERE tenant_id IS NULL AND subject_id = ? AND redacted_at IS NULL",
        userId);
  }

  private int redact(String sql, UUID... params) {
    return inTx(
        c -> {
          try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
              ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
          }
        },
        "redact notifications");
  }

  /** Recent in-app notifications for a tenant, newest first, optionally filtered by recipient. */
  public List<NotificationLog> listRecent(UUID tenantId, String recipient, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, event_id, type, channel, recipient, subject, body, status,"
                + " created_at FROM notification_log WHERE tenant_id = ?");
    if (recipient != null) sb.append(" AND recipient = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (recipient != null) {
            ps.setString(2, recipient);
            ps.setInt(3, limit);
          } else {
            ps.setInt(2, limit);
          }
        },
        NotificationRepository::mapNotification,
        "list notifications");
  }

  private static NotificationLog mapNotification(ResultSet rs) throws SQLException {
    return new NotificationLog(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getString("type"),
        rs.getString("channel"),
        rs.getString("recipient"),
        rs.getString("subject"),
        rs.getString("body"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
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
