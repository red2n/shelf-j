package com.shelfj.pricing.service;

import com.shelfj.pricing.domain.Domain.BasketLine;
import com.shelfj.pricing.domain.Domain.LineDiscount;
import com.shelfj.pricing.domain.Domain.Promotion;
import com.shelfj.pricing.domain.Domain.PromotionOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides which promotions apply to a basket, and what each one takes off.
 *
 * <p><b>No database, no clock, no tenant.</b> Everything it needs arrives as arguments, which is
 * the point: the engine it replaces was three branches inside a price-resolution method, reachable
 * only through a Postgres container, and its behaviour was consequently asserted almost nowhere.
 * This class is a pure function over a basket and a candidate list, so the interesting cases —
 * which of two overlapping offers wins, what an exclusive one suppresses, whether a threshold is
 * measured before or after the discounts above it — are ordinary unit tests.
 *
 * <p><b>The basket is the unit, not the line.</b> The old engine priced each line with an
 * independent call, so no rule that needed to see the whole order could exist: spend thresholds,
 * basket percentages and buy-one-get-one were not unimplemented so much as unexpressible.
 *
 * <h2>Order of application</h2>
 *
 * <ol>
 *   <li>Line-level promotions ({@code PERCENT}, {@code FLAT}, {@code BOGO}) in {@code priority}
 *       order, each against the line's <em>original</em> price.
 *   <li>Basket-level promotions ({@code BASKET_PERCENT}, {@code BASKET_FLAT}, {@code
 *       SPEND_THRESHOLD}) in {@code priority} order, each against the subtotal that remains after
 *       step 1.
 * </ol>
 *
 * <p>Line-level rules compound against the original price rather than against each other's output,
 * so two 10% offers on one line take 20%, not 19%. That is the arithmetic a shopper expects and the
 * one a manager means when they set both; the alternative is defensible but has to be chosen
 * deliberately rather than fallen into, and the guard below stops the compounding running past the
 * price.
 *
 * <p><b>Nothing may drive a line or a basket below zero.</b> Each stage clamps, and the clamp is
 * per line rather than global, so a large flat discount on a cheap item cannot silently eat into
 * another line's value.
 */
@ApplicationScoped
public class PromotionEngine {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Ties break on id, not on insertion order, so the same basket always prices the same way. */
  private static final Comparator<Promotion> BY_PRIORITY =
      Comparator.comparingInt(Promotion::priority).thenComparing(p -> p.id().toString());

  /**
   * Prices a basket.
   *
   * @param lines the basket, already resolved to base unit prices
   * @param candidates every promotion live for this tenant, store, channel and instant — the caller
   *     has already filtered on those, because they are database questions and this is not
   * @param scopedVariants for each promotion, the variants it is scoped to; an entry that is absent
   *     or empty means the promotion applies to everything (scope ALL)
   * @param presentedCoupons coupon codes the customer offered, matched case-insensitively
   * @param exhausted promotions whose usage caps are already spent, so they can be reported as
   *     rejected rather than silently skipped
   * @return what applies, what it takes off, and which coupons did not work and why
   */
  public PromotionOutcome apply(
      List<BasketLine> lines,
      List<Promotion> candidates,
      Map<UUID, Set<UUID>> scopedVariants,
      List<String> presentedCoupons,
      Map<UUID, String> exhausted) {

    Set<String> offered =
        presentedCoupons == null
            ? Set.of()
            : presentedCoupons.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

    Map<String, String> rejected = new LinkedHashMap<>();
    // Every code the customer typed starts rejected and is cleared as it is honoured. Building it
    // this way round means a code that matches nothing at all still gets an answer, which is the
    // case a "did my voucher work?" support call is always about.
    for (String c : offered) rejected.put(c, "NO_SUCH_COUPON");

    List<Promotion> ordered = new ArrayList<>(candidates);
    ordered.sort(BY_PRIORITY);

    List<LineDiscount> lineDiscounts = new ArrayList<>();
    List<LineDiscount> basketDiscounts = new ArrayList<>();
    List<UUID> applied = new ArrayList<>();

    // Remaining value per line, so the clamp and the basket subtotal both stay honest as
    // discounts accumulate.
    Map<UUID, BigDecimal> lineTotals = new LinkedHashMap<>();
    for (BasketLine l : lines) lineTotals.merge(l.variantId(), lineTotal(l), BigDecimal::add);
    Map<UUID, BigDecimal> remaining = new LinkedHashMap<>(lineTotals);

    boolean stopped = false;
    for (Promotion p : ordered) {
      if (stopped) break;
      if (p.isBasketLevel()) continue; // second pass
      if (!admissible(p, offered, exhausted, rejected)) continue;

      List<LineDiscount> got = applyLineLevel(p, lines, scopedVariants, remaining);
      if (got.isEmpty()) continue;

      lineDiscounts.addAll(got);
      applied.add(p.id());
      if (p.requiresCoupon()) rejected.remove(p.couponCode().toUpperCase(Locale.ROOT));
      if (p.exclusive()) stopped = true;
    }

    BigDecimal subtotal = remaining.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

    for (Promotion p : ordered) {
      if (stopped) break;
      if (!p.isBasketLevel()) continue;
      if (!admissible(p, offered, exhausted, rejected)) continue;
      if (subtotal.signum() <= 0) continue;

      BigDecimal amount = basketAmount(p, subtotal);
      if (amount.signum() <= 0) continue;

      basketDiscounts.add(new LineDiscount(null, p.id(), p.name(), amount));
      subtotal = subtotal.subtract(amount);
      applied.add(p.id());
      if (p.requiresCoupon()) rejected.remove(p.couponCode().toUpperCase(Locale.ROOT));
      if (p.exclusive()) stopped = true;
    }

    return new PromotionOutcome(lineDiscounts, basketDiscounts, applied, rejected);
  }

