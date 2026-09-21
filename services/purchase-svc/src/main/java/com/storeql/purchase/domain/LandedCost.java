package com.storeql.purchase.domain;

import com.storeql.ids.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Landed cost (07.x): freight, duty, insurance and the like, charged against a goods receipt after
 * the goods and spread over its lines, so the stock carries what it really cost to bring it in.
 *
 * <p>Pure: what a charge is, how it is apportioned, and what must be true of one. The apportionment
 * is exact — the lines always sum to the charge — with the rounding remainder placed on the
 * heaviest line, so a charge of 1.00 over three equal lines is 0.34, 0.33, 0.33 and never 0.99.
 */
public final class LandedCost {

  private LandedCost() {}

  public static final String STATUS_APPLIED = "APPLIED";
  public static final String STATUS_REVERSED = "REVERSED";

  public static final String BY_VALUE = "BY_VALUE";
  public static final String BY_QUANTITY = "BY_QUANTITY";

  public static final Set<String> CHARGE_TYPES =
      Set.of("FREIGHT", "DUTY", "INSURANCE", "HANDLING", "OTHER");
  public static final Set<String> BASES = Set.of(BY_VALUE, BY_QUANTITY);

  /** Places in a unit uplift: finer than money, because a unit's share of a charge usually is. */
  public static final int PER_UNIT_SCALE = 4;

  /** A charge against a receipt, as applied; never edited, reversed with a reason. */
  public record Charge(
      UUID id,
      UUID tenantId,
      UUID grId,
      UUID poId,
      UUID storeId,
      String chargeType,
      String basis,
      String currency,
      BigDecimal amount,
      String reference,
      UUID chargedBy,
      String notes,
      String status,
      Instant appliedAt,
      UUID appliedBy,
      Instant reversedAt,
      UUID reversedBy,
      String reversedReason,
      String idempotencyKey) {

    public boolean applied() {
      return STATUS_APPLIED.equals(status);
    }
  }

  /** One receipt line's share of a charge. */
  public record Line(
      UUID id,
      UUID tenantId,
      UUID landedCostId,
      UUID grLineId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal lineValue,
      BigDecimal amount,
      BigDecimal perUnit) {}

  /**
   * A receipt line with the weight it carries: its quantity, and its value at the order's price.
   */
  public record Weighed(UUID grLineId, UUID variantId, BigDecimal qty, BigDecimal lineValue) {}

  /**
   * Spreads {@code amount} over the receipt's lines by value or by quantity, exactly.
   *
   * <p>Each line's share is rounded to the currency's minor unit; whatever rounding left over
   * (positive or negative, at most a minor unit per line) goes to the heaviest line — the first on
   * a tie — so the lines sum to the charge to the penny. A line whose weight is zero receives
   * nothing.
   *
   * @throws IllegalArgumentException when the weights sum to nothing (a BY_VALUE charge over an
   *     order priced at zero): there is no basis to spread on, and spreading evenly would be a
   *     guess
   */
  public static List<Line> apportion(
      UUID landedCostId,
      UUID tenantId,
      String basis,
      BigDecimal amount,
      String currency,
      List<Weighed> lines) {
    boolean byValue = BY_VALUE.equals(basis);
    BigDecimal total = BigDecimal.ZERO;
    for (Weighed w : lines) {
      total = total.add(weight(w, byValue));
    }
    if (total.signum() <= 0) {
      throw new IllegalArgumentException(
          byValue
              ? "the receipt's lines value to nothing at the order's prices, so there is no value to"
                  + " spread the charge over — apportion BY_QUANTITY instead"
              : "the receipt's lines have no quantity to spread the charge over");
    }
    List<Line> out = new ArrayList<>(lines.size());
    BigDecimal allotted = BigDecimal.ZERO;
    int heaviest = 0;
    for (int i = 0; i < lines.size(); i++) {
      Weighed w = lines.get(i);
      BigDecimal weight = weight(w, byValue);
      if (weight.compareTo(weight(lines.get(heaviest), byValue)) > 0) heaviest = i;
      BigDecimal share =
          Money.round(amount.multiply(weight).divide(total, 10, RoundingMode.HALF_UP), currency);
      allotted = allotted.add(share);
      out.add(line(landedCostId, tenantId, w, share));
    }
    BigDecimal remainder = amount.subtract(allotted);
    if (remainder.signum() != 0) {
      Line h = out.get(heaviest);
      out.set(
          heaviest, line(landedCostId, tenantId, lines.get(heaviest), h.amount().add(remainder)));
    }
    return List.copyOf(out);
  }

  private static BigDecimal weight(Weighed w, boolean byValue) {
    return byValue ? w.lineValue() : w.qty();
  }

  private static Line line(UUID landedCostId, UUID tenantId, Weighed w, BigDecimal amount) {
    return new Line(
        Ids.newId(),
        tenantId,
        landedCostId,
        w.grLineId(),
        w.variantId(),
        w.qty(),
        w.lineValue(),
        amount,
        perUnit(amount, w.qty()));
  }

  /** What one unit's cost rises by: the line's share over its quantity, to four places. */
  public static BigDecimal perUnit(BigDecimal amount, BigDecimal qty) {
    return amount.divide(qty, PER_UNIT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Why a charge cannot be applied, or null when it can. Bean Validation has already refused a
   * missing amount; this is the shape the validator cannot see.
   */
  public static String problem(String chargeType, String basis, BigDecimal amount) {
    if (chargeType == null || !CHARGE_TYPES.contains(chargeType)) {
      return "chargeType must be one of " + new java.util.TreeSet<>(CHARGE_TYPES);
    }
    if (basis == null || !BASES.contains(basis)) {
      return "basis must be one of " + new java.util.TreeSet<>(BASES);
    }
    if (amount == null || amount.signum() <= 0) {
      return "amount must be more than zero";
    }
    return null;
  }
}
