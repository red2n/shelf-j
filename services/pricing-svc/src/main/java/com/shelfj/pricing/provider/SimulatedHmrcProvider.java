package com.shelfj.pricing.provider;

import com.shelfj.ids.Ids;
import com.shelfj.pricing.domain.Domain.VatObligation;
import com.shelfj.pricing.domain.Domain.VatRegistration;
import com.shelfj.pricing.domain.Domain.VatReturn;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HMRC as its sandbox behaves (18.5): quarterly obligations, the nine-box rules the API enforces
 * ({@code box3 = box1 + box2}, {@code box5 = |box3 − box4|}, whole pounds in boxes 6–9), one
 * accepted return per period, and a form bundle number back. No credentials, no network. It is what
 * a stack with no HMRC application files with, and what the tests file with.
 */
@ApplicationScoped
public class SimulatedHmrcProvider implements VatSubmissionProvider {

  @Override
  public String name() {
    return VatRegistration.PROVIDER_SIMULATED;
  }

  @Override
  public boolean isConfigured() {
    return true;
  }

  /** Calendar quarters overlapping the range, keyed the way HMRC keys them: {@code 26A3}. */
  @Override
  public List<VatObligation> obligations(
      VatRegistration registration, Instant from, Instant to, Set<String> filedPeriodKeys) {
    List<VatObligation> out = new ArrayList<>();
    LocalDate start = from.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    start = start.withMonth(((start.getMonthValue() - 1) / 3) * 3 + 1);
    LocalDate end = to.atZone(ZoneOffset.UTC).toLocalDate();
    for (LocalDate q = start; !q.isAfter(end); q = q.plusMonths(3)) {
      LocalDate qEnd = q.plusMonths(3).minusDays(1);
      String key = periodKey(q);
      boolean filed = filedPeriodKeys.contains(key);
      out.add(
          new VatObligation(
              key,
              q.atStartOfDay(ZoneOffset.UTC).toInstant(),
              qEnd.atStartOfDay(ZoneOffset.UTC).toInstant(),
              // Due one month and seven days after the period ends: 30 June files by 7 August.
              qEnd.plusDays(1).plusMonths(1).plusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant(),
              filed ? VatObligation.STATUS_FULFILLED : VatObligation.STATUS_OPEN,
              null));
    }
    return out;
  }

  /** {@code <yy>A<quarter>} — the shape HMRC's sandbox uses. */
  public static String periodKey(LocalDate quarterStart) {
    int q = (quarterStart.getMonthValue() - 1) / 3 + 1;
    return String.format("%02dA%d", quarterStart.getYear() % 100, q);
  }

  @Override
  public Receipt submit(
      VatRegistration registration,
      String periodKey,
      VatReturn b,
      Map<String, String> clientHeaders) {
    if (periodKey == null || !periodKey.matches("[0-9]{2}[A-Z][0-9]|[A-Z0-9#]{4}")) {
      throw new ProviderException(
          "PERIOD_KEY_INVALID", "periodKey is not an obligation key", false);
    }
    if (b.box1().add(b.box2()).compareTo(b.box3()) != 0) {
      throw new ProviderException(
          "VAT_TOTAL_VALUE", "totalVatDue must equal vatDueSales + vatDueAcquisitions", false);
    }
    if (b.box3().subtract(b.box4()).abs().compareTo(b.box5()) != 0) {
      throw new ProviderException(
          "VAT_NET_VALUE", "netVatDue must equal |totalVatDue - vatReclaimedCurrPeriod|", false);
    }
    for (BigDecimal whole : List.of(b.box6(), b.box7(), b.box8(), b.box9())) {
      if (whole.stripTrailingZeros().scale() > 0) {
        throw new ProviderException(
            "INVALID_MONETARY_AMOUNT", "boxes 6 to 9 are whole pounds", false);
      }
    }
    if (b.box5().signum() < 0 || b.box1().signum() < 0 || b.box4().signum() < 0) {
      throw new ProviderException("INVALID_MONETARY_AMOUNT", "a box cannot be negative", false);
    }
    Instant now = Instant.now();
    String bundle =
        String.format(
            "%012d", Math.abs(Ids.newId().getLeastSignificantBits() % 1_000_000_000_000L));
    return new Receipt(
        now,
        bundle,
        b.box5().signum() == 0 ? null : "BANK",
        b.box3().compareTo(b.box4()) > 0 ? "X" + bundle.substring(0, 11) : null,
        Ids.newId().toString(),
        now);
  }
}
