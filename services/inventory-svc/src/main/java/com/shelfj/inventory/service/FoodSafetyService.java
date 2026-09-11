package com.shelfj.inventory.service;

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
import com.shelfj.inventory.repo.FoodSafetyRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import com.shelfj.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Temperature monitoring and HACCP checks: what may be checked, where, against which limits, and
 * what happens when a check fails or is missed.
 */
@ApplicationScoped
public class FoodSafetyService {

  /** Every temperature type is in degrees Celsius; a second unit would need conversion first. */
  static final String CELSIUS = "C";

  private static final BigDecimal ABSOLUTE_ZERO = new BigDecimal("-273.15");
  private static final BigDecimal HOTTEST_PLAUSIBLE = new BigDecimal("1000.00");

  static final String TOPIC_CHECK_FAILED = "shelfj.inventory.food-safety-check-failed";
  static final String TOPIC_CHECK_OVERDUE = "shelfj.inventory.food-safety-check-overdue";

  @Inject FoodSafetyRepository repo;

  /** A check as a member of staff reports it. */
  public record RecordCheck(
      UUID tenantId,
      UUID actorId,
      UUID pointId,
      BigDecimal value,
      Boolean passed,
      String notes,
      String refType,
      UUID refId,
      String idempotencyKey) {}

  /** A recorded check, and whether this call replayed an earlier one with the same key. */
  public record RecordedCheck(DiaryEntry entry, boolean replayed) {}

  public record RecordDetail(DiaryEntry entry, List<CorrectiveAction> actions) {}

  // ── check types ────────────────────────────────────────────────────────────

  public List<CheckType> listCheckTypes(UUID tenantId) {
    return repo.listCheckTypes(tenantId);
  }

  public CheckType createCheckType(
      UUID tenantId,
      UUID actorId,
      String code,
      String name,
      Kind kind,
      Limits limits,
      String unit,
      String basis) {
    Limits stored = celsius(limits);
    requireTypeShape(kind, stored, unit);
    var type =
        new CheckType(
            UUID.randomUUID(),
            tenantId,
            code,
            name.trim(),
            kind,
            kind == Kind.TEMPERATURE ? stored : Limits.NONE,
            kind == Kind.TEMPERATURE ? CELSIUS : null,
            blankToNull(basis),
            // A tenant's own limit is never presented as law.
            false,
            true);
    repo.insertCheckType(type, actorId);
    return type;
  }

  public CheckType updateCheckType(
      UUID tenantId, UUID id, String name, Limits limits, String basis, boolean active) {
    CheckType existing = requireType(tenantId, id);
    if (existing.isPlatformType()) {
      throw ApiException.conflict(
          "FOOD_SAFETY_TYPE_READ_ONLY",
          "Platform check types cannot be changed; create your own type instead");
    }
    Limits stored = celsius(limits);
    requireTypeShape(existing.kind(), stored, existing.unit());
    repo.updateCheckType(
        tenantId,
        id,
        name.trim(),
        existing.kind() == Kind.TEMPERATURE ? stored : Limits.NONE,
        blankToNull(basis),
        active);
    return requireType(tenantId, id);
  }

  // ── monitoring points ──────────────────────────────────────────────────────

  public PointStatus createPoint(
      UUID tenantId,
      UUID actorId,
      UUID storeId,
      UUID zoneId,
      String name,
      UUID checkTypeId,
      Limits requested,
      int frequencyHours) {
    CheckType type = requireType(tenantId, checkTypeId);
    if (!type.active()) {
      throw ApiException.conflict(
          "FOOD_SAFETY_TYPE_INACTIVE", "That check type is switched off and takes no new points");
    }
    Instant now = Instant.now();
    var point =
        new MonitoringPoint(
            UUID.randomUUID(),
            tenantId,
            storeId,
            zoneId,
            name.trim(),
            type.id(),
            pointLimits(type, requested),
            frequencyHours,
            true,
            now,
            now);
    repo.insertPoint(point, actorId);
    return new PointStatus(point, type, null, null, null, 0);
  }

