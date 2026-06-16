package com.shelfj.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
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
      Instant createdAt) {

    public static final String TYPE_PERCENT = "PERCENT";
    public static final String TYPE_FLAT = "FLAT";
  }

  /** Scopes a promotion to a specific variant, category, or ALL products. */
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
