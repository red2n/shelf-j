package com.storeql.purchase.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.LandedCost.Line;
import com.storeql.purchase.domain.LandedCost.Weighed;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The apportionment is exact: the lines always sum to the charge, the rounding remainder sits on
 * the heaviest line, a weightless line gets nothing, and a charge with no basis is refused rather
 * than spread by guesswork.
 */
class LandedCostTest {

  private static final UUID CHARGE = Ids.newId();
  private static final UUID TENANT = Ids.newId();

  private static Weighed line(String qty, String value) {
    return new Weighed(Ids.newId(), Ids.newId(), new BigDecimal(qty), new BigDecimal(value));
  }

  private static BigDecimal sum(List<Line> lines) {
    return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void byValueSpreadsInProportionToWhatTheLinesCost() {
    // 25.00 and 15.00 of goods: freight of 10.00 lands 6.25 and 3.75.
    List<Line> lines =
        LandedCost.apportion(
            CHARGE,
            TENANT,
            LandedCost.BY_VALUE,
            new BigDecimal("10.00"),
            "GBP",
            List.of(line("10", "25.00"), line("5", "15.00")));

    assertEquals(new BigDecimal("6.25"), lines.get(0).amount());
    assertEquals(new BigDecimal("3.75"), lines.get(1).amount());
    assertEquals(new BigDecimal("0.6250"), lines.get(0).perUnit(), "6.25 over ten units");
    assertEquals(new BigDecimal("0.7500"), lines.get(1).perUnit());
    assertEquals(new BigDecimal("10.00"), sum(lines));
  }

  @Test
  void byQuantitySpreadsPerUnitWhateverTheLinesCost() {
    List<Line> lines =
        LandedCost.apportion(
            CHARGE,
            TENANT,
            LandedCost.BY_QUANTITY,
            new BigDecimal("3.00"),
            "GBP",
            List.of(line("10", "1000.00"), line("5", "1.00")));

    assertEquals(new BigDecimal("2.00"), lines.get(0).amount());
    assertEquals(new BigDecimal("1.00"), lines.get(1).amount());
    assertEquals(new BigDecimal("0.2000"), lines.get(0).perUnit());
    assertEquals(new BigDecimal("0.2000"), lines.get(1).perUnit(), "the same per unit, by design");
  }

  @Test
  void theRoundingRemainderSitsOnTheHeaviestLineSoTheLinesSumToTheCharge() {
    // 1.00 over three equal lines is 0.33 each, which is 0.99; the penny goes to the first
    // heaviest.
    List<Line> equal =
        LandedCost.apportion(
            CHARGE,
            TENANT,
            LandedCost.BY_QUANTITY,
            new BigDecimal("1.00"),
            "GBP",
            List.of(line("1", "5.00"), line("1", "5.00"), line("1", "5.00")));
    assertEquals(new BigDecimal("0.34"), equal.get(0).amount());
    assertEquals(new BigDecimal("0.33"), equal.get(1).amount());
    assertEquals(new BigDecimal("0.33"), equal.get(2).amount());
    assertEquals(new BigDecimal("1.00"), sum(equal));

    // And when rounding overshoots, the heaviest line — not the first — gives the penny back.
    List<Line> over =
        LandedCost.apportion(
            CHARGE,
            TENANT,
            LandedCost.BY_VALUE,
            new BigDecimal("1.00"),
            "GBP",
            List.of(line("1", "1.00"), line("1", "1.00"), line("1", "4.00")));
    // 1/6 = 0.17, 0.17, 4/6 = 0.67 -> 1.01; the heaviest line takes the 0.01 off: 0.66.
    assertEquals(new BigDecimal("0.17"), over.get(0).amount());
    assertEquals(new BigDecimal("0.17"), over.get(1).amount());
    assertEquals(new BigDecimal("0.66"), over.get(2).amount());
    assertEquals(new BigDecimal("1.00"), sum(over));
  }

  @Test
  void aWeightlessLineGetsNothingAndAZeroMinorUnitCurrencyRoundsToWholeUnits() {
    List<Line> lines =
        LandedCost.apportion(
            CHARGE,
            TENANT,
            LandedCost.BY_VALUE,
            new BigDecimal("100"),
            "JPY",
            List.of(line("3", "0"), line("3", "700"), line("1", "300")));

    assertEquals(0, lines.get(0).amount().signum());
    assertEquals(0, new BigDecimal("70").compareTo(lines.get(1).amount()));
    assertEquals(0, new BigDecimal("30").compareTo(lines.get(2).amount()));
    assertEquals(0, new BigDecimal("100").compareTo(sum(lines)));
  }

  @Test
  void aChargeWithNoBasisIsRefusedNotSpreadEvenly() {
    IllegalArgumentException byValue =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LandedCost.apportion(
                    CHARGE,
                    TENANT,
                    LandedCost.BY_VALUE,
                    new BigDecimal("5.00"),
                    "GBP",
                    List.of(line("4", "0.00"), line("6", "0.00"))));
    assertEquals(true, byValue.getMessage().contains("BY_QUANTITY"), byValue.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            LandedCost.apportion(
                CHARGE, TENANT, LandedCost.BY_QUANTITY, new BigDecimal("5.00"), "GBP", List.of()));
  }

  @Test
  void theShapeAValidatorCannotSeeIsNamed() {
    assertNull(LandedCost.problem("FREIGHT", "BY_VALUE", new BigDecimal("1.00")));
    assertNotNull(LandedCost.problem("POSTAGE", "BY_VALUE", new BigDecimal("1.00")));
    assertNotNull(LandedCost.problem("DUTY", "EVENLY", new BigDecimal("1.00")));
    assertNotNull(LandedCost.problem("DUTY", "BY_QUANTITY", new BigDecimal("0.00")));
    assertNotNull(LandedCost.problem(null, null, null));
  }
}
