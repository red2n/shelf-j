package com.storeql.tenant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Commission.Assignment;
import com.storeql.tenant.domain.Commission.Band;
import com.storeql.tenant.domain.Commission.Earned;
import com.storeql.tenant.domain.Commission.Scheme;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The commission arithmetic, which is the part a person paid on it will check: the bands are
 * marginal, a threshold belongs to the band above it, and nothing earns on a period that sold
 * nothing.
 */
class CommissionTest {

  private static Scheme scheme(String basis, String currency, String... thresholdsAndRates) {
    UUID id = Ids.newId();
    List<Band> bands = new java.util.ArrayList<>();
    for (int i = 0; i < thresholdsAndRates.length; i += 2) {
      bands.add(
          new Band(
              Ids.newId(),
              id,
              new BigDecimal(thresholdsAndRates[i]),
              new BigDecimal(thresholdsAndRates[i + 1])));
    }
    return new Scheme(
        id,
        Ids.newId(),
        "counter",
        basis,
        currency,
        Commission.ACTIVE,
        null,
        null,
        null,
        Instant.now(),
        Ids.newId(),
        bands);
  }

  @Test
  @DisplayName("A flat scheme earns its percentage of the net, rounded like money")
  void flat() {
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    List<Earned> earned = Commission.earn(flat, new BigDecimal("1000.00"));
    assertEquals(1, earned.size());
    assertEquals(new BigDecimal("20.00"), earned.get(0).commission());
    assertEquals(new BigDecimal("20.00"), Commission.total(earned));
    // A third of a penny is rounded once, at the end, and only then.
    assertEquals(
        new BigDecimal("0.34"),
        Commission.total(Commission.earn(flat, new BigDecimal("16.75"))),
        "2% of 16.75 is 0.335");
  }

  @Test
  @DisplayName("Bands are marginal: only the part inside a band earns that band's rate")
  void marginal() {
    Scheme tiered = scheme(Commission.PERCENT_OF_NET, null, "0", "2", "10000", "3");
    List<Earned> earned = Commission.earn(tiered, new BigDecimal("15000.00"));
    assertEquals(2, earned.size());
    assertEquals(new BigDecimal("200.00"), earned.get(0).commission());
    // Both bands' figures come back at money's own scale, whatever scale the band was written at:
    // a statement line reading 10000 beside one reading 5000.00 is the drift that has cost two
    // reports already.
    assertEquals(new BigDecimal("10000.00"), earned.get(0).amountInBand());
    assertEquals(new BigDecimal("0.00"), earned.get(0).thresholdFrom());
    assertEquals(new BigDecimal("150.00"), earned.get(1).commission());
    assertEquals(new BigDecimal("5000.00"), earned.get(1).amountInBand());
    assertEquals(new BigDecimal("350.00"), Commission.total(earned));

    // Exactly at the threshold earns the lower band only: the higher one starts *above* it, so
    // crossing it never re-rates what came before.
    List<Earned> atThreshold = Commission.earn(tiered, new BigDecimal("10000.00"));
    assertEquals(1, atThreshold.size());
    assertEquals(new BigDecimal("200.00"), Commission.total(atThreshold));
    // A penny over pays a penny's worth of the higher rate, not a pound's.
    assertEquals(
        new BigDecimal("200.00"),
        Commission.total(Commission.earn(tiered, new BigDecimal("10000.01"))),
        "3% of a penny rounds to nothing, and the lower band is unchanged");
  }

  @Test
  @DisplayName("A per-unit scheme counts units, and its bands count units too")
  void perUnit() {
    Scheme counter = scheme(Commission.PER_UNIT, "GBP", "0", "1.00", "100", "1.50");
    List<Earned> earned = Commission.earn(counter, new BigDecimal("150"));
    assertEquals(new BigDecimal("175.00"), Commission.total(earned));
    assertEquals(new BigDecimal("100.00"), earned.get(0).commission());
    assertEquals(new BigDecimal("75.00"), earned.get(1).commission());
    // Units keep a quantity's scale, not money's: 100 units, not 100.00 of anything.
    assertEquals(new BigDecimal("100.000"), earned.get(0).amountInBand());
    assertEquals(new BigDecimal("50.000"), earned.get(1).amountInBand());
  }

