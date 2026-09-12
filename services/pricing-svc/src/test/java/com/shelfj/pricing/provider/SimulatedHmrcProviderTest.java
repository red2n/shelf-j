package com.shelfj.pricing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.pricing.domain.Domain.VatObligation;
import com.shelfj.pricing.domain.Domain.VatRegistration;
import com.shelfj.pricing.domain.Domain.VatReturn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The simulator enforces what HMRC's API enforces, so a return the sandbox would refuse is refused
 * here too.
 */
class SimulatedHmrcProviderTest {

  private static final SimulatedHmrcProvider HMRC = new SimulatedHmrcProvider();
  private static final VatRegistration REG =
      new VatRegistration(
          UUID.randomUUID(), "123456782", "SIMULATED", null, null, null, null, null, null);

  private static VatReturn boxes(String b1, String b4, String b6, String b7) {
    BigDecimal box1 = new BigDecimal(b1);
    BigDecimal box4 = new BigDecimal(b4);
    return new VatReturn(
        box1,
        BigDecimal.ZERO,
        box1,
        box4,
        box1.subtract(box4).abs(),
        new BigDecimal(b6),
        new BigDecimal(b7),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "a",
        "b");
  }

  @Test
  void obligationsAreCalendarQuartersKeyedLikeHmrcsAndFulfilledOnceFiled() {
    List<VatObligation> o =
        HMRC.obligations(
            REG,
            Instant.parse("2026-02-10T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            Set.of("26A2"));
    assertEquals(3, o.size());
    assertEquals("26A1", o.get(0).periodKey());
    assertEquals("26A2", o.get(1).periodKey());
    assertEquals("26A3", o.get(2).periodKey());
    assertEquals(VatObligation.STATUS_OPEN, o.get(0).status());
    assertEquals(VatObligation.STATUS_FULFILLED, o.get(1).status());
    assertEquals(Instant.parse("2026-04-01T00:00:00Z"), o.get(1).start());
    assertEquals(Instant.parse("2026-06-30T00:00:00Z"), o.get(1).end());
    assertEquals(Instant.parse("2026-08-07T00:00:00Z"), o.get(1).due());
    assertEquals("26A4", SimulatedHmrcProvider.periodKey(LocalDate.of(2026, 10, 1)));
  }

  @Test
  void aWellFormedReturnIsAcceptedWithAFormBundle() {
    var r = HMRC.submit(REG, "26A1", boxes("100.00", "30.00", "500", "150"), Map.of());
    assertNotNull(r.processingDate());
    assertEquals(12, r.formBundleNumber().length());
    assertEquals("BANK", r.paymentIndicator());
    assertNotNull(r.chargeRefNumber());
    assertNotNull(r.receiptId());
  }

  @Test
  void whatHmrcRefusesTheSimulatorRefusesByTheSameCode() {
    var pence =
        assertThrows(
            VatSubmissionProvider.ProviderException.class,
            () -> HMRC.submit(REG, "26A1", boxes("100.00", "30.00", "500.50", "150"), Map.of()));
    assertEquals("INVALID_MONETARY_AMOUNT", pence.code());
    var badTotal =
        assertThrows(
            VatSubmissionProvider.ProviderException.class,
            () ->
                HMRC.submit(
                    REG,
                    "26A1",
                    new VatReturn(
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("101.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("101.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "a",
                        "b"),
                    Map.of()));
    assertEquals("VAT_TOTAL_VALUE", badTotal.code());
    var badNet =
        assertThrows(
            VatSubmissionProvider.ProviderException.class,
            () ->
                HMRC.submit(
                    REG,
                    "26A1",
                    new VatReturn(
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("100.00"),
                        new BigDecimal("30.00"),
                        new BigDecimal("60.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "a",
                        "b"),
                    Map.of()));
    assertEquals("VAT_NET_VALUE", badNet.code());
    var badKey =
        assertThrows(
            VatSubmissionProvider.ProviderException.class,
            () -> HMRC.submit(REG, "quarter-one", boxes("1.00", "0.00", "5", "0"), Map.of()));
    assertEquals("PERIOD_KEY_INVALID", badKey.code());
  }
}
