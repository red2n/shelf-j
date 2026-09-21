package com.storeql.tenant.repo;

import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Retention.DataClass;
import com.storeql.tenant.domain.Retention.Floor;
import com.storeql.tenant.domain.Retention.Hold;
import com.storeql.tenant.domain.Retention.Run;
import com.storeql.tenant.domain.Retention.Schedule;
import com.storeql.tenant.domain.Retention.SubjectKind;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Retention reference data, the business's schedule, its holds and the register of runs (21.16).
 * Schedules and runs are append-only; a hold's release is the one thing written in place.
 */
@ApplicationScoped
public class RetentionRepository extends BaseJdbcRepository {

  private static final String HOLD_COLUMNS =
      "h.id, h.tenant_id, h.data_class, h.subject_kind, h.subject_id, h.reason, h.placed_by,"
          + " h.placed_at, h.released_by, h.released_at, h.release_reason";

  /** Every class a schedule governs, in code order. */
  public List<DataClass> classes() {
    return query(
        "SELECT c.code, c.name, c.purge_kind, c.purged_by, c.description FROM retention_classes c"
            + " ORDER BY c.code",
        ps -> {},
        rs ->
            new DataClass(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("purge_kind"),
                rs.getString("purged_by"),
                rs.getString("description")),
        "retention classes");
  }

  /**
   * Every floor that reaches any of the countries: a country's own, and a regime's for a country
   * that is a member today.
   */
  public List<Floor> floorsFor(Collection<String> countries) {
    if (countries.isEmpty()) {
      return List.of();
    }
    return query(
        "SELECT f.data_class, f.scope, f.min_days, f.citation, f.summary FROM retention_floors f"
            + " WHERE (f.scope_kind = 'COUNTRY' AND f.scope = ANY (?))"
            + "    OR (f.scope_kind = 'REGIME' AND EXISTS (SELECT 1 FROM jurisdiction_members m"
            + "          WHERE m.regime_code = f.scope AND m.country = ANY (?)"
            + "          AND m.member_from <= CURRENT_DATE"
            + "          AND (m.member_to IS NULL OR m.member_to >= CURRENT_DATE)))"
            + " ORDER BY f.data_class, f.min_days DESC",
        ps -> {
          var array = ps.getConnection().createArrayOf("text", countries.toArray());
          ps.setArray(1, array);
          ps.setArray(2, array);
        },
        rs ->
            new Floor(
                rs.getString("data_class"),
                rs.getString("scope"),
                rs.getInt("min_days"),
                rs.getString("citation"),
                rs.getString("summary")),
        "retention floors");
  }

  /** The period in force per class: the latest decision on each. */
  public List<Schedule> latestSchedules(UUID tenantId) {
    return query(
        "SELECT DISTINCT ON (s.data_class) s.id, s.tenant_id, s.data_class, s.period_days,"
            + " s.set_by, s.set_at FROM retention_schedules s WHERE s.tenant_id = ?"
            + " ORDER BY s.data_class, s.set_at DESC, s.id DESC",
        ps -> ps.setObject(1, tenantId),
        RetentionRepository::mapSchedule,
        "retention schedule");
  }

