package com.storeql.tenant.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The work a shop does every day, and the record that it was done (store operations & workforce).
 *
 * <p>The roster says who is in and the clock says they turned up; neither says what they were meant
 * to <em>do</em>. Opening up, counting the float, checking the bins, locking the back door — a shop
 * runs on a list, and a manager who cannot see whether the list was finished is managing by hope.
 *
 * <p><b>One mechanism, not two.</b> A task may carry items: one without them is a single thing to
 * do, one with them <em>is</em> a checklist, finished when every required line is ticked. Separate
 * models for tasks and checklists would have the same fields, the same report and two places to fix
 * a bug.
 *
 * <p>Two rules about time decide everything else. A list falls due on the <b>store's own clock</b>,
 * because a shop's day starts when the shop opens and not at UTC midnight. And an occurrence is
 * <b>generated before it is worked</b>, because a task nobody did has to exist in order to be
 * missed — a list that appeared only when somebody opened it could never report the morning nobody
 * opened it, which is the one thing this is for.
 */
public final class StoreTasks {

  private StoreTasks() {}

  public static final String OPENING = "OPENING";
  public static final String CLOSING = "CLOSING";
  public static final String DAILY = "DAILY";
  public static final String WEEKLY = "WEEKLY";
  public static final String AD_HOC = "AD_HOC";

  /** The kinds a shop schedules. {@code AD_HOC} is raised by hand and falls due once. */
  public static final List<String> KINDS = List.of(OPENING, CLOSING, DAILY, WEEKLY, AD_HOC);

  public static final String ACTIVE = "ACTIVE";
  public static final String WITHDRAWN = "WITHDRAWN";

  public static final String OPEN = "OPEN";
  public static final String DONE = "DONE";
  public static final String SKIPPED = "SKIPPED";
  public static final String MISSED = "MISSED";

  /** One line of a checklist, as the list is written. */
  public record TemplateItem(
      UUID id, UUID templateId, int position, String text, boolean required) {}

