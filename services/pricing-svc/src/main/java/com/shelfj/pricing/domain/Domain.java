package com.shelfj.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure domain records — no HTTP, no persistence annotations. */
public final class Domain {

  private Domain() {}

  /**
   * HMRC VAT rate. code: T1=Standard 20%, T5=Reduced 5%, T0=Zero 0%, TX=Exempt. Per HMRC VAT Notice
   * 700.
   */
  public record VatRate(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      BigDecimal rate,
      boolean exempt,
      String description,
      Instant effectiveFrom,
      Instant effectiveTo,
      Instant createdAt) {

    public static final String T1 = "T1";
    public static final String T5 = "T5";
    public static final String T0 = "T0";
    public static final String TX = "TX";
  }

  /** Maps a product variant to its HMRC VAT code (T1/T5/T0/TX). */
  public record ProductVatCategory(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String vatCode,
      Instant effectiveFrom,
      Instant effectiveTo,
      Instant createdAt) {}

  /**
   * B2B customer VAT registration status. VAT number format: GB + 9 digits (e.g. GB123456789).
   * reverseChargeEligible applies to cross-border B2B supplies.
   */
  public record CustomerVatStatus(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String vatNumber,
      boolean vatRegistered,
      boolean reverseChargeEligible,
      String countryCode,
      Instant createdAt,
      Instant updatedAt) {}

  /** Named price list (Standard, Online, POS, VIP, etc.) with an effective date range in GBP. */
  public record PriceList(
      UUID id,
      UUID tenantId,
      String name,
      String channel,
      String currency,
      Instant effectiveFrom,
      Instant effectiveTo,
      boolean active,
      Instant createdAt) {

    public static final String CHANNEL_ALL = "ALL";
    public static final String CHANNEL_ONLINE = "ONLINE";
    public static final String CHANNEL_POS = "POS";
  }

