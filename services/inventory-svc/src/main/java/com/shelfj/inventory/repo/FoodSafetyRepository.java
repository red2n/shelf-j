package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.FoodSafety.CheckRecord;
import com.shelfj.inventory.domain.FoodSafety.CheckType;
import com.shelfj.inventory.domain.FoodSafety.CorrectiveAction;
import com.shelfj.inventory.domain.FoodSafety.DiaryEntry;
import com.shelfj.inventory.domain.FoodSafety.FoodDisposition;
import com.shelfj.inventory.domain.FoodSafety.Kind;
import com.shelfj.inventory.domain.FoodSafety.Limits;
import com.shelfj.inventory.domain.FoodSafety.MonitoringPoint;
import com.shelfj.inventory.domain.FoodSafety.OverduePoint;
import com.shelfj.inventory.domain.FoodSafety.PointStatus;
import com.shelfj.inventory.domain.FoodSafety.Result;
import com.shelfj.inventory.domain.FoodSafety.Review;
import com.shelfj.inventory.domain.FoodSafety.ReviewCounts;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import com.shelfj.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
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
 * Food-safety check types, monitoring points, records, corrective actions and reviews.
 *
 * <p>Records, corrective actions, reviews and point status changes are append-only: this class
 * holds no UPDATE or DELETE for any of them.
 */
@ApplicationScoped
public class FoodSafetyRepository extends BaseOutboxRepository {

  private static final String TYPE_COLUMNS =
      "t.id, t.tenant_id, t.code, t.name, t.kind, t.min_value, t.max_value, t.unit, t.basis,"
          + " t.statutory, t.active";

  /** The type's columns beside a point's, aliased where the two tables share a name. */
  private static final String TYPE_COLUMNS_BESIDE_POINT =
      "t.id AS type_id, t.tenant_id AS type_tenant_id, t.code, t.name AS type_name, t.kind,"
          + " t.min_value AS type_min, t.max_value AS type_max, t.unit, t.basis, t.statutory,"
          + " t.active AS type_active";

  private static final String POINT_COLUMNS =
      "p.id, p.tenant_id, p.store_id, p.zone_id, p.name, p.check_type_id, p.min_value, p.max_value,"
          + " p.frequency_hours, p.active, p.created_at, p.updated_at";

  private static final String POINT_STATUS_SELECT =
      "SELECT "
          + POINT_COLUMNS
          + ", "
          + TYPE_COLUMNS_BESIDE_POINT
          + ", last.recorded_at AS last_recorded_at, last.result AS last_result,"
          + " last.value AS last_value,"
          + " (SELECT COUNT(*) FROM fs_check_records f"
          + "   WHERE f.tenant_id = p.tenant_id AND f.point_id = p.id AND f.result = 'FAIL'"
          + "   AND NOT EXISTS (SELECT 1 FROM fs_corrective_actions a"
          + "     WHERE a.tenant_id = f.tenant_id AND a.record_id = f.id)) AS open_failures"
          + " FROM fs_monitoring_points p"
          + " JOIN fs_check_types t ON t.id = p.check_type_id"
          + " LEFT JOIN LATERAL (SELECT l.recorded_at, l.result, l.value FROM fs_check_records l"
          + "   WHERE l.tenant_id = p.tenant_id AND l.point_id = p.id"
          + "   ORDER BY l.recorded_at DESC LIMIT 1) last ON TRUE";

  private static final String RECORD_COLUMNS =
      "r.id, r.tenant_id, r.store_id, r.point_id, r.check_type_id, r.kind, r.value, r.unit,"
          + " r.min_value, r.max_value, r.result, r.notes, r.ref_type, r.ref_id, r.recorded_by,"
          + " r.recorded_at";

  private static final String DIARY_SELECT =
      "SELECT "
          + RECORD_COLUMNS
          + ", p.name AS point_name, t.code AS type_code, t.name AS type_name,"
          + " (SELECT COUNT(*) FROM fs_corrective_actions a"
          + "   WHERE a.tenant_id = r.tenant_id AND a.record_id = r.id) AS actions"
          + " FROM fs_check_records r"
          + " JOIN fs_monitoring_points p ON p.tenant_id = r.tenant_id AND p.id = r.point_id"
          + " JOIN fs_check_types t ON t.id = r.check_type_id";

