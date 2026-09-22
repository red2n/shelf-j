package com.storeql.tenant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Usage metering and quotas (21.10): what a business does in a billing period, against what its
 * plan includes, and what it owes beyond that.
 *
 * <p>A plan's limits (21.8) count what a business <em>has</em> — stores, staff, products — and are
 * enforced by refusing one more. A meter counts what it <em>does</em>, and most of it must never be
 * refused: a till that stops selling because the month's orders ran out loses the shop the sale and
 * the customer. So use beyond what a plan includes is charged, and only a meter that can be refused
 * without harm may be made a hard ceiling.
 */
public final class Meters {

  private Meters() {}

  public static final String ORDERS = "ORDERS";
  public static final String SMS = "SMS";

  /** Once a business has used this share of what is included, it is told it is getting close. */
  public static final int NEARLY = 80;

  /** And once it has used all of it. */
  public static final int ALL = 100;

  /** Why a period over its allowance was charged nothing. */
  public static final String NOT_CHARGED_TRIAL = "TRIAL";

  public static final String NOT_CHARGED_NOT_PRICED = "NOT_PRICED";

  /**
   * Four places, as the invoice line holds it: a text priced at 0.0350 is a real price, and a line
   * must equal its quantity times its unit exactly. The invoice's total is what is rounded to
   * pence.
   */
  private static final int LINE_SCALE = 4;

  /**
   * Something the platform counts.
   *
   * @param unit one of it, as a person would say it
   * @param refusable whether use beyond a hard allowance may be refused; false for anything a shop
   *     cannot do without mid-shift
   * @param countedBy which service counts it, and from what — for the person maintaining this
   */
  public record Meter(String key, String label, String unit, boolean refusable, String countedBy) {}

  /** Every meter a plan may include. Adding one means adding the service that counts it. */
  public static final List<Meter> CATALOGUE =
      List.of(
          new Meter(
              ORDERS,
              "Orders taken",
              "order",
              false,
              "order-svc: every order placed, online or at the till, once each"),
          new Meter(
              SMS,
              "Text messages",
              "text part",
              true,
              "notification-svc: every text sent, by the parts the carrier charges for; only"
                  + " marketing is ever refused"));

  private static final Map<String, Meter> BY_KEY =
      CATALOGUE.stream().collect(Collectors.toUnmodifiableMap(Meter::key, m -> m));

  /** The meter a key names, or empty when the platform counts no such thing. */
  public static Optional<Meter> meter(String key) {
    return Optional.ofNullable(
        BY_KEY.get(key == null ? "" : key.strip().toUpperCase(java.util.Locale.ROOT)));
  }

  /**
   * What a plan includes of one meter.
   *
   * @param included how many each billing period; null means unlimited
   * @param hard whether use beyond it is refused rather than charged
   */
  public record PlanMeter(String meter, Long included, boolean hard) {

    public boolean unlimited() {
      return included == null;
    }
  }

  /** What one unit beyond the included costs, in one currency, from a date. Never edited. */
  public record MeterPrice(
      UUID id,
      UUID planId,
      String meter,
      String currency,
      BigDecimal unitAmount,
      LocalDate effectiveFrom,
      UUID createdBy,
      Instant createdAt) {}

  /**
   * What was billed for one meter over one period.
   *
   * @param unitAmount null when the plan prices no overage for this meter
   * @param notCharged why an overage was charged nothing, or null
   * @param invoiceId null when there was no invoice to carry it
   */
  public record UsagePeriod(
      UUID id,
      UUID tenantId,
      UUID subscriptionId,
      String meter,
      LocalDate periodStart,
      LocalDate periodEnd,
      long used,
      Long included,
      long overage,
      String currency,
      BigDecimal unitAmount,
      BigDecimal amount,
      String notCharged,
      UUID invoiceId,
      Instant createdAt) {

    public boolean charged() {
      return amount.signum() > 0;
    }
  }

  /** A business crossing a share of what it is allowed, once per meter and period. */
  public record Alert(
      UUID id,
      UUID tenantId,
      String meter,
      LocalDate periodStart,
      int threshold,
      long used,
      long included,
      Instant raisedAt) {}

  /**
   * One meter this period, as the business and the platform read it.
   *
   * @param included null when unlimited, or when the plan does not name the meter
   * @param unitAmount what each one over costs in the business's currency; null when not charged
   * @param estimate what is owed for this period so far, beyond the plan's own price
   */
  public record Reading(
      Meter meter,
      long used,
      Long included,
      boolean hard,
      long over,
      String currency,
      BigDecimal unitAmount,
      BigDecimal estimate) {}

  // ── the arithmetic ──────────────────────────────────────────────────────────

  /** How many of {@code used} are beyond what is included; none when unlimited. */
  public static long overage(long used, Long included) {
    return included == null ? 0 : Math.max(0, used - included);
  }

  /**
   * What one period of one meter comes to.
   *
   * @param price what one over costs, or empty when the plan prices none
   * @param trial whether the period was a trial, which is free whatever was used
   */
  public static Charge charge(long used, Long included, Optional<BigDecimal> price, boolean trial) {
    long over = overage(used, included);
    BigDecimal unit = price.orElse(null);
    if (over == 0) return new Charge(over, unit, zero(), null);
    if (trial) return new Charge(over, unit, zero(), NOT_CHARGED_TRIAL);
    if (unit == null) return new Charge(over, null, zero(), NOT_CHARGED_NOT_PRICED);
    return new Charge(over, unit, line(unit.multiply(BigDecimal.valueOf(over))), null);
  }

  /**
   * What a period comes to.
   *
   * @param unitAmount null when the plan prices no overage
   * @param notCharged why an overage cost nothing, or null
   */
  public record Charge(long over, BigDecimal unitAmount, BigDecimal amount, String notCharged) {}

  /**
   * The thresholds {@code used} has reached of what is included: 80%, then 100%.
   *
   * <p>Reached, not crossed: two orders recorded at the same moment each see only their own, and a
   * rule that fires on the step from one count to the next would miss the threshold between them.
   * Raised idempotently, once per period, a threshold missed that way is raised by the next record.
   *
   * @return empty when nothing is included to reach
   */
  public static List<Integer> reached(long used, Long included) {
    List<Integer> out = new ArrayList<>(2);
    if (included == null || included <= 0) return out;
    for (int threshold : new int[] {NEARLY, ALL}) {
      // Compared as whole numbers: 80% of 7 is 5.6, and the fifth order is not yet 80%.
      if (used * 100 >= included * threshold) out.add(threshold);
    }
    return out;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(LINE_SCALE, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal line(BigDecimal amount) {
    return amount.setScale(LINE_SCALE, RoundingMode.HALF_UP);
  }
}
