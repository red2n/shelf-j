package com.shelfj.tenant.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The roster and the time clock (store operations & workforce).
 *
 * <p>A shop's biggest controllable cost is its hours, and the platform had no record of them. Staff
 * were assigned to stores with roles; nothing said who was meant to be in on Tuesday, or who
 * actually was. Everything else in this domain leans on it — labour cost against sales cannot be
 * computed without hours, and an absence cannot be seen without a plan to compare it against.
 *
 * <p><b>This is not a till session.</b> iam-svc's POS session is about the drawer: a cashier may
 * open three tills in one shift and a storekeeper never opens one. Paying from till sessions would
 * pay a cashier for the gaps between tills and pay a storekeeper nothing.
 *
 * <p>Three rules run through the arithmetic. <b>Unpaid breaks come off the hours and paid ones do
 * not</b> — which is the employer's arrangement, kept rather than decided here. <b>An open entry
 * has no hours yet</b>, and is reported as open rather than as zero, because a zero looks like a
 * day nobody worked. And <b>a correction supersedes</b>: hours that can be quietly rewritten are
 * hours nobody can be held to.
 */
public final class Workforce {

  private Workforce() {}

  public static final String PLANNED = "PLANNED";
  public static final String PUBLISHED = "PUBLISHED";
  public static final String CANCELLED = "CANCELLED";
  public static final Set<String> SHIFT_STATUSES = Set.of(PLANNED, PUBLISHED, CANCELLED);

  public static final String SOURCE_CLOCK = "CLOCK";
  public static final String SOURCE_MANAGER = "MANAGER";

  public static final String BREAK_REST = "REST";
  public static final String BREAK_MEAL = "MEAL";
  public static final Set<String> BREAK_KINDS = Set.of(BREAK_REST, BREAK_MEAL);

  /**
   * The daily rest the Working Time Directive asks for: eleven consecutive hours in each 24.
   *
   * <p>A roster that breaks it is <b>flagged, not refused</b>. The directive is implemented member
   * state by member state with its own derogations and collective agreements, and an employer who
   * has one is entitled to roster against it; a platform that refused would be wrong about the law
   * and in the way. What it must not do is stay quiet, because nobody reading a rota spots eleven
   * hours by eye.
   */
  public static final Duration DAILY_REST = Duration.ofHours(11);

  /**
   * How long somebody may work before the directive expects a break: six hours.
   *
   * <p>Flagged for the same reason and with the same caveat. Article 4 leaves the length and the
   * terms to member states; what is not in doubt is that a six-hour stretch with no break is worth
   * saying.
   */
  public static final Duration BREAK_AFTER = Duration.ofHours(6);

  /** A rostered shift: what somebody is meant to work. */
  public record Shift(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID userId,
      Instant startsAt,
      Instant endsAt,
      String duty,
      String status,
      String note,
      String cancelledReason,
      Instant createdAt,
      UUID createdBy,
      Instant updatedAt) {

    public boolean live() {
      return !CANCELLED.equals(status);
    }

    public Duration length() {
      return Duration.between(startsAt, endsAt);
    }

    /** The day a shift belongs to: the day it starts, in UTC, which is how the roster reads. */
    public LocalDate day() {
      return startsAt.atOffset(ZoneOffset.UTC).toLocalDate();
    }
  }

  /** A break inside a worked entry. */
  public record Rest(
      UUID id,
      UUID tenantId,
      UUID timeEntryId,
      Instant startedAt,
      Instant endedAt,
      String kind,
      boolean paid) {

    public boolean open() {
      return endedAt == null;
    }

    public Duration length() {
      return endedAt == null ? Duration.ZERO : Duration.between(startedAt, endedAt);
    }
  }

