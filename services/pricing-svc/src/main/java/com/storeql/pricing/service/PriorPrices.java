package com.storeql.pricing.service;

import com.storeql.pricing.domain.Domain.AppliedPrice;
import com.storeql.pricing.domain.Domain.PriorPrice;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The prior price of a price reduction (03.12), from the applied-price ledger. Pure.
 *
 * <p>Directive 98/6/EC art.6a: the prior price is the lowest price applied during a period of at
 * least 30 days before the reduction was applied. Read strictly, so a "was" price can never be
 * inflated:
 *
 * <ul>
 *   <li>every price applied in the window counts, an earlier reduction's included, so a promotion
 *       re-run within 30 days is measured against the price it was last sold at;
 *   <li>a reduction increased step by step keeps the price before its first step only where every
 *       country whose law reaches the offer has taken up art.6a(5); elsewhere each step is a
 *       reduction of its own, measured against the 30 days before it. Even where it applies, a step
 *       dearer than the one before, or a regular price that moved, starts a new reduction;
 *   <li>a reduction with no applied price recorded before it has no prior price, so it is not
 *       announced as a reduction;
 *   <li>a "reduction" that is not below the prior price is not a reduction to announce;
 *   <li>fewer than 30 days of recorded prices proves nothing about the window, so it is not
 *       announced either;
 *   <li>a window that crosses a span the ledger could not be certain of proves nothing either.
 * </ul>
 */
public final class PriorPrices {

  /** The jurisdiction rule: Directive 98/6/EC art.6a. */
  public static final String PRICE_REDUCTION_PRIOR_PRICE = "PRICE_REDUCTION_PRIOR_PRICE";

  /** Art.6a(5), where taken up: a progressive reduction keeps the price before its first step. */
  public static final String PRICE_REDUCTION_PROGRESSIVE = "PRICE_REDUCTION_PROGRESSIVE";

  /**
   * Art.6a(3), where taken up: goods reduced because they are about to spoil or expire need no
   * prior price.
   */
  public static final String PRICE_REDUCTION_PERISHABLE_EXEMPT =
      "PRICE_REDUCTION_PERISHABLE_EXEMPT";

  public static final Duration WINDOW = Duration.ofDays(30);

  public static final String NOT_REDUCED = "NOT_REDUCED";
  public static final String ANNOUNCEABLE = "ANNOUNCEABLE";
  public static final String NO_HISTORY = "NO_HISTORY";
  public static final String NOT_LOWER = "NOT_LOWER";

  /**
   * Fewer than 30 days of prices recorded before the reduction: nothing proves the lowest price of
   * the window, so the reduction is not announced where art.6a binds. A product priced high a
   * minute before it is "reduced" is exactly this case.
   */
  public static final String SHORT_HISTORY = "SHORT_HISTORY";

  /**
   * Evaluations of this price are still due: a lower price applied since the ledger was last
   * written may not show in it yet.
   */
  public static final String PENDING = "PENDING";

  /** The window crosses a span in which what was offered could not be established. */
  public static final String UNCERTAIN = "UNCERTAIN";

  /**
   * What art.6a asks of one offer, from every country whose law reaches it.
   *
   * @param required whether any of them binds the prior price
   * @param progressive whether every one that binds lets a progressive reduction keep the price
   *     before its first step
   * @param perishableExempt whether every one that binds exempts goods reduced as they near
   *     spoiling or expiry
   */
  public record Rules(boolean required, boolean progressive, boolean perishableExempt) {

    /** When the rules cannot be read: bound, and no member-state relief assumed. */
    public static final Rules STRICT = new Rules(true, false, false);

    /** Where art.6a reaches none of the countries the offer is made in. */
    public static final Rules NOT_BOUND = new Rules(false, false, false);
  }

  private PriorPrices() {}

  /**
   * Whether an offer may be announced as a reduction: it is one and, where art.6a binds, its prior
   * price is known and above it.
   */
  public static boolean announceable(boolean reduced, boolean required, String status) {
    return reduced && (!required || ANNOUNCEABLE.equals(status));
  }