  /** A single price for a variant within a price list, optionally qty-break-tiered. */
  public record PriceListItem(
      UUID id,
      UUID tenantId,
      UUID priceListId,
      UUID variantId,
      BigDecimal price,
      BigDecimal minQty,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * Time-bounded promotional discount. type=PERCENT: value is percentage off (e.g. 10 = 10% off).
   * type=FLAT: value is flat amount off in base currency (GBP).
   */
  public record Promotion(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String type,
      BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      boolean active,
      Instant startsAt,
      Instant endsAt,
      Instant createdAt,
      int priority,
      boolean exclusive,
      String couponCode,
      Integer maxRedemptions,
      Integer maxPerCustomer,
      BigDecimal buyQty,
      BigDecimal getQty,
      BigDecimal getDiscountPct) {

    /** Percentage off each matching line. {@code value} is 0-100. */
    public static final String TYPE_PERCENT = "PERCENT";

    /** Fixed amount off each matching unit. */
    public static final String TYPE_FLAT = "FLAT";

    /** Percentage off the whole basket, after line-level promotions have run. */
    public static final String TYPE_BASKET_PERCENT = "BASKET_PERCENT";

    /** Fixed amount off the whole basket. */
    public static final String TYPE_BASKET_FLAT = "BASKET_FLAT";

    /** Fixed amount off, but only once the basket clears {@code minOrderAmount}. */
    public static final String TYPE_SPEND_THRESHOLD = "SPEND_THRESHOLD";

    /** Buy {@code buyQty}, get {@code getQty} at {@code getDiscountPct} off (100 = free). */
    public static final String TYPE_BOGO = "BOGO";

    /** True when this promotion must be presented rather than applying on its own. */
    public boolean requiresCoupon() {
      return couponCode != null && !couponCode.isBlank();
    }

    /** True for the two types that discount the basket rather than any particular line. */
    public boolean isBasketLevel() {
      return TYPE_BASKET_PERCENT.equals(type)
          || TYPE_BASKET_FLAT.equals(type)
          || TYPE_SPEND_THRESHOLD.equals(type);
    }
  }

  /**
   * One line of a basket being quoted.
   *
   * @param variantId what is being bought
   * @param qty how many
   * @param unitPrice the base price before any promotion
   */
  public record BasketLine(UUID variantId, BigDecimal qty, BigDecimal unitPrice) {}

  /**
   * What one promotion took off one line.
   *
   * @param variantId the line discounted
   * @param promotionId which promotion did it
   * @param promotionName its name, so a receipt can say why the price changed
   * @param amount the money taken off that line in total, not per unit
   */
  public record LineDiscount(
      UUID variantId, UUID promotionId, String promotionName, BigDecimal amount) {}

  /**
   * The engine's answer for one basket.
   *
   * @param lineDiscounts every line-level reduction, itemised by promotion
   * @param basketDiscounts every whole-basket reduction, itemised by promotion
   * @param appliedPromotionIds every promotion that took something off, in the order it ran — the
   *     list a redemption ledger is written from
   * @param rejectedCoupons coupon codes the caller presented that did not apply, each with the
   *     reason. Returned rather than ignored: a customer who typed a code is owed an answer, and
   *     "nothing happened" is the answer that generates a support call.
   */
  public record PromotionOutcome(
      List<LineDiscount> lineDiscounts,
      List<LineDiscount> basketDiscounts,
      List<UUID> appliedPromotionIds,
      Map<String, String> rejectedCoupons) {

    /**
     * Everything this outcome takes off the basket.
     *
     * @return the line and basket discounts summed together
     */
    public BigDecimal totalDiscount() {
      return java.util.stream.Stream.concat(lineDiscounts.stream(), basketDiscounts.stream())
          .map(LineDiscount::amount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /** Scopes a promotion to a specific variant, category, or ALL products. */
  /** What a promotion or price list was switched to, by whom and why. Append-only (SJ-D33). */
  public record StatusChange(
      UUID id,
      UUID tenantId,
      String subjectType,
      UUID subjectId,
      boolean active,
      String reason,
      UUID changedBy,
      Instant changedAt) {

    public static final String PROMOTION = "PROMOTION";
    public static final String PRICE_LIST = "PRICE_LIST";
  }

  public record PromotionItem(
      UUID id, UUID tenantId, UUID promotionId, String scopeType, UUID scopeId, Instant createdAt) {

    public static final String SCOPE_VARIANT = "VARIANT";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String SCOPE_ALL = "ALL";
  }

  /**
   * POSLog-compatible tax capture per order line. Records the tax point date per s.6 VATA 1994
   * (basic tax point = time of supply). Feeds HMRC MTD VAT return boxes 1 and 6.
   */
  public record TaxTransaction(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID orderLineId,
      UUID variantId,
      UUID storeId,
      String vatCode,
      BigDecimal vatRate,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      boolean exempt,
      Instant taxPointDate,
      String invoiceRef,
      Instant createdAt) {}

  // ── Gap #41: POS price overrides ─────────────────────────────────────────

  /** Append-only record of a staff-approved ad-hoc POS price change. */
  public record PriceOverride(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID variantId,
      UUID storeId,
      BigDecimal originalPrice,
      BigDecimal overridePrice,
      String overrideReason,
      UUID overriddenBy,
      Instant createdAt) {}

  /** Result of price resolution: base price + promotion + VAT breakdown. */
  public record ResolvedPrice(
      UUID variantId,
      BigDecimal unitPrice,
      String vatCode,
      BigDecimal vatRate,
      BigDecimal vatAmount,
      BigDecimal totalWithVat,
      String currency,
      UUID priceListId,
      String promotionApplied) {}

  /**
   * HMRC MTD VAT return. Boxes per VAT Notice 700 s.17: 1=output VAT, 2=EU acquisitions VAT
   * (post-Brexit=0), 3=total due, 4=input VAT reclaimed, 5=net payable, 6=total sales ex-VAT,
   * 7=total purchases ex-VAT, 8=EU goods supplied, 9=EU goods acquired.
   */
  /** How a tax summary groups its rows. An enum, so no request text ever reaches the SQL. */
  public enum TaxGrouping {
    CODE,
    STORE,
    MONTH
  }

  /**
   * One aggregated line of the tax summary.
   *
   * <p>{@code exempt} is part of the grouping key rather than folded into the totals, because the
   * VAT return treats the two differently: Box 1 counts output VAT on taxable supplies only, while
   * Box 6 counts the net of every supply including exempt ones. Collapsing them would leave neither
   * box derivable from the report that exists to show their working.
   *
   * @param groupKey the VAT code, store id or {@code YYYY-MM} month this line sums
   * @param exempt whether this line covers exempt supplies
   * @param netAmount total net (ex-VAT) consideration
   * @param vatAmount total VAT charged
   * @param grossAmount total gross consideration
   * @param transactions how many tax transactions the line covers
   */
  public record TaxSummaryRow(
      String groupKey,
      boolean exempt,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      long transactions) {}

  /**
   * Period totals, summed from the same rows the report returns so the two cannot disagree.
   *
   * @param netAmount net across every line — ties to VAT return Box 6
   * @param vatAmount VAT across every line, exempt lines included
   * @param outputVat VAT across taxable lines only — ties to VAT return Box 1. Differs from {@code
   *     vatAmount} only if a line marked exempt carries VAT, which is a data fault worth seeing
   *     rather than silently dropping the way the Box 1 query does
   * @param grossAmount gross across every line
   * @param transactions total tax transactions in the period
   */
  public record TaxSummaryTotals(
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal outputVat,
      BigDecimal grossAmount,
      long transactions) {}

  /**
   * @param rows one line per group, largest VAT first
   * @param totals the period totals, which reconcile to the VAT return
   * @param periodFrom inclusive lower bound, echoed back as supplied
   * @param periodTo exclusive upper bound, echoed back as supplied
   */
  public record TaxSummary(
      List<TaxSummaryRow> rows, TaxSummaryTotals totals, String periodFrom, String periodTo) {}

  public record VatReturn(
      BigDecimal box1,
      BigDecimal box2,
      BigDecimal box3,
      BigDecimal box4,
      BigDecimal box5,
      BigDecimal box6,
      BigDecimal box7,
      BigDecimal box8,
      BigDecimal box9,
      String periodFrom,
      String periodTo) {}
}
