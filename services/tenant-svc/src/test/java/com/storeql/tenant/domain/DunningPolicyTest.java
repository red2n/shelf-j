package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.tenant.domain.Dunning.Policy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a policy owes an overdue invoice (21.12).
 *
 * <p>The case worth the test is a run that has not run for a while. A business that was not chased
 * while the platform was not looking is owed each notice it missed — skipping to the latest step
 * would suspend a business the platform never finished telling was late, which is the opposite of
 * what a dunning process is for.
 */
class DunningPolicyTest {

  private static final Policy DEFAULTS = Dunning.DEFAULT_POLICY;

  @Test
  @DisplayName(
      "The defaults are the published ones: remind on 1, 3, 5 and 7, suspend at 14, write off at 30")
  void theDefaultsAreThePublishedOnes() {
    assertEquals(List.of(1, 3, 5, 7), DEFAULTS.reminderDays());
    assertEquals(14, DEFAULTS.suspendAfterDays());
    assertEquals(30, DEFAULTS.uncollectibleAfterDays());
    assertTrue(DEFAULTS.enabled());
    // Nobody set them, and the policy says so rather than inventing an actor to have set them.
    assertEquals(false, DEFAULTS.set());
  }

  @Test
  @DisplayName("Nothing is owed before the due date, or on it")
  void nothingIsOwedYet() {
    assertEquals(List.of(), DEFAULTS.stepsEarnedBy(0));
    assertEquals(List.of(), DEFAULTS.stepsEarnedBy(-3));
  }

  @Test
  @DisplayName("One day late earns the first reminder and nothing else")
  void oneDayLate() {
    assertEquals(List.of("REMINDER_1"), DEFAULTS.stepsEarnedBy(1));
    assertEquals(List.of("REMINDER_1"), DEFAULTS.stepsEarnedBy(2));
  }

  @Test
  @DisplayName("A run that has not run for a week owes every notice that was missed, in order")
  void everyMissedNoticeIsOwed() {
    // The case the class exists for. Not "the reminder for day 7" — all four, so the business is
    // told four times before anything is taken away, which is what it was promised.
    assertEquals(
        List.of("REMINDER_1", "REMINDER_3", "REMINDER_5", "REMINDER_7"), DEFAULTS.stepsEarnedBy(8));
  }

  @Test
  @DisplayName("At fourteen days the service is interrupted, after every reminder has been earned")
  void suspensionComesAfterTheReminders() {
    List<String> steps = DEFAULTS.stepsEarnedBy(14);
    assertEquals(
        List.of("REMINDER_1", "REMINDER_3", "REMINDER_5", "REMINDER_7", "SUSPENDED"), steps);
    assertEquals("SUSPENDED", steps.get(steps.size() - 1), "and last, never before them");
  }

  @Test
  @DisplayName(
      "At thirty the debt is given up on, and the suspension is still on the list before it")
  void writeOffComesLast() {
    List<String> steps = DEFAULTS.stepsEarnedBy(45);
    assertEquals("UNCOLLECTIBLE", steps.get(steps.size() - 1));
    assertTrue(
        steps.indexOf("SUSPENDED") < steps.indexOf("UNCOLLECTIBLE"),
        "giving up on a debt the platform never stopped serving would be the wrong order: "
            + steps);
  }

  @Test
  @DisplayName("A policy switched off owes nothing, however late the invoice is")
  void switchedOffOwesNothing() {
    Policy off = new Policy(false, List.of(1, 3), 14, 30, null, null);

    assertEquals(List.of(), off.stepsEarnedBy(999));
  }

  @Test
  @DisplayName("A platform that chases once and suspends late is obeyed exactly")
  void aPlatformsOwnTolerance() {
    // The defaults are defaults, not a rule. A platform may decide one reminder and sixty days.
    Policy gentle = new Policy(true, List.of(7), 60, 120, null, null);

    assertEquals(List.of(), gentle.stepsEarnedBy(6));
    assertEquals(List.of("REMINDER_7"), gentle.stepsEarnedBy(30));
    assertEquals(List.of("REMINDER_7", "SUSPENDED"), gentle.stepsEarnedBy(60));
    assertEquals(List.of("REMINDER_7", "SUSPENDED", "UNCOLLECTIBLE"), gentle.stepsEarnedBy(200));
  }

  @Test
  @DisplayName("Only non-payment is ever lifted by money")
  void onlyNonPaymentIsLiftedByMoney() {
    // The distinction the whole row rests on. An administrator's decision is not an argument money
    // can win, so the two reasons are separate values and nothing treats them alike.
    assertTrue(Dunning.DEACTIVATION_REASONS.contains(Dunning.NON_PAYMENT));
    assertTrue(Dunning.DEACTIVATION_REASONS.contains(Dunning.ADMINISTRATOR));
    assertEquals(
        2, Dunning.DEACTIVATION_REASONS.size(), "a third reason needs a decision, not a value");
  }
}