  public Schedule insertSchedule(Schedule s) {
    exec(
        "INSERT INTO retention_schedules (id, tenant_id, data_class, period_days, set_by, set_at)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, s.id());
          ps.setObject(2, s.tenantId());
          ps.setString(3, s.dataClass());
          ps.setInt(4, s.periodDays());
          ps.setObject(5, s.setBy());
          ps.setObject(6, utc(s.setAt()));
        },
        "set retention period");
    return s;
  }

  /** How many decisions the business has recorded on a class: its history, never rewritten. */
  public int scheduleHistory(UUID tenantId, String dataClass) {
    return query(
            "SELECT COUNT(*) AS n FROM retention_schedules s WHERE s.tenant_id = ?"
                + " AND s.data_class = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, dataClass);
            },
            rs -> rs.getInt("n"),
            "retention schedule history")
        .get(0);
  }

  public List<Hold> holds(UUID tenantId, boolean activeOnly) {
    return query(
        "SELECT "
            + HOLD_COLUMNS
            + " FROM retention_holds h WHERE h.tenant_id = ?"
            + (activeOnly ? " AND h.released_at IS NULL" : "")
            + " ORDER BY h.placed_at DESC, h.id DESC",
        ps -> ps.setObject(1, tenantId),
        RetentionRepository::mapHold,
        "retention holds");
  }

  public Hold insertHold(Hold h) {
    exec(
        "INSERT INTO retention_holds (id, tenant_id, data_class, subject_kind, subject_id, reason,"
            + " placed_by, placed_at) VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, h.id());
          ps.setObject(2, h.tenantId());
          ps.setString(3, h.dataClass());
          ps.setString(4, h.subjectKind().name());
          ps.setObject(5, h.subjectId());
          ps.setString(6, h.reason());
          ps.setObject(7, h.placedBy());
          ps.setObject(8, utc(h.placedAt()));
        },
        "place retention hold");
    return h;
  }

  /**
   * Releases a hold, once, under its row lock.
   *
   * @throws ApiException 404 {@code RETENTION_HOLD_NOT_FOUND}; 409 {@code RETENTION_HOLD_RELEASED}
   */
  public Hold release(UUID tenantId, UUID id, UUID by, String reason) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + HOLD_COLUMNS
                      + " FROM retention_holds h WHERE h.tenant_id = ? AND h.id = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("RETENTION_HOLD_NOT_FOUND", "No such hold");
              }
              if (rs.getObject("released_at") != null) {
                throw ApiException.conflict(
                    "RETENTION_HOLD_RELEASED", "This hold was already released");
              }
            }
          }
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE retention_holds SET released_by = ?, released_at = ?, release_reason = ?"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, by);
            ps.setObject(2, utc(now));
            ps.setString(3, reason);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT "
                      + HOLD_COLUMNS
                      + " FROM retention_holds h WHERE h.tenant_id = ? AND h.id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapHold(rs);
            }
          }
        },
        "release retention hold");
  }

  /**
   * Records a run once: the id is the event's, so a redelivery records nothing.
   *
   * @return whether the run was recorded now
   */
  public boolean recordRunOnce(Run r) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO retention_runs (id, tenant_id, service, data_class, cutoff,"
                      + " rows_affected, held_skipped, started_at, finished_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setString(3, r.service());
            ps.setString(4, r.dataClass());
            ps.setObject(5, utc(r.cutoff()));
            ps.setInt(6, r.rowsAffected());
            ps.setInt(7, r.heldSkipped());
            ps.setObject(8, utc(r.startedAt()));
            ps.setObject(9, utc(r.finishedAt()));
            return ps.executeUpdate() == 1;
          }
        },
        "record retention run");
  }

  /** Keyset page of runs, newest first. */
  public List<Run> runs(UUID tenantId, Cursor.CreatedAtId after, int limitPlusOne) {
    return query(
        "SELECT r.id, r.tenant_id, r.service, r.data_class, r.cutoff, r.rows_affected,"
            + " r.held_skipped, r.started_at, r.finished_at, r.recorded_at FROM retention_runs r"
            + " WHERE r.tenant_id = ?"
            + (after != null ? " AND (r.finished_at, r.id) < (?, ?)" : "")
            + " ORDER BY r.finished_at DESC, r.id DESC LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (after != null) {
            ps.setObject(i++, utc(after.createdAt()));
            ps.setObject(i++, after.id());
          }
          ps.setInt(i, limitPlusOne);
        },
        rs ->
            new Run(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("service"),
                rs.getString("data_class"),
                instant(rs, "cutoff"),
                rs.getInt("rows_affected"),
                rs.getInt("held_skipped"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "recorded_at")),
        "retention runs");
  }

  private static Schedule mapSchedule(ResultSet rs) throws SQLException {
    return new Schedule(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("data_class"),
        rs.getInt("period_days"),
        rs.getObject("set_by", UUID.class),
        instant(rs, "set_at"));
  }

  private static Hold mapHold(ResultSet rs) throws SQLException {
    return new Hold(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("data_class"),
        SubjectKind.valueOf(rs.getString("subject_kind")),
        rs.getObject("subject_id", UUID.class),
        rs.getString("reason"),
        rs.getObject("placed_by", UUID.class),
        instant(rs, "placed_at"),
        rs.getObject("released_by", UUID.class),
        instant(rs, "released_at"),
        rs.getString("release_reason"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
    return odt == null ? null : odt.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