  /**
   * @param ledger the rows recorded for one variant, channel and store, in any order
   * @param price what the shopper is offered now
   * @param regular the regular price now, without promotions
   * @param now the moment the question is asked
   * @param progressive whether art.6a(5) applies to this offer
   */
  public static PriorPrice of(
      List<AppliedPrice> ledger,
      BigDecimal price,
      BigDecimal regular,
      Instant now,
      boolean progressive) {
    if (price == null || regular == null || price.compareTo(regular) >= 0) {
      return new PriorPrice(NOT_REDUCED, null, null, null, false);
    }
    List<AppliedPrice> rows = new ArrayList<>(ledger);
    rows.sort(
        Comparator.comparing(AppliedPrice::appliedFrom).thenComparing(r -> r.id().toString()));
    AppliedPrice latest = rows.isEmpty() ? null : rows.get(rows.size() - 1);
    // What is offered now stands as the last row, whether or not the ledger has caught up with it.
    if (latest == null || !sameOffer(latest, price, regular)) {
      rows.add(
          new AppliedPrice(
              null, null, null, null, null, true, price, null, regular, null, null, now, null, now,
              "NOW"));
    }

    int first = rows.size() - 1;
    while (first > 0 && sameReduction(rows.get(first - 1), rows.get(first), progressive)) {
      first--;
    }
    Instant start = rows.get(first).appliedFrom();
    Instant windowStart = start.minus(WINDOW);

    BigDecimal lowest = null;
    BigDecimal lowestNet = null;
    Instant earliestPriced = null;
    for (int i = 0; i < first; i++) {
      AppliedPrice row = rows.get(i);
      Instant until = rows.get(i + 1).appliedFrom();
      if (!row.priced() || !until.isAfter(windowStart)) {
        continue;
      }
      if (earliestPriced == null) earliestPriced = row.appliedFrom();
      if (lowest == null || row.price().compareTo(lowest) < 0) {
        lowest = row.price();
        lowestNet = row.netPrice();
      }
    }
    if (lowest == null) {
      return new PriorPrice(NO_HISTORY, null, null, start, true);
    }
    boolean shortHistory = earliestPriced.isAfter(windowStart) && !pricedBefore(rows, windowStart);
    if (uncertainWithin(rows, windowStart, now)) {
      return new PriorPrice(UNCERTAIN, lowest, lowestNet, start, shortHistory);
    }
    if (lowest.compareTo(rows.get(rows.size() - 1).price()) <= 0) {
      return new PriorPrice(NOT_LOWER, lowest, lowestNet, start, shortHistory);
    }
    return new PriorPrice(
        shortHistory ? SHORT_HISTORY : ANNOUNCEABLE, lowest, lowestNet, start, shortHistory);
  }

  /** Whether {@code earlier} belongs to the same reduction as {@code later}. */
  private static boolean sameReduction(
      AppliedPrice earlier, AppliedPrice later, boolean progressive) {
    return progressive
        ? continues(earlier, later)
        : later.reduced()
            && earlier.priced()
            && sameOffer(earlier, later.price(), later.regularPrice());
  }

  /**
   * Whether {@code earlier} belongs to the same progressively increasing reduction as {@code
   * later}: both reduced, the same regular price, and the later step no dearer.
   */
  static boolean continues(AppliedPrice earlier, AppliedPrice later) {
    return earlier.reduced()
        && later.reduced()
        && earlier.regularPrice().compareTo(later.regularPrice()) == 0
        && later.price().compareTo(earlier.price()) <= 0;
  }

  /** Whether any span the ledger was not certain of reaches into the window or after it. */
  private static boolean uncertainWithin(
      List<AppliedPrice> rows, Instant windowStart, Instant now) {
    for (int i = 0; i < rows.size(); i++) {
      AppliedPrice row = rows.get(i);
      if (row.uncertainSince() == null) continue;
      Instant until = i + 1 < rows.size() ? rows.get(i + 1).appliedFrom() : now;
      if (until.isAfter(windowStart) && !row.uncertainSince().isAfter(now)) return true;
    }
    return false;
  }

  private static boolean sameOffer(AppliedPrice row, BigDecimal price, BigDecimal regular) {
    return row.priced()
        && row.price().compareTo(price) == 0
        && row.regularPrice().compareTo(regular) == 0;
  }

  private static boolean pricedBefore(List<AppliedPrice> rows, Instant windowStart) {
    return rows.stream().anyMatch(r -> r.priced() && !r.appliedFrom().isAfter(windowStart));
  }
}
