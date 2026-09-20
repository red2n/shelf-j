package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.StoreTasks.Day;
import com.storeql.tenant.domain.StoreTasks.Instance;
import com.storeql.tenant.domain.StoreTasks.InstanceItem;
import com.storeql.tenant.domain.StoreTasks.Template;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules a shop's list turns on: which day it falls due, when that is on the store's own clock,
 * when an open list has been missed, and what a day came to.
 */
class StoreTasksTest {

  private static Template template(String kind, Set<Integer> days, String dueTime) {
    return new Template(
        Ids.newId(),
        Ids.newId(),
        null,
        "Open up",
        null,
        kind,
        days,
        LocalTime.parse(dueTime),
        60,
        null,
        true,
        StoreTasks.ACTIVE,
        Instant.now(),
        Ids.newId(),
        null,
        null,
        List.of());
  }

  private static Instance instance(
      String status, Instant dueAt, Instant completedAt, boolean required) {
    return new Instance(
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        Ids.newId(),
        LocalDate.of(2026, 9, 14),
        dueAt,
        status,
        "Open up",
        StoreTasks.OPENING,
        null,
        required,
        completedAt,
        completedAt == null ? null : Ids.newId(),
        null,
        null,
        Instant.now(),
        List.of());
  }

  @Test
  @DisplayName("An empty set of days means every day; a weekly list means only its own")
  void fallsDue() {
    Template daily = template(StoreTasks.DAILY, Set.of(), "08:00");
    for (int d = 14; d <= 20; d++) {
      assertTrue(daily.fallsDueOn(LocalDate.of(2026, 9, d)), "day " + d);
    }
    // 2026-09-18 is a Friday; 2026-09-19 a Saturday.
    Template fridays = template(StoreTasks.WEEKLY, Set.of(5), "17:00");
    assertTrue(fridays.fallsDueOn(LocalDate.of(2026, 9, 18)));
    assertFalse(fridays.fallsDueOn(LocalDate.of(2026, 9, 19)));
    // Raised by hand, so it never falls due on a schedule.
    assertFalse(
        template(StoreTasks.AD_HOC, Set.of(), "09:00").fallsDueOn(LocalDate.of(2026, 9, 18)));
  }

  @Test
  @DisplayName("A list falls due on the store's own clock, not at UTC midnight")
  void dueOnTheStoresClock() {
    LocalDate day = LocalDate.of(2026, 9, 14);
    // 08:00 in London in September is 07:00Z; in Mumbai it is 02:30Z. A shop's day starts when the
    // shop opens, and a list read in UTC would fall due before the staff arrive or after they
    // leave.
    assertEquals(
        Instant.parse("2026-09-14T07:00:00Z"),
        StoreTasks.dueAt(day, LocalTime.parse("08:00"), "Europe/London"));
    assertEquals(
        Instant.parse("2026-09-14T02:30:00Z"),
        StoreTasks.dueAt(day, LocalTime.parse("08:00"), "Asia/Kolkata"));
    // A mistyped zone still has a list to work: UTC rather than an exception that stops the sweeper
    // for every other store too.
    assertEquals(
        Instant.parse("2026-09-14T08:00:00Z"),
        StoreTasks.dueAt(day, LocalTime.parse("08:00"), "Europe/Nowhere"));
    assertEquals(
        Instant.parse("2026-09-14T08:00:00Z"),
        StoreTasks.dueAt(day, LocalTime.parse("08:00"), null));
    // And "today" is the store's today: just before midnight in Sydney is already tomorrow there.
    assertEquals(
        LocalDate.of(2026, 9, 15),
        StoreTasks.businessDate(Instant.parse("2026-09-14T15:00:00Z"), "Australia/Sydney"));
    assertEquals(
        LocalDate.of(2026, 9, 14),
        StoreTasks.businessDate(Instant.parse("2026-09-14T15:00:00Z"), "Europe/London"));
  }

  @Test
  @DisplayName("Grace decides missed, and a list done late is late rather than missed")
  void missedAndLate() {
    Instant due = Instant.parse("2026-09-14T21:00:00Z");
    Instance open = instance(StoreTasks.OPEN, due, null, true);
    // A closing check at 22:00 is not missed at 22:01: a sweeper that said so would cry wolf
    // nightly
    // until nobody read it.
    assertFalse(StoreTasks.missedBy(open, 60, Instant.parse("2026-09-14T21:59:00Z")));
    assertTrue(StoreTasks.missedBy(open, 60, Instant.parse("2026-09-14T22:01:00Z")));
    assertFalse(
        StoreTasks.missedBy(open, 0, due), "exactly at the moment it fell due is not yet past it");
    assertTrue(StoreTasks.missedBy(open, 0, due.plusSeconds(1)));
    // Only an open list can be missed: one already done, skipped or missed is settled.
    Instance done = instance(StoreTasks.DONE, due, due.plusSeconds(3600), true);
    assertFalse(StoreTasks.missedBy(done, 0, Instant.parse("2026-09-15T09:00:00Z")));
    assertTrue(done.late(), "done an hour after it fell due");
    assertFalse(instance(StoreTasks.DONE, due, due.minusSeconds(60), true).late());
  }

