package com.storeql.pricing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.pricing.domain.Domain.AppliedPrice;
import com.storeql.pricing.domain.Domain.PriorPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Art.6a's prior price, read strictly from the applied-price ledger (03.12). */
class PriorPricesTest {

  private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
  private static final BigDecimal VAT = new BigDecimal("1.20");

  private static Instant daysAgo(double days) {
    return NOW.minus(Duration.ofMinutes(Math.round(days * 24 * 60)));
  }

  private static AppliedPrice row(double daysAgo, String price, String regular, Instant uncertain) {
    Instant at = daysAgo(daysAgo);
    boolean priced = price != null;
    BigDecimal gross = priced ? new BigDecimal(price) : null;
    return new AppliedPrice(
        UUID.randomUUID(),
        null,
        null,
        "ONLINE",
        null,
        priced,
        gross,
        priced ? gross.divide(VAT, 2, RoundingMode.HALF_UP) : null,
        priced ? new BigDecimal(regular) : null,
        null,
        priced ? "EUR" : null,
        at,
        uncertain,
        at,
        "TEST");
  }

  /** A ledger built day by day: each step is (days before now, price, regular); null = unpriced. */
  private static List<AppliedPrice> ledger(Object[]... steps) {
    List<AppliedPrice> out = new ArrayList<>();
    for (Object[] s : steps) {
      out.add(
          row(
              ((Number) s[0]).doubleValue(),
              (String) s[1],
              (String) s[2],
              s.length > 3 ? daysAgo(((Number) s[3]).doubleValue()) : null));
    }
    return out;
  }

  private static Object[] at(double daysAgo, String price, String regular) {
    return new Object[] {daysAgo, price, regular};
  }

  /** A row the ledger could not be certain of, from {@code since} days ago. */
  private static Object[] unsure(double daysAgo, String price, String regular, double since) {
    return new Object[] {daysAgo, price, regular, since};
  }

  /** Where art.6a(5) has not been taken up: each step of a reduction stands alone. */
  private static PriorPrice now(List<AppliedPrice> rows, String price, String regular) {
    return PriorPrices.of(rows, new BigDecimal(price), new BigDecimal(regular), NOW, false);
  }

  /** Where it has. */
  private static PriorPrice progressively(List<AppliedPrice> rows, String price, String regular) {
    return PriorPrices.of(rows, new BigDecimal(price), new BigDecimal(regular), NOW, true);
  }

  private static void is(PriorPrice p, String status, String prior) {
    assertEquals(status, p.status());
    if (prior == null) assertNull(p.priorPrice());
    else assertEquals(0, new BigDecimal(prior).compareTo(p.priorPrice()), p.priorPrice() + "");
  }

  @Test
  @DisplayName("No reduction, nothing to announce")
  void notReduced() {
    is(now(ledger(at(40, "10.00", "10.00")), "10.00", "10.00"), PriorPrices.NOT_REDUCED, null);
    is(now(ledger(), "12.00", "10.00"), PriorPrices.NOT_REDUCED, null);
  }

  @Test
  @DisplayName(
      "A reduction after 40 days at 12.00 is announced against 12.00 — and 10.00 before VAT for a till")
  void aPlainReduction() {
    PriorPrice p = now(ledger(at(40, "12.00", "12.00"), at(2, "9.60", "12.00")), "9.60", "12.00");
    is(p, PriorPrices.ANNOUNCEABLE, "12.00");
    assertEquals(0, new BigDecimal("10.00").compareTo(p.priorPriceNet()));
    assertEquals(daysAgo(2), p.reductionStartedAt());
    assertFalse(p.shortHistory());
  }

  @Test
  @DisplayName("A price raised just before the reduction does not raise the prior price")
  void aRaisedPriceIsNotTheReference() {
    PriorPrice p =
        now(
            ledger(at(40, "10.00", "10.00"), at(5, "15.00", "15.00"), at(1, "12.00", "15.00")),
            "12.00",
            "15.00");
    is(p, PriorPrices.NOT_LOWER, "10.00");
  }

