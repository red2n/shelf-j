package com.storeql.tenant.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Chasing an overdue invoice, and taking the platform away when chasing does not work (21.12).
 *
 * <p>Three stages, and the order between them is the whole policy: remind, then interrupt the
 * service, then give up on the debt. Suspending before the last reminder has gone would take the
 * platform away from a business that has not finished being told it is late; giving up before
 * suspending would write off a debt the platform never stopped serving.
 *
 * <p><b>The defaults are from published dunning practice, not invented.</b> Reminders on days 1, 3,
 * 5 and 7 after the due date — three or four attempts over ten to fourteen days recovers most of
 * what is recoverable, and a fifth mostly annoys. Service interrupted at fourteen days, which is
 * long enough that a missed payment is a decision rather than an oversight. The debt given up on at
 * thirty. All configurable, because a platform's tolerance for its own customers is a commercial
 * judgement and not a technical one.
 */
public final class Dunning {

  private Dunning() {}

  // ── the steps ───────────────────────────────────────────────────────────────

  /** A reminder, numbered by which offset in the policy it answers to: {@code REMINDER_3}. */
  public static final String REMINDER = "REMINDER_";

  /** The platform was taken away. */
  public static final String SUSPENDED = "SUSPENDED";

  /** The debt was given up on. */
  public static final String UNCOLLECTIBLE = "UNCOLLECTIBLE";

  /**
   * Somebody promised to pay and the date was moved, which pauses the chase without forgiving it.
   */
  public static final String DUE_DATE_EXTENDED = "DUE_DATE_EXTENDED";

  /** It was paid. Recorded so the file reads as an account of what happened, ending somewhere. */
  public static final String RESOLVED = "RESOLVED";

  /** Why a business is switched off. Only the first is ever lifted by money. */
  public static final String NON_PAYMENT = "NON_PAYMENT";

  public static final String ADMINISTRATOR = "ADMINISTRATOR";

  public static final Set<String> DEACTIVATION_REASONS = Set.of(NON_PAYMENT, ADMINISTRATOR);

  public static String reminder(int day) {
    return REMINDER + day;
  }

  // ── the policy ──────────────────────────────────────────────────────────────

  /**
   * How hard the platform chases.
   *
   * @param reminderDays days after the due date, ascending
   * @param updatedBy null in {@link #DEFAULT_POLICY}, because nobody set it
   */
  public record Policy(
      boolean enabled,
      List<Integer> reminderDays,
      int suspendAfterDays,
      int uncollectibleAfterDays,
      UUID updatedBy,
      Instant updatedAt) {

    public Policy {
      reminderDays = List.copyOf(reminderDays);
    }

    /** Whether this is the platform's own choice or the defaults standing in for one. */
    public boolean set() {
      return updatedBy != null;
    }

    /**
     * Every step this invoice has earned by a day, oldest first.
     *
     * <p>Every step, not just the latest: a business that was not chased while the platform was not
     * looking is owed each notice it missed, once. Skipping to the latest would suspend it without
     * ever having told it, which is the opposite of what a dunning process is for. Bounded by the
     * policy's own length, and the unique index on (invoice, step) means a step already taken is
     * not taken again.
     */
    public List<String> stepsEarnedBy(int daysOverdue) {
      List<String> steps = new ArrayList<>();
      if (!enabled || daysOverdue <= 0) return steps;
      for (int day : reminderDays) {
        if (daysOverdue >= day) steps.add(reminder(day));
      }
      if (daysOverdue >= suspendAfterDays) steps.add(SUSPENDED);
      if (daysOverdue >= uncollectibleAfterDays) steps.add(UNCOLLECTIBLE);
      return steps;
    }
  }

  /**
   * What the platform does until it says otherwise.
   *
   * <p>An absent row means these, rather than meaning dunning is off — the same shape as a business
   * on no plan being unrestricted. A platform that has not thought about dunning still wants its
   * invoices chased; what it would not want is a migration inventing an actor to seed a row with.
   */
  public static final Policy DEFAULT_POLICY =
      new Policy(true, List.of(1, 3, 5, 7), 14, 30, null, null);

  /** One thing that was done about one overdue invoice. */
  public record Event(
      UUID id, UUID invoiceId, String step, String detail, UUID actorId, Instant createdAt) {}

  /**
   * An overdue invoice as the receivables screen shows it.
   *
   * @param daysOverdue against the day the list was asked for
   * @param stage the last step taken, or null when it has not been chased yet
   * @param nextStep what it will earn next, so an operator can see what is about to happen
   */
  public record Overdue(
      UUID invoiceId,
      UUID tenantId,
      String number,
      LocalDate dueDate,
      int daysOverdue,
      String stage,
      String nextStep) {}
}