  @Test
  @DisplayName("A period that sold nothing earns nothing, and neither does a scheme with no bands")
  void nothing() {
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    assertTrue(Commission.earn(flat, BigDecimal.ZERO).isEmpty());
    assertTrue(Commission.earn(flat, new BigDecimal("-500.00")).isEmpty(), "a net refund");
    assertTrue(Commission.earn(flat, null).isEmpty());
    assertTrue(Commission.earn(null, new BigDecimal("100")).isEmpty());
    assertTrue(
        Commission.earn(scheme(Commission.PERCENT_OF_NET, null), new BigDecimal("100")).isEmpty());
    // And the total of nothing is money's zero, not an int's: a statement prints 0.00.
    assertEquals(new BigDecimal("0.00"), Commission.total(List.of()));
  }

  @Test
  @DisplayName("The scheme in force is the latest one effective on or before the day sold")
  void inForce() {
    UUID person = Ids.newId();
    UUID first = Ids.newId();
    UUID second = Ids.newId();
    List<Assignment> assignments =
        List.of(
            assignment(person, first, LocalDate.of(2026, 1, 1)),
            assignment(person, second, LocalDate.of(2026, 4, 1)),
            // Taken off commission in July: the arrangement ended, which is not the absence of one.
            assignment(person, null, LocalDate.of(2026, 7, 1)));
    assertEquals(first, Commission.schemeOn(assignments, LocalDate.of(2026, 3, 31)));
    assertEquals(second, Commission.schemeOn(assignments, LocalDate.of(2026, 4, 1)));
    assertEquals(second, Commission.schemeOn(assignments, LocalDate.of(2026, 6, 30)));
    assertNull(Commission.schemeOn(assignments, LocalDate.of(2026, 7, 1)), "off commission");
    assertNull(
        Commission.schemeOn(assignments, LocalDate.of(2025, 12, 31)),
        "a day before any arrangement earns nothing, rather than the first one ever made");
    assertNull(Commission.schemeOn(List.of(), LocalDate.of(2026, 4, 1)));
  }

  @Test
  @DisplayName("A scheme that could not be paid on is refused, and the reason says what to do")
  void refusals() {
    assertTrue(
        Commission.problem("MARGIN", null, List.of(BigDecimal.ZERO)).contains("PERCENT_OF_NET"));
    assertTrue(Commission.problem(null, null, List.of(BigDecimal.ZERO)).contains("PERCENT_OF_NET"));
    assertTrue(
        Commission.problem(Commission.PER_UNIT, null, List.of(BigDecimal.ZERO))
            .contains("currency"));
    assertTrue(
        Commission.problem(Commission.PERCENT_OF_NET, "GBP", List.of(BigDecimal.ZERO))
            .contains("leave the currency out"));
    assertTrue(
        Commission.problem(Commission.PERCENT_OF_NET, null, List.of()).contains("one rate band"));
    assertTrue(
        Commission.problem(Commission.PERCENT_OF_NET, null, List.of(new BigDecimal("500")))
            .contains("starts at zero"),
        "the first sales of every period would earn nothing, silently");
    assertTrue(
        Commission.problem(
                Commission.PERCENT_OF_NET,
                null,
                List.of(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("100")))
            .contains("undecidable"));
    assertNull(
        Commission.problem(
            Commission.PERCENT_OF_NET, null, List.of(BigDecimal.ZERO, new BigDecimal("10000"))));
    assertNull(Commission.problem(Commission.PER_UNIT, "GBP", List.of(BigDecimal.ZERO)));
  }