  @Test
  @DisplayName("An earlier promotion within 30 days is the lowest price; one outside it is not")
  void earlierReductions() {
    is(
        now(
            ledger(
                at(60, "10.00", "10.00"),
                at(20, "7.00", "10.00"),
                at(15, "10.00", "10.00"),
                at(1, "8.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.NOT_LOWER,
        "7.00");
    is(
        now(
            ledger(
                at(60, "10.00", "10.00"),
                at(40, "5.00", "10.00"),
                at(36, "10.00", "10.00"),
                at(1, "8.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.ANNOUNCEABLE,
        "10.00");
  }

  @Test
  @DisplayName(
      "A deeper step keeps the price before the first only where art.6a(5) is law; elsewhere the"
          + " step before is the prior price")
  void progressive() {
    List<AppliedPrice> steps =
        ledger(
            at(50, "10.00", "10.00"),
            at(10, "9.00", "10.00"),
            at(5, "8.00", "10.00"),
            at(1, "7.00", "10.00"));
    PriorPrice where = progressively(steps, "7.00", "10.00");
    is(where, PriorPrices.ANNOUNCEABLE, "10.00");
    assertEquals(daysAgo(10), where.reductionStartedAt());

    PriorPrice elsewhere = now(steps, "7.00", "10.00");
    is(elsewhere, PriorPrices.ANNOUNCEABLE, "8.00");
    assertEquals(daysAgo(1), elsewhere.reductionStartedAt());
  }

  @Test
  @DisplayName(
      "A shallower reduction after a deeper one is measured against the deeper, everywhere")
  void notProgressive() {
    List<AppliedPrice> steps =
        ledger(at(50, "10.00", "10.00"), at(10, "5.00", "10.00"), at(5, "7.00", "10.00"));
    is(progressively(steps, "7.00", "10.00"), PriorPrices.NOT_LOWER, "5.00");
    is(now(steps, "7.00", "10.00"), PriorPrices.NOT_LOWER, "5.00");
  }

  @Test
  @DisplayName("A regular price changed mid-campaign starts a new reduction")
  void regularPriceChangeBreaksTheRun() {
    List<AppliedPrice> steps =
        ledger(at(50, "10.00", "10.00"), at(20, "9.00", "10.00"), at(5, "11.00", "12.00"));
    for (PriorPrice p :
        List.of(now(steps, "11.00", "12.00"), progressively(steps, "11.00", "12.00"))) {
      assertEquals(daysAgo(5), p.reductionStartedAt());
      is(p, PriorPrices.NOT_LOWER, "9.00");
    }
  }

  @Test
  @DisplayName("The same reduction recorded twice in a row is still one reduction")
  void aRepeatedRowIsOneReduction() {
    PriorPrice p =
        now(
            ledger(at(50, "10.00", "10.00"), at(10, "8.00", "10.00"), at(4, "8.00", "10.00")),
            "8.00",
            "10.00");
    is(p, PriorPrices.ANNOUNCEABLE, "10.00");
    assertEquals(daysAgo(10), p.reductionStartedAt());
  }

  @Test
  @DisplayName("No price recorded before the reduction: no prior price, not announceable")
  void noHistory() {
    is(now(ledger(at(3, "8.00", "10.00")), "8.00", "10.00"), PriorPrices.NO_HISTORY, null);
    is(
        now(ledger(at(40, null, null), at(3, "8.00", "10.00")), "8.00", "10.00"),
        PriorPrices.NO_HISTORY,
        null);
  }

  @Test
  @DisplayName(
      "A ledger that has not caught up: the reduction starts now, measured against what was recorded")
  void aLaggingLedger() {
    PriorPrice p = now(ledger(at(40, "10.00", "10.00")), "8.00", "10.00");
    is(p, PriorPrices.ANNOUNCEABLE, "10.00");
    assertEquals(NOW, p.reductionStartedAt());
  }

  @Test
  @DisplayName("An unpriced gap ends a run, and unpriced rows are never the lowest price")
  void gaps() {
    is(
        progressively(
            ledger(
                at(50, "10.00", "10.00"),
                at(20, "8.00", "10.00"),
                at(10, null, null),
                at(2, "8.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.NOT_LOWER,
        "8.00");
    is(
        now(
            ledger(at(50, "10.00", "10.00"), at(10, null, null), at(2, "8.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.ANNOUNCEABLE,
        "10.00");
  }

  @Test
  @DisplayName(
      "A price that ended exactly as the window opened is outside it; a second later, inside")
  void windowEdges() {
    // 5.00 held from day 60 until day 32 (the reduction starts day 2, so the window opens day 32).
    is(
        now(
            ledger(at(60, "5.00", "10.00"), at(32, "10.00", "10.00"), at(2, "8.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.ANNOUNCEABLE,
        "10.00");
    List<AppliedPrice> rows = ledger(at(60, "5.00", "10.00"), at(2, "8.00", "10.00"));
    AppliedPrice late = row(32, "10.00", "10.00", null);
    rows.add(
        1,
        new AppliedPrice(
            late.id(),
            null,
            null,
            "ONLINE",
            null,
            true,
            late.price(),
            late.netPrice(),
            late.regularPrice(),
            null,
            "EUR",
            late.appliedFrom().plusSeconds(1),
            null,
            NOW,
            "TEST"));
    is(now(rows, "8.00", "10.00"), PriorPrices.NOT_LOWER, "5.00");
  }

  @Test
  @DisplayName("Less than 30 days of history proves no prior price: reported, not announceable")
  void shortHistory() {
    PriorPrice p = now(ledger(at(10, "10.00", "10.00"), at(2, "8.00", "10.00")), "8.00", "10.00");
    is(p, PriorPrices.SHORT_HISTORY, "10.00");
    assertTrue(p.shortHistory());
  }

  @Test
  @DisplayName(
      "A window crossing a span the ledger could not be certain of proves nothing; one clear of it does")
  void uncertainSpans() {
    // Uncertain from day 20 until the certain row on day 15: inside the 30 days before today.
    is(
        now(
            ledger(
                at(60, "10.00", "10.00"),
                unsure(20, "10.00", "10.00", 20),
                at(15, "10.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.UNCERTAIN,
        "10.00");
    // Uncertain from day 50 to day 45: long before the window opened.
    is(
        now(
            ledger(
                at(60, "10.00", "10.00"),
                unsure(50, "10.00", "10.00", 50),
                at(45, "10.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.ANNOUNCEABLE,
        "10.00");
    // Still open: no certain row has closed it, so it reaches today.
    is(
        now(ledger(at(60, "10.00", "10.00"), unsure(40, "10.00", "10.00", 41)), "8.00", "10.00"),
        PriorPrices.UNCERTAIN,
        "10.00");
    // Uncertain inside the reduction itself: its start cannot be trusted either.
    is(
        progressively(
            ledger(
                at(60, "10.00", "10.00"),
                unsure(10, "9.00", "10.00", 10),
                at(9, "9.00", "10.00"),
                at(1, "8.00", "10.00")),
            "8.00",
            "10.00"),
        PriorPrices.UNCERTAIN,
        "10.00");
  }

  @Test
  @DisplayName("Announceable only when reduced and, where the law binds, with a proven prior price")
  void announceable() {
    assertFalse(PriorPrices.announceable(false, false, null));
    assertTrue(PriorPrices.announceable(true, false, null));
    assertTrue(PriorPrices.announceable(true, true, PriorPrices.ANNOUNCEABLE));
    for (String s :
        new String[] {
          PriorPrices.PENDING,
          PriorPrices.UNCERTAIN,
          PriorPrices.NO_HISTORY,
          PriorPrices.SHORT_HISTORY,
          PriorPrices.NOT_LOWER,
          PriorPrices.NOT_REDUCED,
          null,
          "announceable"
        }) {
      assertFalse(PriorPrices.announceable(true, true, s), String.valueOf(s));
    }
    assertEquals(PriorPrices.Rules.STRICT, new PriorPrices.Rules(true, false, false));
  }
}
