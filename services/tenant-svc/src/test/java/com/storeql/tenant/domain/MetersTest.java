package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.tenant.domain.Meters.Charge;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a period of metered use comes to (21.10). The cases an invoice gets argued about: exactly
 * the allowance, one over, a price with four places, the trial, the plan that prices nothing — and
 * when a business is told it is getting close.
 */
class MetersTest {

  private static Optional<BigDecimal> price(String amount) {
    return Optional.of(new BigDecimal(amount));
  }

  @Test
  @DisplayName("Nothing is over until the allowance is passed, and unlimited is never over")
  void overage() {
    assertEquals(0, Meters.overage(1000, 1000L));
    assertEquals(1, Meters.overage(1001, 1000L));
    assertEquals(5, Meters.overage(5, 0L));
    assertEquals(0, Meters.overage(1_000_000, null));
  }

  @Test
  @DisplayName("One over is charged one unit, to four places, exactly")
  void aChargeIsExact() {
    Charge one = Meters.charge(1001, 1000L, price("0.0500"), false);
    assertEquals(1, one.over());
    assertEquals(0, one.amount().compareTo(new BigDecimal("0.0500")));
    assertNull(one.notCharged());
    // A text part at 0.0350, three over: 0.1050, not 0.11. The invoice rounds its total, and a line
    // must equal its quantity times its unit.
    Charge texts = Meters.charge(5, 2L, price("0.0350"), false);
    assertEquals(new BigDecimal("0.1050"), texts.amount());
  }

  @Test
  @DisplayName("Within the allowance nothing is owed, and nothing needs explaining")
  void withinTheAllowance() {
    Charge none = Meters.charge(1000, 1000L, price("0.0500"), false);
    assertEquals(0, none.over());
    assertEquals(0, none.amount().signum());
    assertNull(none.notCharged(), "nothing was over, so nothing was waived");
  }

  @Test
  @DisplayName("A trial is free whatever it used, and says so")
  void aTrialIsFree() {
    Charge trial = Meters.charge(50, 10L, price("1.0000"), true);
    assertEquals(40, trial.over());
    assertEquals(0, trial.amount().signum());
    assertEquals(Meters.NOT_CHARGED_TRIAL, trial.notCharged());
  }

  @Test
  @DisplayName("Over an allowance the plan prices nothing for: recorded, not charged, and why")
  void notPriced() {
    Charge free = Meters.charge(12, 10L, Optional.empty(), false);
    assertEquals(2, free.over());
    assertEquals(0, free.amount().signum());
    assertNull(free.unitAmount());
    assertEquals(Meters.NOT_CHARGED_NOT_PRICED, free.notCharged());
  }

  @Test
  @DisplayName("80% and then 100% are reached by whole numbers, not by rounding")
  void thresholds() {
    // 80% of 7 is 5.6: the fifth is not there yet, the sixth is.
    assertEquals(List.of(), Meters.reached(5, 7L));
    assertEquals(List.of(80), Meters.reached(6, 7L));
    assertEquals(List.of(80, 100), Meters.reached(7, 7L));
    assertEquals(List.of(80, 100), Meters.reached(70, 7L), "far past is still both");
    assertEquals(List.of(), Meters.reached(10, null), "unlimited has nothing to reach");
    assertEquals(List.of(), Meters.reached(10, 0L), "nothing included is not a percentage");
  }

  @Test
  @DisplayName("An order is never refused; a text may be; a key is read in any case")
  void catalogue() {
    assertFalse(Meters.meter("ORDERS").orElseThrow().refusable());
    assertTrue(Meters.meter("sms").orElseThrow().refusable());
    assertTrue(Meters.meter(" Sms ").isPresent());
    assertTrue(Meters.meter("API_CALLS").isEmpty());
    assertTrue(Meters.meter(null).isEmpty());
  }
}
