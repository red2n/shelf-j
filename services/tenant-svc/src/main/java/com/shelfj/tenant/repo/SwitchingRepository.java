package com.shelfj.tenant.repo;

import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.tenant.domain.Switching.Dates;
import com.shelfj.tenant.domain.Switching.Evidence;
import com.shelfj.tenant.domain.Switching.Switch;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC persistence for a business leaving (21.14): its notice, the erasure that follows, and what
 * every service erased. Every query on a business's rows filters by tenant_id first; the sweep for
 * erasures due reads across businesses, as the platform's own work.
 */
@ApplicationScoped
public class SwitchingRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "s.id, s.tenant_id, s.intent, s.notice_given_at, s.notice_given_by, s.notice_ends_on,"
          + " s.transition_ends_on, s.extended_at, s.extended_by, s.retrieval_ends_on,"
          + " s.erasure_due_on, s.cancelled_at, s.cancelled_by, s.cancel_reason,"
          + " s.erasure_event_id, s.erasure_started_at";

  /** The business's latest notice, standing or not. */
  public Optional<Switch> latest(UUID tenantId) {
    List<Switch> rows =
        query(
            "SELECT "
                + COLUMNS
                + " FROM tenant_switches s WHERE s.tenant_id = ?"
                + " ORDER BY s.notice_given_at DESC, s.id DESC LIMIT 1",
            ps -> ps.setObject(1, tenantId),
            SwitchingRepository::switchOf,
            "read notice");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Records a notice.
   *
   * @throws ApiException 409 {@code SWITCHING_NOTICE_ALREADY_GIVEN} while another stands
   */
  public void insert(Switch s) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO tenant_switches (id, tenant_id, intent, notice_given_by,"
                      + " notice_ends_on, transition_ends_on, retrieval_ends_on, erasure_due_on)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setString(3, s.intent());
            ps.setObject(4, s.noticeGivenBy());
            ps.setObject(5, s.noticeEndsOn());
            ps.setObject(6, s.transitionEndsOn());
            ps.setObject(7, s.retrievalEndsOn());
            ps.setObject(8, s.erasureDueOn());
            return ps.executeUpdate();
          }
        },
        "give notice");
  }

  /**
   * Extends the transitional period, once.
   *
   * @return {@code false} when it was extended, withdrawn or erased meanwhile
   */
  public boolean extend(UUID tenantId, UUID id, Dates d, UUID by) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenant_switches SET transition_ends_on = ?, retrieval_ends_on = ?,"
                      + " erasure_due_on = ?, extended_at = now(), extended_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND extended_at IS NULL"
                      + " AND cancelled_at IS NULL AND erasure_started_at IS NULL")) {
            ps.setObject(1, d.transitionEndsOn());
            ps.setObject(2, d.retrievalEndsOn());
            ps.setObject(3, d.erasureDueOn());
            ps.setObject(4, by);
            ps.setObject(5, tenantId);
            ps.setObject(6, id);
            return ps.executeUpdate() == 1;
          }
        },
        "extend notice");
  }

  /**
   * Withdraws a notice.
   *
   * @return {@code false} when it was withdrawn or erased meanwhile
   */
  public boolean cancel(UUID tenantId, UUID id, UUID by, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenant_switches SET cancelled_at = now(), cancelled_by = ?,"
                      + " cancel_reason = ? WHERE tenant_id = ? AND id = ?"
                      + " AND cancelled_at IS NULL AND erasure_started_at IS NULL")) {
            ps.setObject(1, by);
            ps.setString(2, reason);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps.executeUpdate() == 1;
          }
        },
        "withdraw notice");
  }

  /** Every notice whose erasure falls due by the day and has not started, across businesses. */
  public List<Switch> due(LocalDate today) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM tenant_switches s WHERE s.cancelled_at IS NULL"
            + " AND s.erasure_started_at IS NULL AND s.erasure_due_on <= ?"
            + " ORDER BY s.erasure_due_on, s.id",
        ps -> ps.setObject(1, today),
        SwitchingRepository::switchOf,
        "find erasures due");
  }

  /**
   * Starts a business's erasure in one transaction: the notice marked with the erasure's event, the
   * business made inactive, and both announced.
   *
   * @return {@code false} when another sweep started it, or the notice was withdrawn, first
   */
  public boolean startErasure(
      Switch s, UUID erasureEventId, String inactive, OutboxRow statusChanged, OutboxRow due) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenant_switches SET erasure_event_id = ?, erasure_started_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND cancelled_at IS NULL"
                      + " AND erasure_started_at IS NULL")) {
            ps.setObject(1, erasureEventId);
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.id());
            if (ps.executeUpdate() != 1) return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenants SET status = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, inactive);
            ps.setObject(2, s.tenantId());
            ps.executeUpdate();
          }
          insertOutbox(c, statusChanged);
          insertOutbox(c, due);
          return true;
        },
        "start erasure");
  }

  /** The notice an erasure event was started for. */
  public Optional<Switch> byErasureEvent(UUID tenantId, UUID erasureEventId) {
    List<Switch> rows =
        query(
            "SELECT "
                + COLUMNS
                + " FROM tenant_switches s WHERE s.tenant_id = ? AND s.erasure_event_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, erasureEventId);
            },
            SwitchingRepository::switchOf,
            "find erasure");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Records what a service erased, once per announcement.
   *
   * @return whether it was recorded now
   */
  public boolean recordEvidence(UUID tenantId, UUID switchId, Evidence e) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO tenant_erasure_evidence (id, tenant_id, switch_id,"
                      + " erasure_event_id, service, rows_erased, tables, erased_at)"
                      + " VALUES (?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, e.id());
            ps.setObject(2, tenantId);
            ps.setObject(3, switchId);
            ps.setObject(4, e.erasureEventId());
            ps.setString(5, e.service());
            ps.setInt(6, e.rowsErased());
            ps.setString(7, e.tables());
            ps.setObject(8, e.erasedAt().atOffset(java.time.ZoneOffset.UTC));
            return ps.executeUpdate() == 1;
          }
        },
        "record erasure evidence");
  }

  /** What each service erased for a notice, by service. */
  public List<Evidence> evidence(UUID tenantId, UUID switchId) {
    return query(
        "SELECT e.id, e.erasure_event_id, e.service, e.rows_erased, e.tables::text AS tables,"
            + " e.erased_at, e.recorded_at FROM tenant_erasure_evidence e"
            + " WHERE e.tenant_id = ? AND e.switch_id = ? ORDER BY e.service, e.recorded_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, switchId);
        },
        rs ->
            new Evidence(
                rs.getObject("id", UUID.class),
                rs.getObject("erasure_event_id", UUID.class),
                rs.getString("service"),
                rs.getInt("rows_erased"),
                rs.getString("tables"),
                instant(rs, "erased_at"),
                instant(rs, "recorded_at")),
        "read erasure evidence");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new ApiException(
          409,
          "SWITCHING_NOTICE_ALREADY_GIVEN",
          "a notice already stands; withdraw it before giving another",
          List.of(),
          e);
    }
    return super.handleTxSqlException(what, e);
  }

  private static Switch switchOf(ResultSet rs) throws SQLException {
    return new Switch(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("intent"),
        instant(rs, "notice_given_at"),
        rs.getObject("notice_given_by", UUID.class),
        rs.getObject("notice_ends_on", LocalDate.class),
        rs.getObject("transition_ends_on", LocalDate.class),
        instant(rs, "extended_at"),
        rs.getObject("extended_by", UUID.class),
        rs.getObject("retrieval_ends_on", LocalDate.class),
        rs.getObject("erasure_due_on", LocalDate.class),
        instant(rs, "cancelled_at"),
        rs.getObject("cancelled_by", UUID.class),
        rs.getString("cancel_reason"),
        rs.getObject("erasure_event_id", UUID.class),
        instant(rs, "erasure_started_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
