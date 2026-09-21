package com.storeql.tenant.domain;

import com.storeql.web.ApiException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retention schedules (21.16): how long each class of data is kept, never shorter than the law of
 * any country the business trades in requires; the holds that stop a purge; and the register of
 * purges run.
 */
public final class Retention {

  private Retention() {}

  /** The longest period a schedule accepts: a hundred years is "forever" for these purposes. */
  public static final int MAX_DAYS = 36500;

  /** A class of data a schedule governs, as {@code retention_classes} lists it. */
  public record DataClass(
      String code, String name, String purgeKind, String purgedBy, String description) {}

  /** The least a country requires a class be kept, with the instrument that says so. */
  public record Floor(
      String dataClass, String scope, int minDays, String citation, String summary) {

    /** The highest of several floors — the one a business trading in all those countries owes. */
    public static Optional<Floor> highest(List<Floor> floors) {
      return floors.stream().max(Comparator.comparingInt(Floor::minDays));
    }
  }

  /** One decision on a class's period; the latest per class is the one in force. */
  public record Schedule(
      UUID id, UUID tenantId, String dataClass, int periodDays, UUID setBy, Instant setAt) {}

  /** A class as the business sees it: what the law requires, and what it has set. */
  public record ClassStatus(DataClass dataClass, Floor floor, Schedule schedule) {}

  public enum SubjectKind {
    ALL,
    CUSTOMER,
    ORDER
  }

  public record Hold(
      UUID id,
      UUID tenantId,
      /** Null: every class. */
      String dataClass,
      SubjectKind subjectKind,
      UUID subjectId,
      String reason,
      UUID placedBy,
      Instant placedAt,
      UUID releasedBy,
      Instant releasedAt,
      String releaseReason) {

    public boolean isActive() {
      return releasedAt == null;
    }
  }

  /** One purge a service ran, as it announced it. */
  public record Run(
      UUID id,
      UUID tenantId,
      String service,
      String dataClass,
      Instant cutoff,
      int rowsAffected,
      int heldSkipped,
      Instant startedAt,
      Instant finishedAt,
      Instant recordedAt) {}

  /**
   * Refuses a period the law does not allow.
   *
   * @param periodDays what the business wants to set
   * @param floor the highest floor among the countries it trades in, or null when none
   * @throws ApiException 400 {@code RETENTION_PERIOD_INVALID} outside 0..{@link #MAX_DAYS}; 400
   *     {@code RETENTION_BELOW_LEGAL_MINIMUM} under the floor, naming it and its instrument
   */
  public static void requireLawful(int periodDays, Floor floor) {
    if (periodDays < 0 || periodDays > MAX_DAYS) {
      throw ApiException.badRequest(
          "RETENTION_PERIOD_INVALID", "periodDays is a number of days from 0 to " + MAX_DAYS);
    }
    if (floor != null && periodDays < floor.minDays()) {
      throw new ApiException(
          400,
          "RETENTION_BELOW_LEGAL_MINIMUM",
          "The law requires at least "
              + floor.minDays()
              + " days for "
              + floor.dataClass()
              + " ("
              + floor.citation()
              + "); a longer period is allowed, a shorter one is not",
          List.of(String.valueOf(floor.minDays()), floor.citation()));
    }
  }
}
