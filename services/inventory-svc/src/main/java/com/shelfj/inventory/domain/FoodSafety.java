package com.shelfj.inventory.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Food-safety checks: temperature monitoring and HACCP records, and the rules that judge them. */
public final class FoodSafety {

  private FoodSafety() {}

  public enum Kind {
    TEMPERATURE,
    PASS_FAIL
  }

  public enum Result {
    PASS,
    FAIL
  }

  public enum DueStatus {
    OK,
    DUE,
    OVERDUE
  }

  public enum FoodDisposition {
    NONE,
    DISCARDED,
    MOVED,
    REHEATED,
    RECOOKED,
    OTHER
  }

  /** Inclusive bounds; either may be absent. A reading on the limit passes. */
  public record Limits(BigDecimal min, BigDecimal max) {

    /** Neither bound set: nothing to judge a reading against. */
    public static final Limits NONE = new Limits(null, null);

    /**
     * Whether this pair constrains anything at all.
     *
     * @return {@code true} when neither bound is set
     */
    public boolean isEmpty() {
      return min == null && max == null;
    }

    /**
     * Whether the bounds are the right way round.
     *
     * @return {@code true} unless a minimum sits above its maximum; a half-open range is ordered by
     *     definition
     */
    public boolean isOrdered() {
      return min == null || max == null || min.compareTo(max) <= 0;
    }

    /**
     * Judges one reading against these bounds.
     *
     * <p>Bounds are inclusive: a reading exactly on the limit passes.
     *
     * @param value the reading to judge
     * @return {@code FAIL} when the reading falls outside either bound, otherwise {@code PASS}
     */
    public Result judge(BigDecimal value) {
      if (min != null && value.compareTo(min) < 0) return Result.FAIL;
      if (max != null && value.compareTo(max) > 0) return Result.FAIL;
      return Result.PASS;
    }

    /**
     * A point may tighten its type's limits, never loosen them — and dropping a bound the type sets
     * is loosening it. Mirrors age restrictions, where a tenant may be stricter than the law.
     */
    public boolean isNoLaxerThan(Limits base) {
      if (base.min != null && (min == null || min.compareTo(base.min) < 0)) return false;
      return base.max == null || (max != null && max.compareTo(base.max) <= 0);
    }

    /**
     * Fills in whichever bounds this pair leaves unset from {@code base}.
     *
     * <p>Unlike {@link #tightestWith}, a bound set here wins even if it is looser — this is
     * defaulting, not constraining.
     *
     * @param base the limits to inherit missing bounds from
     * @return the combined limits
     */
    public Limits withDefaultsFrom(Limits base) {
      return new Limits(min != null ? min : base.min, max != null ? max : base.max);
    }

    /**
     * The tighter of each bound. A check is judged against its point's limits and its type's
     * current ones together, so tightening a type later still binds a point created before it.
     */
    public Limits tightestWith(Limits other) {
      return new Limits(higher(min, other.min), lower(max, other.max));
    }

    private static BigDecimal higher(BigDecimal a, BigDecimal b) {
      if (a == null) return b;
      return b == null || a.compareTo(b) >= 0 ? a : b;
    }

    private static BigDecimal lower(BigDecimal a, BigDecimal b) {
      if (a == null) return b;
      return b == null || a.compareTo(b) <= 0 ? a : b;
    }
  }

  public record CheckType(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      Kind kind,
      Limits limits,
      String unit,
      String basis,
      boolean statutory,
      boolean active) {

    /** The platform's reference types belong to no tenant, and no tenant may edit them. */
    public boolean isPlatformType() {
      return tenantId == null;
    }
  }

  public record MonitoringPoint(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID zoneId,
      String name,
      UUID checkTypeId,
      Limits limits,
      int frequencyHours,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {}

  /** A point with its type and its most recent check, as a store's day of checks needs it. */
  public record PointStatus(
      MonitoringPoint point,
      CheckType type,
      Instant lastRecordedAt,
      Result lastResult,
      BigDecimal lastValue,
      long openFailures) {

    /** A point never checked is due one interval after it was set up, not the moment it exists. */
    public Instant nextDueAt() {
      Instant from = lastRecordedAt != null ? lastRecordedAt : point.createdAt();
      return from.plus(Duration.ofHours(point.frequencyHours()));
    }

    /** DUE opens for the last quarter of the interval, so a check can be made on time. */
    public DueStatus dueStatus(Instant now) {
      Instant due = nextDueAt();
      if (now.isAfter(due)) return DueStatus.OVERDUE;
      Duration window = Duration.ofHours(point.frequencyHours()).dividedBy(4);
      return now.isBefore(due.minus(window)) ? DueStatus.OK : DueStatus.DUE;
    }
  }

  public record CheckRecord(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID pointId,
      UUID checkTypeId,
      Kind kind,
      BigDecimal value,
      String unit,
      Limits limits,
      Result result,
      String notes,
      String refType,
      UUID refId,
      UUID recordedBy,
      Instant recordedAt) {}

  /** A record with what a diary shows beside it. */
  public record DiaryEntry(
      CheckRecord record,
      String pointName,
      String checkTypeCode,
      String checkTypeName,
      int actions) {

    /** A failure nobody has recorded a response to is what an inspector asks about first. */
    public boolean isOpenFailure() {
      return record.result() == Result.FAIL && actions == 0;
    }
  }

  public record CorrectiveAction(
      UUID id,
      UUID tenantId,
      UUID recordId,
      String action,
      FoodDisposition foodDisposition,
      UUID recordedBy,
      Instant recordedAt) {}

  public record ReviewCounts(int records, int failures, int openFailures) {}

  public record Review(
      UUID id,
      UUID tenantId,
      UUID storeId,
      Instant periodFrom,
      Instant periodTo,
      ReviewCounts counts,
      String notes,
      UUID reviewedBy,
      Instant reviewedAt) {}

  /** An active point whose check is overdue and not yet alerted for this due time. */
  public record OverduePoint(
      UUID tenantId,
      UUID pointId,
      UUID storeId,
      String pointName,
      String checkTypeCode,
      Instant dueSince) {}
}