  /**
   * A worked entry: what somebody actually did.
   *
   * @param shiftId the rostered shift it answers, or null — an unplanned shift and an unworked plan
   *     are both real, so neither side requires the other
   * @param supersedes the entry a correction replaces; both stay on the record
   */
  public record Entry(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID userId,
      UUID shiftId,
      Instant clockedInAt,
      Instant clockedOutAt,
      String source,
      String note,
      String adjustedReason,
      UUID supersedes,
      UUID supersededBy,
      Instant createdAt,
      UUID createdBy,
      List<Rest> breaks) {

    public Entry {
      breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }

    public boolean open() {
      return clockedOutAt == null;
    }

    /** Whether this is the entry that stands, rather than one a correction has replaced. */
    public boolean stands() {
      return supersededBy == null;
    }

    /** The clock time, before breaks: null while the entry is still open. */
    public Duration onSite() {
      return clockedOutAt == null ? null : Duration.between(clockedInAt, clockedOutAt);
    }

    /** How much of the breaks is unpaid, and so comes off the hours. */
    public Duration unpaidBreaks() {
      Duration total = Duration.ZERO;
      for (Rest r : breaks) {
        if (!r.paid()) total = total.plus(r.length());
      }
      return total;
    }

    /**
     * The hours this entry is worth.
     *
     * <p>Null while the entry is open, deliberately: an open entry reported as zero looks like a
     * day nobody worked, and a payroll run must be able to tell the difference.
     */
    public Duration worked() {
      Duration site = onSite();
      if (site == null) return null;
      Duration net = site.minus(unpaidBreaks());
      return net.isNegative() ? Duration.ZERO : net;
    }

    /** The day an entry belongs to: the day it began. */
    public LocalDate day() {
      return clockedInAt.atOffset(ZoneOffset.UTC).toLocalDate();
    }
  }

  /** Something about a roster worth saying out loud, with the instrument that asks for it. */
  public record Concern(String code, String detail) {}

  public static final String REST_SHORT = "DAILY_REST_SHORT";
  public static final String NO_BREAK = "BREAK_EXPECTED";
  public static final String OVERLAPS = "SHIFTS_OVERLAP";

  /**
   * What is worth saying about one person's rostered shifts.
   *
   * <p>Shifts must arrive in order of start. Overlaps are reported first because they are a mistake
   * rather than a judgement: nobody works two places at once, and the rest of the arithmetic would
   * be nonsense if they did.
   */
  public static List<Concern> concerns(List<Shift> ofOnePerson) {
    List<Concern> out = new ArrayList<>();
    Shift previous = null;
    for (Shift s : ofOnePerson) {
      if (!s.live()) continue;
      if (s.length().compareTo(BREAK_AFTER) > 0) {
        out.add(
            new Concern(
                NO_BREAK,
                "the shift on "
                    + s.day()
                    + " runs "
                    + hours(s.length())
                    + " hours, and a break is expected after "
                    + BREAK_AFTER.toHours()
                    + " (Directive 2003/88/EC art. 4, as the member state implements it)"));
      }
      if (previous != null) {
        if (s.startsAt().isBefore(previous.endsAt())) {
          out.add(
              new Concern(
                  OVERLAPS,
                  "the shifts on "
                      + previous.day()
                      + " and "
                      + s.day()
                      + " overlap, and nobody works two places at once"));
        } else {
          Duration rest = Duration.between(previous.endsAt(), s.startsAt());
          if (rest.compareTo(DAILY_REST) < 0) {
            out.add(
                new Concern(
                    REST_SHORT,
                    "only "
                        + hours(rest)
                        + " hours between the shifts on "
                        + previous.day()
                        + " and "
                        + s.day()
                        + ", where "
                        + DAILY_REST.toHours()
                        + " are expected (Directive 2003/88/EC art. 3)"));
          }
        }
      }
      previous = s;
    }
    return out;
  }

  /** Hours to one decimal place, which is how a rota is read and discussed. */
  public static String hours(Duration d) {
    long minutes = Math.max(0, d.toMinutes());
    return (minutes / 60) + "." + ((minutes % 60) * 10 / 60);
  }

  /**
   * One person's day, planned against worked — the shape an attendance report is read in.
   *
   * @param planned the rostered minutes, zero when nobody rostered them
   * @param worked the clocked minutes, zero when they did not turn up
   * @param openEntry true when they are still on the clock, so the worked figure is not final
   * @param lateByMinutes how late the first clock-in was against the roster; negative for early
   */
  public record AttendanceDay(
      LocalDate day,
      UUID userId,
      UUID storeId,
      long planned,
      long worked,
      int entries,
      boolean openEntry,
      Long lateByMinutes) {

    /**
     * Rostered and <em>nothing clocked at all</em>: the case the report exists for.
     *
     * <p>Counted on entries and not on minutes. Somebody who clocked in and straight back out
     * worked no minutes but did turn up, and calling that an absence would put a disciplinary
     * question where a mis-tap is.
     */
    public boolean absent() {
      return planned > 0 && entries == 0;
    }

    /** Clocked with nothing rostered, which is as much a management fact as an absence. */
    public boolean unplanned() {
      return planned == 0 && entries > 0;
    }
  }
}