  @Test
  @DisplayName("A day's totals separate late from missed, and open from explained")
  void summary() {
    Instant due = Instant.parse("2026-09-14T08:00:00Z");
    List<Instance> day =
        List.of(
            instance(StoreTasks.DONE, due, due.minusSeconds(600), true),
            instance(StoreTasks.DONE, due, due.plusSeconds(600), true),
            instance(StoreTasks.SKIPPED, due, null, true),
            instance(StoreTasks.MISSED, due, null, true),
            instance(StoreTasks.OPEN, due, null, false));
    UUID store = Ids.newId();
    Day summary = StoreTasks.summarise(LocalDate.of(2026, 9, 14), store, day);
    assertEquals(5, summary.total());
    assertEquals(4, summary.required());
    assertEquals(2, summary.done());
    assertEquals(1, summary.late(), "one of the two was after it fell due");
    assertEquals(1, summary.skipped());
    assertEquals(1, summary.missed());
    assertEquals(1, summary.open());
    assertFalse(summary.settled(), "something is open and something was missed");
    Day clean =
        StoreTasks.summarise(
            LocalDate.of(2026, 9, 14),
            store,
            List.of(
                instance(StoreTasks.DONE, due, due, true),
                instance(StoreTasks.SKIPPED, due, null, true)));
    assertTrue(clean.settled(), "explained counts as settled; only open and missed do not");
    assertEquals(0, StoreTasks.summarise(LocalDate.of(2026, 9, 14), store, List.of()).total());
  }

  @Test
  @DisplayName(
      "A checklist is a task with lines, and is finished when the required ones are ticked")
  void checklist() {
    UUID instanceId = Ids.newId();
    List<InstanceItem> items =
        List.of(
            new InstanceItem(
                Ids.newId(), instanceId, 1, "Unlock", true, Instant.now(), Ids.newId()),
            new InstanceItem(Ids.newId(), instanceId, 2, "Count the float", true, null, null),
            new InstanceItem(Ids.newId(), instanceId, 3, "Water the plant", false, null, null));
    Instance list =
        new Instance(
            instanceId,
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            LocalDate.of(2026, 9, 14),
            Instant.parse("2026-09-14T07:00:00Z"),
            StoreTasks.OPEN,
            "Open up",
            StoreTasks.OPENING,
            null,
            true,
            null,
            null,
            null,
            null,
            Instant.now(),
            items);
    assertEquals(1, list.outstanding(), "the float is required and unticked; the plant is not");
    Instance finished =
        new Instance(
            instanceId,
            list.tenantId(),
            list.storeId(),
            list.templateId(),
            list.businessDate(),
            list.dueAt(),
            StoreTasks.OPEN,
            list.title(),
            list.kind(),
            null,
            true,
            null,
            null,
            null,
            null,
            list.createdAt(),
            items.stream()
                .map(
                    i ->
                        i.required() && !i.ticked()
                            ? new InstanceItem(
                                i.id(),
                                i.instanceId(),
                                i.position(),
                                i.text(),
                                true,
                                Instant.now(),
                                Ids.newId())
                            : i)
                .toList());
    assertEquals(0, finished.outstanding());
    // A task with no lines is not a checklist, and has nothing outstanding of its own.
    assertEquals(0, instance(StoreTasks.OPEN, Instant.now(), null, true).outstanding());
  }

  @Test
  @DisplayName("A list nobody could work is refused, and the reason says what to do")
  void refusals() {
    assertTrue(StoreTasks.problem("ROUTINE", Set.of(), LocalTime.NOON, 60).contains("OPENING"));
    assertTrue(StoreTasks.problem(null, Set.of(), LocalTime.NOON, 60).contains("OPENING"));
    assertTrue(
        StoreTasks.problem(StoreTasks.DAILY, Set.of(), null, 60).contains("falls due at a time"));
    assertTrue(
        StoreTasks.problem(StoreTasks.DAILY, Set.of(), LocalTime.NOON, -1).contains("grace"));
    assertTrue(
        StoreTasks.problem(StoreTasks.DAILY, Set.of(), LocalTime.NOON, 2000).contains("grace"));
    assertTrue(
        StoreTasks.problem(StoreTasks.DAILY, Set.of(0), LocalTime.NOON, 60).contains("1 (Monday)"),
        "a day outside the week would read as scheduled and never fall due");
    assertTrue(
        StoreTasks.problem(StoreTasks.DAILY, Set.of(8), LocalTime.NOON, 60).contains("not one"));
    assertTrue(
        StoreTasks.problem(StoreTasks.WEEKLY, Set.of(), LocalTime.NOON, 60).contains("which day"));
    assertNull(StoreTasks.problem(StoreTasks.WEEKLY, Set.of(5), LocalTime.NOON, 60));
    assertNull(StoreTasks.problem(StoreTasks.OPENING, Set.of(), LocalTime.parse("08:00"), 0));
    assertNull(StoreTasks.problem(StoreTasks.AD_HOC, null, LocalTime.NOON, 60));
  }
}