  /**
   * Whether a promotion is allowed to be considered at all, recording why not when a coupon was
   * offered for it. A promotion the customer did not ask for and cannot have is simply skipped —
   * only codes they actually typed earn an explanation.
   */
  private static boolean admissible(
      Promotion p, Set<String> offered, Map<UUID, String> exhausted, Map<String, String> rejected) {
    String code = p.requiresCoupon() ? p.couponCode().toUpperCase(Locale.ROOT) : null;

    if (code != null && !offered.contains(code)) return false; // not presented; not an error

    String spent = exhausted == null ? null : exhausted.get(p.id());
    if (spent != null) {
      if (code != null) rejected.put(code, spent);
      return false;
    }
    // The code exists and is usable: downgrade it from NO_SUCH_COUPON to "did not apply", which
    // is a different fact and the one worth telling a shopper who added the wrong item.
    if (code != null) rejected.put(code, "NOT_APPLICABLE");
    return true;
  }

  /** Line-level discounts for one promotion, clamped so no line can be driven below zero. */
  private static List<LineDiscount> applyLineLevel(
      Promotion p,
      List<BasketLine> lines,
      Map<UUID, Set<UUID>> scopedVariants,
      Map<UUID, BigDecimal> remaining) {

    Set<UUID> scope = scopedVariants == null ? null : scopedVariants.get(p.id());
    List<LineDiscount> out = new ArrayList<>();

    if (Promotion.TYPE_BOGO.equals(p.type())) {
      // BOGO counts across every line the promotion is scoped to, not within one line: three
      // different shirts on a buy-2-get-1 is the case a per-line implementation gets wrong, and
      // it is the common one.
      BigDecimal qty = BigDecimal.ZERO;
      for (BasketLine l : lines) if (inScope(l, scope)) qty = qty.add(l.qty());

      BigDecimal group = p.buyQty().add(p.getQty());
      if (qty.compareTo(group) < 0) return out;

      // Whole completed groups only. A basket of 5 on buy-2-get-1 discounts one unit, not one
      // and two thirds.
      BigDecimal sets = qty.divide(group, 0, RoundingMode.DOWN);
      BigDecimal free = sets.multiply(p.getQty());

      // Cheapest units are the ones given away — the convention every retailer uses, and the one
      // that keeps a mixed-price basket from being gamed by adding an expensive item.
      List<BasketLine> byPrice =
          lines.stream()
              .filter(l -> inScope(l, scope))
              .sorted(Comparator.comparing(BasketLine::unitPrice))
              .toList();

      BigDecimal left = free;
      for (BasketLine l : byPrice) {
        if (left.signum() <= 0) break;
        BigDecimal take = left.min(l.qty());
        BigDecimal amount =
            l.unitPrice()
                .multiply(take)
                .multiply(p.getDiscountPct())
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        amount = clamp(amount, remaining, l.variantId());
        if (amount.signum() > 0) out.add(new LineDiscount(l.variantId(), p.id(), p.name(), amount));
        left = left.subtract(take);
      }
      return out;
    }

    for (BasketLine l : lines) {
      if (!inScope(l, scope)) continue;
      BigDecimal amount =
          switch (p.type()) {
            case Promotion.TYPE_PERCENT ->
                lineTotal(l).multiply(p.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            // FLAT is per unit, matching the old engine, which subtracted it from a unit price.
            case Promotion.TYPE_FLAT ->
                p.value().multiply(l.qty()).setScale(2, RoundingMode.HALF_UP);
            default -> BigDecimal.ZERO;
          };
      amount = clamp(amount, remaining, l.variantId());
      if (amount.signum() > 0) out.add(new LineDiscount(l.variantId(), p.id(), p.name(), amount));
    }
    return out;
  }

  /** What a basket-level promotion takes off a subtotal, or zero if its threshold is not met. */
  private static BigDecimal basketAmount(Promotion p, BigDecimal subtotal) {
    // The threshold is checked for every basket-level type, not only SPEND_THRESHOLD: a
    // minOrderAmount set on a basket percentage means the same thing and was previously ignored
    // on all of them alike.
    if (p.minOrderAmount() != null && subtotal.compareTo(p.minOrderAmount()) < 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal amount =
        switch (p.type()) {
          case Promotion.TYPE_BASKET_PERCENT ->
              subtotal.multiply(p.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
          case Promotion.TYPE_BASKET_FLAT, Promotion.TYPE_SPEND_THRESHOLD ->
              p.value().setScale(2, RoundingMode.HALF_UP);
          default -> BigDecimal.ZERO;
        };
    // Never more than is left to discount.
    return amount.min(subtotal).max(BigDecimal.ZERO);
  }

  /**
   * Takes {@code amount} off the line's remaining value, returning what was actually available. The
   * clamp is per line so an oversized flat discount on one item cannot spill into another.
   */
  private static BigDecimal clamp(
      BigDecimal amount, Map<UUID, BigDecimal> remaining, UUID variantId) {
    BigDecimal left = remaining.getOrDefault(variantId, BigDecimal.ZERO);
    BigDecimal taken = amount.min(left).max(BigDecimal.ZERO);
    remaining.put(variantId, left.subtract(taken));
    return taken;
  }

  /** An absent or empty scope means "everything" — the ALL scope, without a special case. */
  private static boolean inScope(BasketLine l, Set<UUID> scope) {
    return scope == null || scope.isEmpty() || scope.contains(l.variantId());
  }

  private static BigDecimal lineTotal(BasketLine l) {
    return l.unitPrice().multiply(l.qty()).setScale(2, RoundingMode.HALF_UP);
  }
}