  public PointStatus updatePoint(
      UUID tenantId, UUID id, UUID zoneId, String name, Limits requested, int frequencyHours) {
    MonitoringPoint point = requirePoint(tenantId, id);
    CheckType type = requireType(tenantId, point.checkTypeId());
    repo.updatePoint(
        tenantId, id, zoneId, name.trim(), pointLimits(type, requested), frequencyHours);
    return requirePointStatus(tenantId, id);
  }

  public PointStatus setPointActive(
      UUID tenantId, UUID id, boolean active, String reason, UUID actorId) {
    requirePoint(tenantId, id);
    if (!repo.setPointActive(tenantId, id, active, reason.trim(), actorId)) {
      throw ApiException.conflict(
          "FOOD_SAFETY_ALREADY_IN_STATE",
          active ? "This point is already switched on" : "This point is already switched off");
    }
    return requirePointStatus(tenantId, id);
  }

  public List<PointStatus> listPoints(UUID tenantId, UUID storeId, boolean includeInactive) {
    return repo.listPointStatuses(tenantId, storeId, includeInactive);
  }

  // ── records ────────────────────────────────────────────────────────────────

  /**
   * Records a check and judges it against the limits in force.
   *
   * @param requireStoreAccess refuses a caller who is not assigned to the point's store; passed in
   *     so this class stays free of the request context
   */
  public RecordedCheck recordCheck(RecordCheck cmd, Consumer<UUID> requireStoreAccess) {
    if (cmd.idempotencyKey() != null) {
      var prior = repo.findRecordByIdempotencyKey(cmd.tenantId(), cmd.idempotencyKey());
      if (prior.isPresent()) {
        requireStoreAccess.accept(prior.get().storeId());
        return new RecordedCheck(requireEntry(cmd.tenantId(), prior.get().id()), true);
      }
    }
    MonitoringPoint point = requirePoint(cmd.tenantId(), cmd.pointId());
    requireStoreAccess.accept(point.storeId());
    if (!point.active()) {
      throw ApiException.conflict(
          "FOOD_SAFETY_POINT_INACTIVE", "This monitoring point is switched off");
    }
    if ((cmd.refType() == null) != (cmd.refId() == null)) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_REFERENCE_INCOMPLETE", "refType and refId must be given together");
    }
    CheckType type = requireType(cmd.tenantId(), point.checkTypeId());
    Limits limits =
        type.kind() == Kind.TEMPERATURE ? point.limits().tightestWith(type.limits()) : Limits.NONE;
    BigDecimal value = celsius(cmd.value(), "value");
    var record =
        new CheckRecord(
            UUID.randomUUID(),
            cmd.tenantId(),
            point.storeId(),
            point.id(),
            type.id(),
            type.kind(),
            value,
            type.unit(),
            limits,
            judge(type.kind(), limits, value, cmd.passed()),
            blankToNull(cmd.notes()),
            cmd.refType(),
            cmd.refId(),
            cmd.actorId(),
            Instant.now());
    OutboxRow failed =
        record.result() == Result.FAIL
            ? new OutboxRow(
                "FoodSafetyCheckFailed",
                TOPIC_CHECK_FAILED,
                cmd.tenantId(),
                record.id(),
                Events.foodSafetyCheckFailed(record, point.name(), type.code()))
            : null;
    repo.insertRecord(record, cmd.idempotencyKey(), failed);
    return new RecordedCheck(requireEntry(cmd.tenantId(), record.id()), false);
  }

  public RecordDetail getRecord(UUID tenantId, UUID recordId) {
    return new RecordDetail(
        requireEntry(tenantId, recordId), repo.listCorrectiveActions(tenantId, recordId));
  }

  public Cursor.Page<DiaryEntry> listDiary(
      UUID tenantId,
      UUID storeId,
      UUID pointId,
      Result result,
      boolean openOnly,
      Instant from,
      Instant to,
      String after,
      Integer limit) {
    if (from != null && to != null && !from.isBefore(to)) {
      throw ApiException.badRequest("FOOD_SAFETY_PERIOD_INVERTED", "from must be before to");
    }
    int lim = Cursor.clampLimit(limit);
    var rows =
        repo.listDiary(
            tenantId,
            storeId,
            pointId,
            result,
            openOnly,
            from,
            to,
            Cursor.decodeCreatedAtId(after),
            lim + 1);
    return Cursor.page(rows, lim, e -> e.record().recordedAt() + "|" + e.record().id());
  }

  public CorrectiveAction addCorrectiveAction(
      UUID tenantId,
      UUID actorId,
      UUID recordId,
      String action,
      FoodDisposition disposition,
      Consumer<UUID> requireStoreAccess) {
    DiaryEntry entry = requireEntry(tenantId, recordId);
    requireStoreAccess.accept(entry.record().storeId());
    if (entry.record().result() == Result.PASS) {
      throw ApiException.conflict(
          "FOOD_SAFETY_RECORD_PASSED", "A check that passed needs no corrective action");
    }
    var corrective =
        new CorrectiveAction(
            UUID.randomUUID(),
            tenantId,
            recordId,
            action.trim(),
            disposition,
            actorId,
            Instant.now());
    repo.insertCorrectiveAction(corrective);
    return corrective;
  }

  // ── reviews ────────────────────────────────────────────────────────────────

  public Review createReview(
      UUID tenantId, UUID actorId, UUID storeId, Instant from, Instant to, String notes) {
    if (!from.isBefore(to)) {
      throw ApiException.badRequest("FOOD_SAFETY_PERIOD_INVERTED", "from must be before to");
    }
    var review =
        new Review(
            UUID.randomUUID(),
            tenantId,
            storeId,
            from,
            to,
            repo.countForReview(tenantId, storeId, from, to),
            blankToNull(notes),
            actorId,
            Instant.now());
    repo.insertReview(review);
    return review;
  }

  public Cursor.Page<Review> listReviews(UUID tenantId, UUID storeId, String after, Integer limit) {
    int lim = Cursor.clampLimit(limit);
    var rows = repo.listReviews(tenantId, storeId, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, r -> r.reviewedAt() + "|" + r.id());
  }

  // ── overdue sweep ──────────────────────────────────────────────────────────

  /**
   * Alerts every point whose check is overdue, once per missed due time.
   *
   * @return how many alerts this sweep raised
   */
  public int sweepOverdue(int batchSize) {
    int alerted = 0;
    for (OverduePoint point : repo.findUnalertedOverduePoints(batchSize)) {
      var event =
          new OutboxRow(
              "FoodSafetyCheckOverdue",
              TOPIC_CHECK_OVERDUE,
              point.tenantId(),
              point.pointId(),
              Events.foodSafetyCheckOverdue(point));
      if (repo.claimOverdueAlert(point, event)) {
        alerted++;
      }
    }
    return alerted;
  }

  // ── rules ──────────────────────────────────────────────────────────────────

  /**
   * A TEMPERATURE check needs a reading and no verdict; a PASS_FAIL check the reverse. The field
   * that should not be there is reported first: sending it means the caller has the check's kind
   * wrong, which "missing field" would not tell them.
   */
  static Result judge(Kind kind, Limits limits, BigDecimal value, Boolean passed) {
    return switch (kind) {
      case TEMPERATURE -> {
        if (passed != null) {
          throw ApiException.badRequest(
              "FOOD_SAFETY_PASSED_NOT_ALLOWED",
              "A temperature check is judged against its limits, not reported as passed");
        }
        if (value == null) {
          throw ApiException.badRequest(
              "FOOD_SAFETY_VALUE_REQUIRED", "A temperature check needs the reading");
        }
        yield limits.judge(value);
      }
      case PASS_FAIL -> {
        if (value != null) {
          throw ApiException.badRequest(
              "FOOD_SAFETY_VALUE_NOT_ALLOWED", "This check takes no reading");
        }
        if (passed == null) {
          throw ApiException.badRequest(
              "FOOD_SAFETY_PASSED_REQUIRED", "This check needs passed: true or false");
        }
        yield passed ? Result.PASS : Result.FAIL;
      }
    };
  }

  /**
   * A point starts from its type's limits and may only tighten them. Refused with 422 rather than
   * silently clamped: an operator who typed 10 °C for a chiller should be told the law says 8.
   */
  static Limits pointLimits(CheckType type, Limits requested) {
    if (type.kind() == Kind.PASS_FAIL) {
      if (!requested.isEmpty()) {
        throw ApiException.badRequest(
            "FOOD_SAFETY_LIMITS_NOT_ALLOWED", "A pass/fail check has no limits");
      }
      return Limits.NONE;
    }
    Limits limits = celsius(requested).withDefaultsFrom(type.limits());
    if (!limits.isOrdered()) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_LIMITS_INVERTED", "minValue must not be above maxValue");
    }
    if (!limits.isNoLaxerThan(type.limits())) {
      throw ApiException.unprocessable(
          "FOOD_SAFETY_LIMIT_LAXER_THAN_TYPE",
          "A point's limits may be stricter than its check type's, never laxer");
    }
    return limits;
  }

  /**
   * A temperature at the precision it is stored and judged at. The columns are NUMERIC(6,2) and
   * Postgres rounds to scale on write without complaint, so a reading of 8.004 against a limit of
   * 8.00 would be judged a failure and then stored as 8.00 — a record contradicting itself. Refused
   * instead of rounded, because no probe a shop uses reads to thousandths.
   */
  static BigDecimal celsius(BigDecimal value, String field) {
    if (value == null) {
      return null;
    }
    if (value.stripTrailingZeros().scale() > 2) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_TOO_PRECISE", field + " takes at most two decimal places");
    }
    if (value.compareTo(ABSOLUTE_ZERO) < 0 || value.compareTo(HOTTEST_PLAUSIBLE) > 0) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_OUT_OF_RANGE", field + " must be between -273.15 and 1000 °C");
    }
    return value.setScale(2);
  }

  static Limits celsius(Limits limits) {
    return new Limits(celsius(limits.min(), "minValue"), celsius(limits.max(), "maxValue"));
  }

  private static void requireTypeShape(Kind kind, Limits limits, String unit) {
    if (kind == Kind.PASS_FAIL) {
      if (!limits.isEmpty()) {
        throw ApiException.badRequest(
            "FOOD_SAFETY_LIMITS_NOT_ALLOWED", "A pass/fail check has no limits");
      }
      return;
    }
    if (limits.isEmpty()) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_LIMIT_REQUIRED", "A temperature check needs a minimum, a maximum or both");
    }
    if (!limits.isOrdered()) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_LIMITS_INVERTED", "minValue must not be above maxValue");
    }
    if (unit != null && !CELSIUS.equals(unit)) {
      throw ApiException.badRequest(
          "FOOD_SAFETY_UNIT_UNSUPPORTED", "Temperatures are recorded in degrees Celsius (C)");
    }
  }

  private CheckType requireType(UUID tenantId, UUID id) {
    return repo.findCheckType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("FOOD_SAFETY_TYPE_NOT_FOUND", "No such check type"));
  }

  private MonitoringPoint requirePoint(UUID tenantId, UUID id) {
    return repo.findPoint(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("FOOD_SAFETY_POINT_NOT_FOUND", "No such monitoring point"));
  }

  private PointStatus requirePointStatus(UUID tenantId, UUID id) {
    return repo.findPointStatus(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("FOOD_SAFETY_POINT_NOT_FOUND", "No such monitoring point"));
  }

  private DiaryEntry requireEntry(UUID tenantId, UUID recordId) {
    return repo.findDiaryEntry(tenantId, recordId)
        .orElseThrow(
            () -> ApiException.notFound("FOOD_SAFETY_RECORD_NOT_FOUND", "No such check record"));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
