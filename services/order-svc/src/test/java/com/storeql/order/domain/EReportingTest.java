package com.storeql.order.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.order.domain.EReporting.Content;
import com.storeql.order.domain.EReporting.CrossBorderLine;
import com.storeql.order.domain.EReporting.Day;
import com.storeql.order.domain.EReporting.RateLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a period reports, and what it refuses to report.
 *
 * <p>The cases worth writing are the ones that would go wrong quietly: a basket spanning two rates
 * counted twice, a period with nothing in it going out as nothing at all, and a period that has not
 * ended being reported as if it had.
 */
class EReportingTest {

  private static RateLine rate(String code, String pct, String net, String vat) {
    return new RateLine(code, new BigDecimal(pct), new BigDecimal(net), new BigDecimal(vat));
  }

  @Test
  @DisplayName("A basket spanning two rates is one transaction, not two")
  void oneBasketIsOneOperation() {
    // The count sits on the day and the money on the rates. A count kept per rate would report a
    // loaf and a bottle in one basket as two sales — and the number of operations is a figure the
    // administration reads.
    Day day =
        new Day(
            LocalDate.of(2026, 9, 3),
            1,
            List.of(rate("T0", "0.0000", "1.20", "0.00"), rate("T1", "0.2000", "2.50", "0.50")));
    Content c =
        new Content(
            EReporting.TRANSACTIONS,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 11),
            "EUR",
            List.of(day),
            List.of());

    assertEquals(1, c.transactionCount());
    assertEquals(new BigDecimal("3.70"), c.netTotal());
    assertEquals(new BigDecimal("0.50"), c.vatTotal());
    assertFalse(c.empty());
  }

  @Test
  @DisplayName("A cross-border sale is its own operation, and goes out line by line")
  void crossBorderIsCounted() {
    Content c =
        new Content(
            EReporting.TRANSACTIONS,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 11),
            "EUR",
            List.of(
                new Day(
                    LocalDate.of(2026, 9, 2), 2, List.of(rate("T1", "0.2000", "10.00", "2.00")))),
            List.of(
                new CrossBorderLine(
                    "INV/2026/000014",
                    LocalDate.of(2026, 9, 4),
                    "BE",
                    "BE0123456789",
                    "EUR",
                    new BigDecimal("500.00"),
                    BigDecimal.ZERO)));

    assertEquals(3, c.transactionCount(), "two shop sales and one invoice abroad");
    assertEquals(new BigDecimal("510.00"), c.netTotal());
    String xml = EReporting.payload(c, "FR12345678901", "FR");
    assertTrue(xml.contains("numeroFacture=\"INV/2026/000014\""));
    assertTrue(xml.contains("paysAcquereur=\"BE\""));
    assertTrue(xml.contains("<Journee date=\"2026-09-02\" nombreOperations=\"2\">"));
    assertTrue(xml.contains("tauxTVA=\"0.2000\""));
  }

  @Test
  @DisplayName("A period with nothing in it is reported as nothing, not as silence")
  void anEmptyPeriodStillReports() {
    // A fortnight with no in-scope sales must be distinguishable from a platform that stopped
    // working. The document says so in words.
    Content c =
        new Content(
            EReporting.TRANSACTIONS,
            LocalDate.of(2026, 9, 11),
            LocalDate.of(2026, 9, 21),
            "EUR",
            List.of(),
            List.of());
    assertTrue(c.empty());
    assertEquals(0, c.transactionCount());
    String xml = EReporting.payload(c, "FR12345678901", "FR");
    assertTrue(xml.contains("<Neant>true</Neant>"));
    assertTrue(xml.contains("nombreOperations=\"0\""));
  }

  @Test
  @DisplayName("A refund lowers the day it happened, and the totals with it")
  void refundsAreNegative() {
    Content c =
        new Content(
            EReporting.TRANSACTIONS,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 11),
            "EUR",
            List.of(
                new Day(
                    LocalDate.of(2026, 9, 5),
                    3,
                    List.of(
                        rate("T1", "0.2000", "100.00", "20.00"),
                        rate("T1", "0.2000", "-25.00", "-5.00")))),
            List.of());
    assertEquals(new BigDecimal("75.00"), c.netTotal());
    assertEquals(new BigDecimal("15.00"), c.vatTotal());
  }

  @Test
  @DisplayName("A period is reported once it has ended, and never more than a month at a time")
  void periodsAreChecked() {
    LocalDate asOf = LocalDate.of(2026, 9, 19);
    assertNull(EReporting.periodProblem(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11), asOf));
    // Ending today is ended: the exclusive end is the first day after the period.
    assertNull(EReporting.periodProblem(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 19), asOf));
    assertNotNull(
        EReporting.periodProblem(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 21), asOf),
        "a period that has not ended cannot be reported");
    assertNotNull(
        EReporting.periodProblem(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 11), asOf),
        "a period of no length is not a period");
    assertNotNull(
        EReporting.periodProblem(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 1), asOf),
        "a mistyped date must not sweep a year of sales into one filing");
  }

  @Test
  @DisplayName("A business name with an ampersand in it does not break the document")
  void textIsEscaped() {
    Content c =
        new Content(
            EReporting.PAYMENTS,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 11),
            "EUR",
            List.of(
                new Day(
                    LocalDate.of(2026, 9, 2), 1, List.of(rate("T&1", "0.2000", "1.00", "0.20")))),
            List.of());
    String xml = EReporting.payload(c, "FR<12>", "FR");
    assertTrue(xml.contains("codeTVA=\"T&amp;1\""));
    assertTrue(xml.contains("<NumeroTVA>FR&lt;12&gt;</NumeroTVA>"));
    assertFalse(xml.contains("T&1"));
  }
}