  // ── check types ────────────────────────────────────────────────────────────

  /** The platform's reference types plus the tenant's own. */
  public List<CheckType> listCheckTypes(UUID tenantId) {
    return query(
        "SELECT "
            + TYPE_COLUMNS
            + " FROM fs_check_types t WHERE (t.tenant_id = ? OR t.tenant_id IS NULL)"
            + " ORDER BY t.tenant_id NULLS FIRST, t.name",
        ps -> ps.setObject(1, tenantId),
        FoodSafetyRepository::mapType,
        "list food-safety check types");
  }

  public Optional<CheckType> findCheckType(UUID tenantId, UUID id) {
    return query(
            "SELECT "
                + TYPE_COLUMNS
                + " FROM fs_check_types t WHERE (t.tenant_id = ? OR t.tenant_id IS NULL)"
                + " AND t.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            FoodSafetyRepository::mapType,
            "find food-safety check type")
        .stream()
        .findFirst();
  }

  public void insertCheckType(CheckType type, UUID actorId) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO fs_check_types (id, tenant_id, code, name, kind, min_value,"
                      + " max_value, unit, basis, statutory, active, created_by)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, type.id());
            ps.setObject(2, type.tenantId());
            ps.setString(3, type.code());
            ps.setString(4, type.name());
            ps.setString(5, type.kind().name());
            ps.setBigDecimal(6, type.limits().min());
            ps.setBigDecimal(7, type.limits().max());
            ps.setString(8, type.unit());
            ps.setString(9, type.basis());
            ps.setBoolean(10, type.statutory());
            ps.setBoolean(11, type.active());
            ps.setObject(12, actorId);
            ps.executeUpdate();
          }
          return null;
        },
        "create food-safety check type");
  }

  /** Only a tenant's own type: the tenant filter is what keeps platform types read-only. */
  public boolean updateCheckType(
      UUID tenantId, UUID id, String name, Limits limits, String basis, boolean active) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE fs_check_types SET name = ?, min_value = ?, max_value = ?, basis = ?,"
                      + " active = ?, updated_at = now() WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, name);
            ps.setBigDecimal(2, limits.min());
            ps.setBigDecimal(3, limits.max());
            ps.setString(4, basis);
            ps.setBoolean(5, active);
            ps.setObject(6, tenantId);
            ps.setObject(7, id);
            return ps.executeUpdate() == 1;
          }
        },
        "update food-safety check type");
  }

  // ── monitoring points ──────────────────────────────────────────────────────

  public void insertPoint(MonitoringPoint point, UUID actorId) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO fs_monitoring_points (id, tenant_id, store_id, zone_id, name,"
                      + " check_type_id, min_value, max_value, frequency_hours, active, created_by,"
                      + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, point.id());
            ps.setObject(2, point.tenantId());
            ps.setObject(3, point.storeId());
            ps.setObject(4, point.zoneId());
            ps.setString(5, point.name());
            ps.setObject(6, point.checkTypeId());
            ps.setBigDecimal(7, point.limits().min());
            ps.setBigDecimal(8, point.limits().max());
            ps.setInt(9, point.frequencyHours());
            ps.setBoolean(10, point.active());
            ps.setObject(11, actorId);
            ps.setObject(12, utc(point.createdAt()));
            ps.setObject(13, utc(point.updatedAt()));
            ps.executeUpdate();
          }
          return null;
        },
        "create food-safety monitoring point");
  }

  public Optional<MonitoringPoint> findPoint(UUID tenantId, UUID id) {
    return query(
            "SELECT "
                + POINT_COLUMNS
                + " FROM fs_monitoring_points p WHERE p.tenant_id = ? AND p.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            FoodSafetyRepository::mapPoint,
            "find food-safety monitoring point")
        .stream()
        .findFirst();
  }

  public void updatePoint(
      UUID tenantId, UUID id, UUID zoneId, String name, Limits limits, int frequencyHours) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE fs_monitoring_points SET zone_id = ?, name = ?, min_value = ?,"
                      + " max_value = ?, frequency_hours = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, zoneId);
            ps.setString(2, name);
            ps.setBigDecimal(3, limits.min());
            ps.setBigDecimal(4, limits.max());
            ps.setInt(5, frequencyHours);
            ps.setObject(6, tenantId);
            ps.setObject(7, id);
            ps.executeUpdate();
          }
          return null;
        },
        "update food-safety monitoring point");
  }

  /**
   * Switches a point and appends who did it and why, in one transaction.
   *
   * @return false when the point was already in that state, so nothing is recorded twice
   */
  public boolean setPointActive(
      UUID tenantId, UUID pointId, boolean active, String reason, UUID actorId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE fs_monitoring_points SET active = ?, updated_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND active <> ?")) {
            ps.setBoolean(1, active);
            ps.setObject(2, tenantId);
            ps.setObject(3, pointId);
            ps.setBoolean(4, active);
            if (ps.executeUpdate() == 0) {
              return false;
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO fs_point_status_changes (id, tenant_id, point_id, active, reason,"
                      + " changed_by) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setObject(3, pointId);
            ps.setBoolean(4, active);
            ps.setString(5, reason);
            ps.setObject(6, actorId);
            ps.executeUpdate();
          }
          return true;
        },
        "switch food-safety monitoring point");
  }

  /** A store's points, each with its type, its last check and how many failures are still open. */
  public List<PointStatus> listPointStatuses(UUID tenantId, UUID storeId, boolean includeInactive) {
    return query(
        POINT_STATUS_SELECT
            + " WHERE p.tenant_id = ? AND p.store_id = ?"
            + (includeInactive ? "" : " AND p.active")
            + " ORDER BY p.name",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        FoodSafetyRepository::mapPointStatus,
        "list food-safety monitoring points");
  }

  public Optional<PointStatus> findPointStatus(UUID tenantId, UUID pointId) {
    return query(
            POINT_STATUS_SELECT + " WHERE p.tenant_id = ? AND p.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, pointId);
            },
            FoodSafetyRepository::mapPointStatus,
            "find food-safety monitoring point status")
        .stream()
        .findFirst();
  }

  // ── records ────────────────────────────────────────────────────────────────

  public Optional<CheckRecord> findRecordByIdempotencyKey(UUID tenantId, String idempotencyKey) {
    return query(
            "SELECT "
                + RECORD_COLUMNS
                + " FROM fs_check_records r WHERE r.tenant_id = ? AND r.idempotency_key = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            FoodSafetyRepository::mapRecord,
            "find food-safety record by idempotency key")
        .stream()
        .findFirst();
  }

  /**
   * Writes a check, and for a failure the event that alerts the store, in one transaction — so a
   * failed reading can never be on record without the alert, or alerted without the record.
   */
  public void insertRecord(CheckRecord record, String idempotencyKey, OutboxRow failedEvent) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO fs_check_records (id, tenant_id, store_id, point_id, check_type_id,"
                      + " kind, value, unit, min_value, max_value, result, notes, ref_type, ref_id,"
                      + " recorded_by, recorded_at, idempotency_key)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, record.id());
            ps.setObject(2, record.tenantId());
            ps.setObject(3, record.storeId());
            ps.setObject(4, record.pointId());
            ps.setObject(5, record.checkTypeId());
            ps.setString(6, record.kind().name());
            ps.setBigDecimal(7, record.value());
            ps.setString(8, record.unit());
            ps.setBigDecimal(9, record.limits().min());
            ps.setBigDecimal(10, record.limits().max());
            ps.setString(11, record.result().name());
            ps.setString(12, record.notes());
            ps.setString(13, record.refType());
            ps.setObject(14, record.refId());
            ps.setObject(15, record.recordedBy());
            ps.setObject(16, utc(record.recordedAt()));
            ps.setString(17, idempotencyKey);
            ps.executeUpdate();
          }
          if (failedEvent != null) {
            insertOutbox(c, failedEvent);
          }
          return null;
        },
        "record food-safety check");
  }

  public Optional<DiaryEntry> findDiaryEntry(UUID tenantId, UUID recordId) {
    return query(
            DIARY_SELECT + " WHERE r.tenant_id = ? AND r.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, recordId);
            },
            FoodSafetyRepository::mapDiaryEntry,
            "find food-safety record")
        .stream()
        .findFirst();
  }

  /**
   * A page of the diary, newest first, keyed on (recorded_at, id).
   *
   * @param from inclusive lower bound, or null
   * @param to exclusive upper bound, or null — a half-open window, so adjacent days never share a
   *     record (SJ-D30)
   */
  public List<DiaryEntry> listDiary(
      UUID tenantId,
      UUID storeId,
      UUID pointId,
      Result result,
      boolean openOnly,
      Instant from,
      Instant to,
      Cursor.CreatedAtId after,
      int limitPlusOne) {
    StringBuilder sql = new StringBuilder(DIARY_SELECT).append(" WHERE r.tenant_id = ?");
    if (storeId != null) sql.append(" AND r.store_id = ?");
    if (pointId != null) sql.append(" AND r.point_id = ?");
    if (result != null) sql.append(" AND r.result = ?");
    if (openOnly) {
      sql.append(
          " AND r.result = 'FAIL' AND NOT EXISTS (SELECT 1 FROM fs_corrective_actions a"
              + " WHERE a.tenant_id = r.tenant_id AND a.record_id = r.id)");
    }
    if (from != null) sql.append(" AND r.recorded_at >= ?");
    if (to != null) sql.append(" AND r.recorded_at < ?");
    if (after != null) sql.append(" AND (r.recorded_at, r.id) < (?, ?)");
    sql.append(" ORDER BY r.recorded_at DESC, r.id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (pointId != null) ps.setObject(i++, pointId);
          if (result != null) ps.setString(i++, result.name());
          if (from != null) ps.setObject(i++, utc(from));
          if (to != null) ps.setObject(i++, utc(to));
          if (after != null) {
            ps.setObject(i++, utc(after.createdAt()));
            ps.setObject(i++, after.id());
          }
          ps.setInt(i, limitPlusOne);
        },
        FoodSafetyRepository::mapDiaryEntry,
        "list food-safety records");
  }

  // ── corrective actions ─────────────────────────────────────────────────────

  public void insertCorrectiveAction(CorrectiveAction action) {
    exec(
        "INSERT INTO fs_corrective_actions (id, tenant_id, record_id, action, food_disposition,"
            + " recorded_by, recorded_at) VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, action.id());
          ps.setObject(2, action.tenantId());
          ps.setObject(3, action.recordId());
          ps.setString(4, action.action());
          ps.setString(5, action.foodDisposition().name());
          ps.setObject(6, action.recordedBy());
          ps.setObject(7, utc(action.recordedAt()));
        },
        "record food-safety corrective action");
  }

  public List<CorrectiveAction> listCorrectiveActions(UUID tenantId, UUID recordId) {
    return query(
        "SELECT a.id, a.tenant_id, a.record_id, a.action, a.food_disposition, a.recorded_by,"
            + " a.recorded_at FROM fs_corrective_actions a"
            + " WHERE a.tenant_id = ? AND a.record_id = ? ORDER BY a.recorded_at, a.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, recordId);
        },
        rs ->
            new CorrectiveAction(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("record_id", UUID.class),
                rs.getString("action"),
                FoodDisposition.valueOf(rs.getString("food_disposition")),
                rs.getObject("recorded_by", UUID.class),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant()),
        "list food-safety corrective actions");
  }

  // ── reviews ────────────────────────────────────────────────────────────────

  public ReviewCounts countForReview(UUID tenantId, UUID storeId, Instant from, Instant to) {
    return query(
            "SELECT COUNT(*) AS records,"
                + " COUNT(*) FILTER (WHERE r.result = 'FAIL') AS failures,"
                + " COUNT(*) FILTER (WHERE r.result = 'FAIL' AND NOT EXISTS ("
                + "   SELECT 1 FROM fs_corrective_actions a"
                + "   WHERE a.tenant_id = r.tenant_id AND a.record_id = r.id)) AS open_failures"
                + " FROM fs_check_records r"
                + " WHERE r.tenant_id = ? AND r.store_id = ? AND r.recorded_at >= ?"
                + " AND r.recorded_at < ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, utc(from));
              ps.setObject(4, utc(to));
            },
            rs ->
                new ReviewCounts(
                    rs.getInt("records"), rs.getInt("failures"), rs.getInt("open_failures")),
            "count food-safety records for review")
        .get(0);
  }

  public void insertReview(Review review) {
    exec(
        "INSERT INTO fs_reviews (id, tenant_id, store_id, period_from, period_to, records_count,"
            + " failures_count, open_failures_count, notes, reviewed_by, reviewed_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, review.id());
          ps.setObject(2, review.tenantId());
          ps.setObject(3, review.storeId());
          ps.setObject(4, utc(review.periodFrom()));
          ps.setObject(5, utc(review.periodTo()));
          ps.setInt(6, review.counts().records());
          ps.setInt(7, review.counts().failures());
          ps.setInt(8, review.counts().openFailures());
          ps.setString(9, review.notes());
          ps.setObject(10, review.reviewedBy());
          ps.setObject(11, utc(review.reviewedAt()));
        },
        "record food-safety review");
  }

  public List<Review> listReviews(
      UUID tenantId, UUID storeId, Cursor.CreatedAtId after, int limitPlusOne) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT v.id, v.tenant_id, v.store_id, v.period_from, v.period_to, v.records_count,"
                + " v.failures_count, v.open_failures_count, v.notes, v.reviewed_by,"
                + " v.reviewed_at FROM fs_reviews v WHERE v.tenant_id = ?");
    if (storeId != null) sql.append(" AND v.store_id = ?");
    if (after != null) sql.append(" AND (v.reviewed_at, v.id) < (?, ?)");
    sql.append(" ORDER BY v.reviewed_at DESC, v.id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (after != null) {
            ps.setObject(i++, utc(after.createdAt()));
            ps.setObject(i++, after.id());
          }
          ps.setInt(i, limitPlusOne);
        },
        rs ->
            new Review(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("period_from", OffsetDateTime.class).toInstant(),
                rs.getObject("period_to", OffsetDateTime.class).toInstant(),
                new ReviewCounts(
                    rs.getInt("records_count"),
                    rs.getInt("failures_count"),
                    rs.getInt("open_failures_count")),
                rs.getString("notes"),
                rs.getObject("reviewed_by", UUID.class),
                rs.getObject("reviewed_at", OffsetDateTime.class).toInstant()),
        "list food-safety reviews");
  }

  // ── overdue sweep ──────────────────────────────────────────────────────────

  /**
   * Active points past their due time that have not been alerted for it. A cross-tenant read for
   * the background sweeper, like the pending-order sweep: the tenant is carried on every row, never
   * assumed.
   */
  public List<OverduePoint> findUnalertedOverduePoints(int limit) {
    return query(
        "SELECT d.tenant_id, d.id, d.store_id, d.name, d.code, d.due_since FROM ("
            + " SELECT p.tenant_id, p.id, p.store_id, p.name, t.code,"
            + "  COALESCE(last.recorded_at, p.created_at)"
            + "   + make_interval(hours => p.frequency_hours) AS due_since"
            + " FROM fs_monitoring_points p"
            + " JOIN fs_check_types t ON t.id = p.check_type_id"
            + " LEFT JOIN LATERAL (SELECT l.recorded_at FROM fs_check_records l"
            + "   WHERE l.tenant_id = p.tenant_id AND l.point_id = p.id"
            + "   ORDER BY l.recorded_at DESC LIMIT 1) last ON TRUE"
            + " WHERE p.active) d"
            + " WHERE d.due_since < now()"
            + " AND NOT EXISTS (SELECT 1 FROM fs_overdue_alerts o"
            + "   WHERE o.tenant_id = d.tenant_id AND o.point_id = d.id"
            + "   AND o.due_since = d.due_since)"
            + " ORDER BY d.due_since LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs ->
            new OverduePoint(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("name"),
                rs.getString("code"),
                rs.getObject("due_since", OffsetDateTime.class).toInstant()),
        "find overdue food-safety checks");
  }

  /**
   * Claims one overdue alert and writes its event in the same transaction.
   *
   * @return false when another sweep already claimed it
   */
  public boolean claimOverdueAlert(OverduePoint point, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO fs_overdue_alerts (tenant_id, point_id, due_since) VALUES (?,?,?)"
                      + " ON CONFLICT DO NOTHING")) {
            ps.setObject(1, point.tenantId());
            ps.setObject(2, point.pointId());
            ps.setObject(3, utc(point.dueSince()));
            if (ps.executeUpdate() == 0) {
              return false;
            }
          }
          insertOutbox(c, event);
          return true;
        },
        "claim overdue food-safety alert");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      String detail = e.getMessage() == null ? "" : e.getMessage();
      if (detail.contains("uq_fs_records_idempotency")) {
        return ApiException.conflict(
            "FOOD_SAFETY_DUPLICATE_KEY", "A check was already recorded with this Idempotency-Key");
      }
      if (detail.contains("uq_fs_points_name")) {
        return ApiException.conflict(
            "FOOD_SAFETY_POINT_NAME_TAKEN", "This store already has a point with that name");
      }
      if (detail.contains("uq_fs_check_types_code")) {
        return ApiException.conflict(
            "FOOD_SAFETY_TYPE_CODE_TAKEN", "A check type with that code already exists");
      }
    }
    return super.handleTxSqlException(what, e);
  }

  // ── mapping ────────────────────────────────────────────────────────────────

  private static CheckType mapType(ResultSet rs) throws SQLException {
    return new CheckType(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        Kind.valueOf(rs.getString("kind")),
        new Limits(rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value")),
        rs.getString("unit"),
        rs.getString("basis"),
        rs.getBoolean("statutory"),
        rs.getBoolean("active"));
  }

  private static MonitoringPoint mapPoint(ResultSet rs) throws SQLException {
    return new MonitoringPoint(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getString("name"),
        rs.getObject("check_type_id", UUID.class),
        new Limits(rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value")),
        rs.getInt("frequency_hours"),
        rs.getBoolean("active"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static PointStatus mapPointStatus(ResultSet rs) throws SQLException {
    String lastResult = rs.getString("last_result");
    return new PointStatus(
        mapPoint(rs),
        new CheckType(
            rs.getObject("type_id", UUID.class),
            rs.getObject("type_tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("type_name"),
            Kind.valueOf(rs.getString("kind")),
            new Limits(rs.getBigDecimal("type_min"), rs.getBigDecimal("type_max")),
            rs.getString("unit"),
            rs.getString("basis"),
            rs.getBoolean("statutory"),
            rs.getBoolean("type_active")),
        instantOrNull(rs, "last_recorded_at"),
        lastResult == null ? null : Result.valueOf(lastResult),
        rs.getBigDecimal("last_value"),
        rs.getLong("open_failures"));
  }

  private static CheckRecord mapRecord(ResultSet rs) throws SQLException {
    return new CheckRecord(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("point_id", UUID.class),
        rs.getObject("check_type_id", UUID.class),
        Kind.valueOf(rs.getString("kind")),
        rs.getBigDecimal("value"),
        rs.getString("unit"),
        new Limits(rs.getBigDecimal("min_value"), rs.getBigDecimal("max_value")),
        Result.valueOf(rs.getString("result")),
        rs.getString("notes"),
        rs.getString("ref_type"),
        rs.getObject("ref_id", UUID.class),
        rs.getObject("recorded_by", UUID.class),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
  }

  private static DiaryEntry mapDiaryEntry(ResultSet rs) throws SQLException {
    return new DiaryEntry(
        mapRecord(rs),
        rs.getString("point_name"),
        rs.getString("type_code"),
        rs.getString("type_name"),
        rs.getInt("actions"));
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
    return odt == null ? null : odt.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