  /**
   * A piece of work a shop does on a schedule.
   *
   * @param storeId null for every store the business has, which is the ordinary case
   * @param daysOfWeek ISO day numbers (1 = Monday); empty means every day
   * @param role whose job it is, as the staff assignments name roles; null means anybody on shift
   */
  public record Template(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String title,
      String instructions,
      String kind,
      Set<Integer> daysOfWeek,
      LocalTime dueTime,
      int graceMinutes,
      String role,
      boolean required,
      String status,
      Instant createdAt,
      UUID createdBy,
      Instant withdrawnAt,
      UUID withdrawnBy,
      List<TemplateItem> items) {

    public Template {
      daysOfWeek = daysOfWeek == null ? Set.of() : Set.copyOf(daysOfWeek);
      items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean active() {
      return ACTIVE.equals(status);
    }

    /** A checklist is a task with lines; the distinction is the lines, not a different kind. */
    public boolean checklist() {
      return !items.isEmpty();
    }

    /** Whether this list falls due on a day: an empty set of days means every day. */
    public boolean fallsDueOn(LocalDate day) {
      if (AD_HOC.equals(kind)) return false;
      return daysOfWeek.isEmpty() || daysOfWeek.contains(day.getDayOfWeek().getValue());
    }
  }

  /** One line of an occurrence, with its text carried across from the template. */
  public record InstanceItem(
      UUID id,
      UUID instanceId,
      int position,
      String text,
      boolean required,
      Instant tickedAt,
      UUID tickedBy) {

    public boolean ticked() {
      return tickedAt != null;
    }
  }

  /** One occurrence: this store, this business date. */
  public record Instance(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID templateId,
      LocalDate businessDate,
      Instant dueAt,
      String status,
      String title,
      String kind,
      String role,
      boolean required,
      Instant completedAt,
      UUID completedBy,
      String skippedReason,
      String note,
      Instant createdAt,
      List<InstanceItem> items) {

    public Instance {
      items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean open() {
      return OPEN.equals(status);
    }

    /** Done after it fell due: late, which is not the same as missed, and a report says which. */
    public boolean late() {
      return DONE.equals(status) && completedAt != null && completedAt.isAfter(dueAt);
    }

    /** How many required lines are still to tick; zero when the list can be finished. */
    public long outstanding() {
      return items.stream().filter(i -> i.required() && !i.ticked()).count();
    }
  }

  /**
   * When a list falls due, on the store's own clock.
   *
   * @param timezone the store's IANA zone; an unknown one falls back to UTC rather than throwing,
   *     because a shop with a mistyped timezone still has a list to work and the alternative is a
   *     sweeper that stops for every store
   */
  public static Instant dueAt(LocalDate businessDate, LocalTime dueTime, String timezone) {
    return businessDate.atTime(dueTime).atZone(zone(timezone)).toInstant();
  }

  /** The store's own date now: what "today's list" means in the shop. */
  public static LocalDate businessDate(Instant now, String timezone) {
    return now.atZone(zone(timezone)).toLocalDate();
  }

  private static ZoneId zone(String timezone) {
    if (timezone == null || timezone.isBlank()) return ZoneId.of("UTC");
    try {
      return ZoneId.of(timezone.strip());
    } catch (java.time.DateTimeException e) {
      return ZoneId.of("UTC");
    }
  }

  /**
   * Whether an open occurrence has been missed by now.
   *
   * <p>Grace is the point: a closing check at 22:00 is not missed at 22:01, and a sweeper that said
   * so would cry wolf every night until nobody read it.
   */
  public static boolean missedBy(Instance instance, int graceMinutes, Instant now) {
    return instance.open() && now.isAfter(instance.dueAt().plusSeconds(60L * graceMinutes));
  }

  /**
   * What a day came to, for one store.
   *
   * @param required how many of the day's lists had to be done
   * @param done how many were, {@code late} how many of those after they fell due
   * @param missed how many were never done, {@code skipped} how many were explained away
   */
  public record Day(
      LocalDate businessDate,
      UUID storeId,
      int total,
      int required,
      int done,
      int late,
      int skipped,
      int missed,
      int open) {

    /** Whether the day's required work is settled: nothing required left open or missed. */
    public boolean settled() {
      return open == 0 && missed == 0;
    }
  }

  /** Totals a store's day from its occurrences. */
  public static Day summarise(LocalDate businessDate, UUID storeId, List<Instance> instances) {
    int required = 0;
    int done = 0;
    int late = 0;
    int skipped = 0;
    int missed = 0;
    int open = 0;
    for (Instance i : instances) {
      if (i.required()) required++;
      switch (i.status()) {
        case DONE -> {
          done++;
          if (i.late()) late++;
        }
        case SKIPPED -> skipped++;
        case MISSED -> missed++;
        default -> open++;
      }
    }
    return new Day(
        businessDate, storeId, instances.size(), required, done, late, skipped, missed, open);
  }

  /**
   * What is wrong with a list as somebody asked for it, or null when nothing is.
   *
   * <p>A day number outside 1&ndash;7 is refused rather than ignored: a list quietly scheduled for
   * nothing would read as scheduled and never fall due, which is worse than a refusal.
   */
  public static String problem(String kind, Set<Integer> daysOfWeek, LocalTime dueTime, int grace) {
    if (kind == null || !KINDS.contains(kind)) {
      return "a list is OPENING, CLOSING, DAILY, WEEKLY or AD_HOC";
    }
    if (dueTime == null) return "a list falls due at a time, on the store's own clock";
    if (grace < 0 || grace > 1440) return "grace is between 0 and 1440 minutes";
    if (daysOfWeek != null) {
      List<Integer> wrong = new ArrayList<>();
      for (Integer day : daysOfWeek) {
        if (day == null || day < 1 || day > 7) wrong.add(day);
      }
      if (!wrong.isEmpty()) {
        return "days of the week are 1 (Monday) to 7 (Sunday), and " + wrong + " is not one";
      }
    }
    if (WEEKLY.equals(kind) && (daysOfWeek == null || daysOfWeek.isEmpty())) {
      return "a weekly list says which day it falls due on";
    }
    return null;
  }
}
