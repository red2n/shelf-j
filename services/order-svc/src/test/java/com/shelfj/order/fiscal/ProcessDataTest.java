package com.shelfj.order.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The strings a German security module signs are fixed by DSFinV-K Anlage I; a receipt whose
 * process data is shaped differently is a receipt the inspector's tooling rejects.
 */
class ProcessDataTest {

  private static SaleFigures sale(
      List<SaleFigures.RateAmount> rates, List<SaleFigures.TenderAmount> tenders) {
    return new SaleFigures(
        UUID.randomUUID(), "EUR", Instant.parse("2026-09-12T10:00:00Z"), rates, tenders);
  }

  @Test
  void aStandardRatedCashSaleIsBelegWithFivePositionsAndBar() {
    var s =
        sale(
            List.of(new SaleFigures.RateAmount(new BigDecimal("19.00"), new BigDecimal("11.90"))),
            List.of(new SaleFigures.TenderAmount("CASH", new BigDecimal("11.90"))));
    assertEquals("Beleg^11.90_0.00_0.00_0.00_0.00^11.90:Bar", ProcessData.kassenbeleg(s));
  }

  @Test
  void mixedRatesLandInTheirPositionsAndSplitTendersAreListedInOrder() {
    var s =
        sale(
            List.of(
                new SaleFigures.RateAmount(new BigDecimal("19.00"), new BigDecimal("11.90")),
                new SaleFigures.RateAmount(new BigDecimal("7.00"), new BigDecimal("5.35")),
                new SaleFigures.RateAmount(new BigDecimal("0.00"), new BigDecimal("2.00"))),
            List.of(
                new SaleFigures.TenderAmount("CASH", new BigDecimal("10.00")),
                new SaleFigures.TenderAmount("CARD", new BigDecimal("9.25"))));
    assertEquals(
        "Beleg^11.90_5.35_0.00_0.00_2.00^10.00:Bar_9.25:Unbar", ProcessData.kassenbeleg(s));
  }

  @Test
  void aRateTheSchemeDoesNotKnowGoesToTheNearestPositionRatherThanVanishing() {
    // 20% (a UK line rung up at a German store by mistake) is nearest 19%.
    assertEquals(1, ProcessData.position(new BigDecimal("20.00")));
    assertEquals(2, ProcessData.position(new BigDecimal("6.50")));
    assertEquals(5, ProcessData.position(BigDecimal.ZERO));
    assertEquals(3, ProcessData.position(new BigDecimal("10.7")));
  }

  @Test
  void noTenderInTheLedgerReportsTheWholeAmountAsNonCash() {
    var s =
        sale(
            List.of(new SaleFigures.RateAmount(new BigDecimal("19.00"), new BigDecimal("3.00"))),
            List.of());
    assertTrue(ProcessData.kassenbeleg(s).endsWith("^3.00:Unbar"));
  }

  @Test
  void theQrPayloadHasTheTwelveFieldsInTheLegalOrder() {
    String qr =
        ProcessData.qr(
            "till-1",
            "Kassenbeleg-V1",
            "Beleg^1.00_0.00_0.00_0.00_0.00^1.00:Bar",
            7,
            42,
            Instant.parse("2026-09-12T10:00:00Z"),
            Instant.parse("2026-09-12T10:00:05Z"),
            "ecdsa-plain-SHA256",
            "unixTime",
            "SIG==",
            "PUB==");
    String[] f = qr.split(";");
    assertEquals(12, f.length);
    assertEquals("V0", f[0]);
    assertEquals("till-1", f[1]);
    assertEquals("Kassenbeleg-V1", f[2]);
    assertEquals("7", f[4]);
    assertEquals("42", f[5]);
    assertEquals("SIG==", f[10]);
    assertEquals("PUB==", f[11]);
  }

  @Test
  void cloudRateNamesFollowThePositions() {
    assertEquals("NORMAL", ProcessData.cloudVatRateName(1));
    assertEquals("REDUCED_1", ProcessData.cloudVatRateName(2));
    assertEquals("NULL", ProcessData.cloudVatRateName(5));
  }
}