  @Test
  @DisplayName("A person's days are cut into segments wherever the arrangement changes")
  void segments() {
    UUID person = Ids.newId();
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    Scheme tiered = scheme(Commission.PERCENT_OF_NET, null, "0", "1", "1000", "5");
    Map<UUID, Scheme> schemes = Map.of(flat.id(), flat, tiered.id(), tiered);
    List<Assignment> assignments =
        List.of(
            assignment(person, flat.id(), LocalDate.of(2026, 9, 1)),
            assignment(person, tiered.id(), LocalDate.of(2026, 9, 16)));
    List<Commission.Day> days =
        List.of(
            day("2026-09-10", "500.00"),
            day("2026-09-15", "500.00"),
            day("2026-09-16", "900.00"),
            day("2026-09-20", "600.00"));

    List<Commission.Segment> segments = Commission.rate(days, assignments, schemes);
    assertEquals(2, segments.size());
    // The old arrangement rates its own days only.
    assertEquals(flat.id(), segments.get(0).schemeId());
    assertEquals(new BigDecimal("1000.00"), segments.get(0).amount());
    assertEquals(new BigDecimal("20.00"), segments.get(0).commission());
    // And the new one starts its band progression from zero: 1000 at 1% then 500 at 5%, never
    // 1500 as though the first scheme's sales had already climbed this scheme's first band.
    assertEquals(tiered.id(), segments.get(1).schemeId());
    assertEquals(new BigDecimal("1500.00"), segments.get(1).amount());
    assertEquals(new BigDecimal("35.00"), segments.get(1).commission());
    assertEquals(LocalDate.of(2026, 9, 16), segments.get(1).from());
    assertEquals(LocalDate.of(2026, 9, 20), segments.get(1).to());
  }

  @Test
  @DisplayName("Days under no arrangement are reported as sales that earned nothing")
  void unearned() {
    UUID person = Ids.newId();
    Scheme flat = scheme(Commission.PERCENT_OF_NET, null, "0", "2");
    List<Assignment> assignments =
        List.of(assignment(person, flat.id(), LocalDate.of(2026, 9, 16)));
    List<Commission.Segment> segments =
        Commission.rate(
            List.of(day("2026-09-10", "400.00"), day("2026-09-20", "100.00")),
            assignments,
            Map.of(flat.id(), flat));
    assertEquals(2, segments.size());
    assertNull(segments.get(0).schemeId(), "before the arrangement began");
    assertEquals(new BigDecimal("400.00"), segments.get(0).amount(), "the sales are still counted");
    assertEquals(new BigDecimal("0.00"), segments.get(0).commission());
    assertEquals(flat.id(), segments.get(1).schemeId());
    assertEquals(new BigDecimal("2.00"), segments.get(1).commission());
    // A scheme the assignment names but nothing holds is the same as no arrangement, never a crash.
    List<Commission.Segment> dangling =
        Commission.rate(List.of(day("2026-09-20", "100.00")), assignments, Map.of());
    assertNull(dangling.get(0).schemeId());
    assertEquals(new BigDecimal("0.00"), dangling.get(0).commission());
    assertTrue(Commission.rate(List.of(), assignments, Map.of()).isEmpty());
  }

  @Test
  @DisplayName("A per-unit arrangement counts the units of its days, not their money")
  void perUnitDays() {
    UUID person = Ids.newId();
    Scheme counter = scheme(Commission.PER_UNIT, "GBP", "0", "0.50");
    List<Commission.Segment> segments =
        Commission.rate(
            List.of(
                new Commission.Day(
                    LocalDate.of(2026, 9, 1), new BigDecimal("900.00"), new BigDecimal("30")),
                new Commission.Day(
                    LocalDate.of(2026, 9, 2), new BigDecimal("300.00"), new BigDecimal("10"))),
            List.of(assignment(person, counter.id(), LocalDate.of(2026, 1, 1))),
            Map.of(counter.id(), counter));
    assertEquals(1, segments.size());
    assertEquals(
        new BigDecimal("40.000"), segments.get(0).amount(), "units, at a quantity's scale");
    assertEquals(new BigDecimal("20.00"), segments.get(0).commission());
  }

  private static Commission.Day day(String on, String net) {
    return new Commission.Day(LocalDate.parse(on), new BigDecimal(net), BigDecimal.ZERO);
  }

  private static Assignment assignment(UUID person, UUID schemeId, LocalDate from) {
    return new Assignment(
        Ids.newId(), Ids.newId(), person, schemeId, from, null, Instant.now(), Ids.newId());
  }
}
