package com.shelfj.pricing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** All request and response DTOs for pricing-svc (records = immutable, no domain types). */
public final class Dtos {

  private Dtos() {}

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  // The limits mirror vat_rates' column sizes and rate CHECK: past them the insert fails in the
  // database and the caller got a 500 instead of a 400.
  @Schema(name = "CreateVatRateRequest")
  public record CreateVatRateRequest(
      @Schema(description = "HMRC VAT code, e.g. T1, T0, T5.", maxLength = 8)
          @NotBlank
          @Size(max = 8)
          String code,
      @NotBlank @Size(max = 100) String name,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.")
          @NotNull
          @PositiveOrZero
          @DecimalMax("1")
          BigDecimal rate,
      @Schema(description = "True if this code is VAT-exempt (no VAT charged).") boolean exempt,
      @Size(max = 255) String description,
      @Schema(description = "ISO-8601 instant this rate takes effect, e.g. 2026-01-01T00:00:00Z.")
          @NotBlank
          String effectiveFrom) {}

  @Schema(
      name = "SetActiveRequest",
      description = "Stop or restart a promotion or price list. The reason is required either way.")
  public record SetActiveRequest(
      @Schema(
              description =
                  "Why. Required in both directions — restarting a promotion is the change more"
                      + " likely to be questioned later, and a trail that records only why things"
                      + " were stopped answers the easier half of the question.")
          @NotBlank
          String reason) {}

  @Schema(name = "StatusChangeResponse", description = "One entry in the on/off history.")
  public record StatusChangeResponse(
      UUID id,
      String subjectType,
      UUID subjectId,
      @Schema(description = "The state it was changed TO.") boolean active,
      String reason,
      UUID changedBy,
      Instant changedAt) {}

  @Schema(name = "VatRateResponse")
  public record VatRateResponse(
      UUID id,
      UUID tenantId,
      String code,
      String name,
      BigDecimal rate,
      boolean exempt,
      String description,
      String effectiveFrom,
      String effectiveTo,
      String createdAt) {}

  // ── Product VAT Categories ────────────────────────────────────────────────

  @Schema(name = "UpsertProductVatCategoryRequest")
  public record UpsertProductVatCategoryRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Must match an existing VAT rate code.") @NotBlank String vatCode) {}

  @Schema(name = "ProductVatCategoryResponse")
  public record ProductVatCategoryResponse(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String vatCode,
      String effectiveFrom,
      String effectiveTo,
      String createdAt) {}

  // ── Customer VAT Status ───────────────────────────────────────────────────

  @Schema(
      name = "UpsertCustomerVatStatusRequest",
      description = "B2B customer VAT registration status.")
  public record UpsertCustomerVatStatusRequest(
      @Schema(description = "UUID of the customer.") @NotBlank String customerId,
      String vatNumber,
      boolean vatRegistered,
      @Schema(description = "True if this customer is eligible for reverse-charge VAT.")
          boolean reverseChargeEligible,
      @Schema(description = "ISO 3166-1 alpha-2 country code. Defaults to GB.")
          String countryCode) {}

  @Schema(name = "CustomerVatStatusResponse")
  public record CustomerVatStatusResponse(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String vatNumber,
      boolean vatRegistered,
      boolean reverseChargeEligible,
      String countryCode,
      String createdAt,
      String updatedAt) {}

  // ── Price Lists ───────────────────────────────────────────────────────────

  @Schema(name = "CreatePriceListRequest")
  public record CreatePriceListRequest(
      @NotBlank String name,
      @Schema(description = "ALL, ONLINE, or POS. Defaults to ALL.") String channel,
      @Schema(description = "ISO 4217 currency code. Defaults to GBP.") String currency,
      @Schema(
              description =
                  "ISO-8601 instant this price list takes effect, e.g."
                      + " 2026-01-01T00:00:00Z. The column is TIMESTAMPTZ; a bare date is"
                      + " rejected with INVALID_DATE.")
          @NotBlank
          String effectiveFrom,
      String effectiveTo) {}

  @Schema(name = "PriceListResponse")
  public record PriceListResponse(
      UUID id,
      UUID tenantId,
      String name,
      String channel,
      String currency,
      String effectiveFrom,
      String effectiveTo,
      boolean active,
      String createdAt) {}

  @Schema(name = "UpsertPriceListItemRequest")
  public record UpsertPriceListItemRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @NotNull @Positive BigDecimal price,
      @Schema(description = "Minimum quantity this price applies from. Defaults to 1.")
          @NotNull
          @Positive
          BigDecimal minQty) {}

  @Schema(name = "BatchUpsertPriceListItemsRequest")
  public record BatchUpsertPriceListItemsRequest(
      @NotNull @Valid java.util.List<UpsertPriceListItemRequest> items) {}

  @Schema(
      name = "BatchUpsertResult",
      description = "Result of a batch upsert; never a 4xx on partial failure.")
  public record BatchUpsertResult(
      @Schema(description = "Number of items successfully upserted.") int upserted,
      @Schema(description = "One message per item that failed, e.g. \"variantId=...: reason\".")
          java.util.List<String> errors) {}

  @Schema(name = "PriceListItemResponse")
  public record PriceListItemResponse(
      UUID id,
      UUID tenantId,
      UUID priceListId,
      UUID variantId,
      BigDecimal price,
      BigDecimal minQty,
      String createdAt,
      String updatedAt) {}

  // ── Price Resolution ──────────────────────────────────────────────────────

  @Schema(name = "ResolvePriceRequest")
  public record ResolvePriceRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "UUID of the store; used to select a store-scoped promotion/price.")
          String storeId,
      @Schema(description = "ONLINE or POS. Defaults to ALL.") String channel,
      @Schema(description = "Quantity being priced. Defaults to 1.") BigDecimal qty,
      @Schema(description = "UUID of the customer, for customer-specific pricing.")
          String customerId) {}

  @Schema(name = "ResolvedPriceResponse")
  public record ResolvedPriceResponse(
      UUID variantId,
      BigDecimal unitPrice,
      @Schema(description = "HMRC VAT code applied, e.g. T1.") String vatCode,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.") BigDecimal vatRate,
      BigDecimal vatAmount,
      BigDecimal totalWithVat,
      String currency,
      UUID priceListId,
      @Schema(description = "Name of the promotion applied, if any.") String promotionApplied) {}

  /**
   * Resolve many lines in one call instead of one HTTP round trip per line — order-svc's checkout
   * was issuing one synchronous {@code /prices/resolve} call per order line.
   */
  @Schema(name = "ResolvePriceBatchRequest")
  public record ResolvePriceBatchRequest(@NotEmpty @Valid List<ResolvePriceRequest> lines) {}

  /** Results are in the same order as the request's {@code lines}. */
  @Schema(
      name = "ResolvePriceBatchResponse",
      description = "Results are in the same order as the request's lines.")
  public record ResolvePriceBatchResponse(List<ResolvedPriceResponse> results) {}

  // ── Promotions ────────────────────────────────────────────────────────────

  @Schema(name = "CreatePromotionRequest")
  public record CreatePromotionRequest(
      @NotBlank String name,
      @Schema(
              description =
                  "PERCENT or FLAT (per line), BASKET_PERCENT or BASKET_FLAT (whole basket),"
                      + " SPEND_THRESHOLD (a flat amount once the basket clears minOrderAmount),"
                      + " or BOGO (buy X get Y at a discount).")
          @NotBlank
          String type,
      @Schema(
              description =
                  "Percentage (0-100) for the PERCENT types, or an amount for the FLAT ones."
                      + " Ignored for BOGO, which is described by buyQty/getQty/getDiscountPct.")
          @NotNull
          @Positive
          BigDecimal value,
      @Schema(
              description =
                  "Basket subtotal this promotion needs before it applies. Required for"
                      + " SPEND_THRESHOLD, optional on the other basket types. Previously stored"
                      + " and never read, so a 'spend £100' offer applied to a £3 basket.")
          BigDecimal minOrderAmount,
      @Schema(description = "ALL, ONLINE, or POS. Defaults to ALL.") String channel,
      @Schema(
              description =
                  "UUID of the store this promotion is scoped to; omit for every store. Previously"
                      + " stored and never filtered, so a store promotion ran in every store.")
          String storeId,
      @Schema(description = "ISO-8601 timestamp the promotion becomes active.") @NotBlank
          String startsAt,
      @Schema(description = "ISO-8601 timestamp the promotion ends; open-ended if omitted.")
          String endsAt,
      @Schema(
              description =
                  "Application order, ascending — lower runs first. Defaults to 100. Which of two"
                      + " overlapping offers wins used to be an accident of a SQL sort that"
                      + " compared a percentage against a sum of money.")
          Integer priority,
      @Schema(
              description =
                  "When true, this promotion stops every promotion after it — 'cannot be combined"
                      + " with any other offer'.")
          Boolean exclusive,
      @Schema(
              description =
                  "Code the customer must present. Omit for a promotion that applies on its own."
                      + " Matched case-insensitively and unique per tenant.")
          String couponCode,
      @Schema(description = "Total times this promotion may be redeemed. Null = uncapped.")
          Integer maxRedemptions,
      @Schema(
              description =
                  "Times one customer may redeem it. Null = uncapped. Cannot bind on a guest"
                      + " checkout, which has no identity to count against.")
          Integer maxPerCustomer,
      @Schema(description = "BOGO: how many must be bought.") BigDecimal buyQty,
      @Schema(description = "BOGO: how many are then discounted.") BigDecimal getQty,
      @Schema(description = "BOGO: by how much, as a percentage. 100 = free.")
          BigDecimal getDiscountPct) {}

  @Schema(name = "PromotionResponse")
  public record PromotionResponse(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      @Schema(description = "PERCENT, FLAT, BASKET_PERCENT, BASKET_FLAT, SPEND_THRESHOLD or BOGO.")
          String type,
      BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      boolean active,
      String startsAt,
      String endsAt,
      String createdAt,
      @Schema(description = "Application order, ascending.") int priority,
      @Schema(description = "True when this promotion suppresses every promotion after it.")
          boolean exclusive,
      @Schema(description = "Code the customer must present, or null when it applies on its own.")
          String couponCode,
      Integer maxRedemptions,
      Integer maxPerCustomer,
      BigDecimal buyQty,
      BigDecimal getQty,
      BigDecimal getDiscountPct) {}

  @Schema(name = "AddPromotionItemRequest")
  public record AddPromotionItemRequest(
      @Schema(
              description =
                  "ALL or VARIANT. CATEGORY is rejected: pricing-svc has no variant→category"
                      + " mapping, because product-svc publishes no catalogue event, and a"
                      + " category promotion was previously accepted and silently never applied.")
          @NotBlank
          String scopeType,
      @Schema(description = "UUID of the variant; null when scopeType is ALL.") String scopeId) {}

  // ── Basket quoting ────────────────────────────────────────────────────────

  @Schema(
      name = "QuoteBasketRequest",
      description =
          "Prices a whole basket at once. Distinct from /prices/resolve-batch, which prices each"
              + " line independently and therefore cannot see a spend threshold, a basket"
              + " percentage or a buy-one-get-one.")
  public record QuoteBasketRequest(
      @NotEmpty @Valid List<QuoteLineRequest> lines,
      @Schema(description = "UUID of the store; selects store-scoped prices and promotions.")
          String storeId,
      @Schema(description = "ONLINE or POS. Defaults to ALL.") String channel,
      @Schema(description = "UUID of the customer, for per-customer coupon caps.")
          String customerId,
      @Schema(description = "Coupon codes the customer presented. Matched case-insensitively.")
          List<String> couponCodes) {}

  @Schema(name = "QuoteLineRequest")
  public record QuoteLineRequest(
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "Quantity being bought. Defaults to 1.") BigDecimal qty) {}

  @Schema(name = "QuoteLineResponse", description = "One priced basket line.")
  public record QuoteLineResponse(
      UUID variantId,
      BigDecimal qty,
      @Schema(description = "Base price per unit, before promotions.") BigDecimal unitPrice,
      @Schema(description = "qty × unitPrice, before promotions.") BigDecimal lineTotal,
      @Schema(description = "Total taken off this line by line-level promotions.")
          BigDecimal discount,
      @Schema(description = "lineTotal minus discount.") BigDecimal netTotal,
      @Schema(description = "VAT on netTotal, at this variant's rate.") BigDecimal vatAmount,
      @Schema(description = "The VAT code applied.") String vatCode) {}

  @Schema(name = "AppliedPromotionResponse", description = "One promotion that took money off.")
  public record AppliedPromotionResponse(
      UUID promotionId,
      String name,
      @Schema(description = "The variant discounted, or null for a whole-basket promotion.")
          UUID variantId,
      BigDecimal amount) {}

  @Schema(
      name = "QuoteBasketResponse",
      description = "A fully priced basket, with every promotion that applied itemised.")
  public record QuoteBasketResponse(
      List<QuoteLineResponse> lines,
      @Schema(description = "Sum of line totals before any promotion.") BigDecimal subtotal,
      @Schema(description = "Everything taken off, line-level and basket-level together.")
          BigDecimal totalDiscount,
      @Schema(description = "Whole-basket discounts, which belong to no single line.")
          BigDecimal basketDiscount,
      @Schema(description = "VAT across every line, computed after discounts.")
          BigDecimal vatAmount,
      @Schema(description = "subtotal − totalDiscount + vatAmount.") BigDecimal total,
      String currency,
      @Schema(description = "Every promotion that applied, in the order it ran.")
          List<AppliedPromotionResponse> appliedPromotions,
      @Schema(
              description =
                  "Coupon codes the caller presented that did not apply, and why:"
                      + " NO_SUCH_COUPON, NOT_APPLICABLE, COUPON_EXHAUSTED or"
                      + " COUPON_LIMIT_REACHED. Returned rather than ignored — a customer who"
                      + " typed a code is owed an answer.")
          Map<String, String> rejectedCoupons) {}

  @Schema(
      name = "RecordRedemptionsRequest",
      description =
          "Tells pricing-svc an order used these promotions, so their usage caps are spent."
              + " Idempotent on the order.")
  public record RecordRedemptionsRequest(
      @Schema(description = "UUID of the order the promotions were used on.") @NotBlank
          String orderId,
      @Schema(description = "UUID of the customer, for per-customer caps. Null for a guest.")
          String customerId,
      @Schema(description = "ISO 4217 currency the amounts are in.") String currency,
      @NotEmpty List<AppliedPromotionResponse> appliedPromotions) {}

  @Schema(name = "RecordRedemptionsResponse")
  public record RecordRedemptionsResponse(
      @Schema(
              description =
                  "How many redemptions this call actually recorded. Zero means every one had"
                      + " already been recorded — a replay, not a failure.")
          int recorded) {}

  @Schema(name = "PromotionItemResponse")
  public record PromotionItemResponse(
      UUID id, UUID tenantId, UUID promotionId, String scopeType, UUID scopeId, String createdAt) {}

  // ── Tax Transactions (POSLog) ─────────────────────────────────────────────

  @Schema(
      name = "RecordTaxTransactionRequest",
      description = "POSLog-compatible tax transaction line per HMRC VAT Notice 700.")
  public record RecordTaxTransactionRequest(
      @NotNull UUID orderId,
      @NotNull UUID orderLineId,
      @NotNull UUID variantId,
      @NotNull UUID storeId,
      @Schema(description = "HMRC VAT code applied, e.g. T1.") @NotBlank String vatCode,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.") @NotNull @PositiveOrZero
          BigDecimal vatRate,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      boolean exempt,
      @Schema(
              description =
                  "ISO-8601 instant of the VAT tax point (chargeable event), e.g."
                      + " 2026-01-01T00:00:00Z.")
          @NotBlank
          String taxPointDate,
      String invoiceRef) {}

  @Schema(name = "TaxTransactionResponse")
  public record TaxTransactionResponse(
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
      String taxPointDate,
      String invoiceRef,
      String createdAt) {}

  // ── MTD VAT Return ────────────────────────────────────────────────────────

  @Schema(
      name = "VatReturnResponse",
      description =
          "HMRC Making Tax Digital VAT return boxes 1-9 per VAT Notice 700 s.17. Box 4 (input VAT)"
              + " and boxes 7-9 remain zero until purchase-svc's data feeds them.")
  public record VatReturnResponse(
      @Schema(description = "Total output VAT due on sales.") BigDecimal box1,
      @Schema(description = "VAT due on EC acquisitions; always zero (no EC acquisitions modeled).")
          BigDecimal box2,
      @Schema(description = "Total VAT due (box1 + box2).") BigDecimal box3,
      @Schema(description = "Input VAT reclaimable; zero until purchase-svc feeds this.")
          BigDecimal box4,
      @Schema(description = "Net VAT to pay/reclaim: abs(box3 - box4).") BigDecimal box5,
      @Schema(description = "Total net value of sales excluding VAT.") BigDecimal box6,
      @Schema(description = "Total net value of purchases; zero until purchase-svc feeds this.")
          BigDecimal box7,
      @Schema(description = "Total net EC supplies; zero (no EC supplies modeled).")
          BigDecimal box8,
      @Schema(description = "Total net EC acquisitions; zero (no EC acquisitions modeled).")
          BigDecimal box9,
      String periodFrom,
      String periodTo,
      @Schema(description = "The boxes computed from this tenant's tax transactions.")
          List<Integer> computedBoxes,
      @Schema(
              description =
                  "The boxes this service cannot compute: input VAT and purchases live in"
                      + " purchase-svc and nothing carries them here (SJ-D39). Shown as 0 for"
                      + " shape only.")
          List<Integer> notComputedBoxes,
      @Schema(description = "False until every box is real. Do not file from a return that is not.")
          boolean fitToFile,
      @Schema(description = "Why it is not fit to file, in words for the screen.") String caveat) {}

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  @Schema(
      name = "CreatePriceOverrideRequest",
      description = "Staff-approved ad-hoc price change made at the point of sale.")
  public record CreatePriceOverrideRequest(
      @Schema(description = "UUID of the order this override applies to, if any.") String orderId,
      @Schema(description = "UUID of the product variant.") @NotBlank String variantId,
      @Schema(description = "UUID of the store where the override was made.") @NotBlank
          String storeId,
      @Schema(description = "The price before the override.") @PositiveOrZero
          BigDecimal originalPrice,
      @Schema(description = "The overridden price actually charged.") @NotNull @PositiveOrZero
          BigDecimal overridePrice,
      String overrideReason,
      @Schema(description = "UUID of the staff member who approved the override.")
          String overriddenBy) {}

  @Schema(name = "PriceOverrideResponse")
  public record PriceOverrideResponse(
      String id,
      String orderId,
      String variantId,
      String storeId,
      BigDecimal originalPrice,
      BigDecimal overridePrice,
      String overrideReason,
      String overriddenBy,
      String createdAt) {}

  @Schema(name = "TaxSummaryRow", description = "One aggregated line of the tax summary.")
  public record TaxSummaryRowResponse(
      @Schema(description = "The VAT code, store id, or YYYY-MM month this line sums.")
          String groupKey,
      @Schema(
              description =
                  "True for exempt supplies. Exempt lines are reported separately because the VAT"
                      + " return counts their net in Box 6 but their VAT in no box at all.")
          boolean exempt,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      @Schema(description = "How many tax transactions this line covers.") long transactions) {}

  @Schema(
      name = "TaxSummaryTotals",
      description = "Period totals, summed from the returned rows so the two cannot disagree.")
  public record TaxSummaryTotalsResponse(
      @Schema(description = "Net across every line. Ties to VAT return Box 6.")
          BigDecimal netAmount,
      @Schema(description = "VAT across every line, exempt lines included.") BigDecimal vatAmount,
      @Schema(
              description =
                  "VAT across taxable lines only. Ties to VAT return Box 1. If this differs from"
                      + " vatAmount, a line marked exempt is carrying VAT — a data fault the Box 1"
                      + " query drops silently.")
          BigDecimal outputVat,
      BigDecimal grossAmount,
      long transactions) {}

  @Schema(
      name = "TaxSummaryReport",
      description =
          "VAT collected over a period, grouped, with totals that reconcile to the VAT return.")
  public record TaxSummaryResponse(
      List<TaxSummaryRowResponse> rows,
      TaxSummaryTotalsResponse totals,
      String periodFrom,
      String periodTo) {}
}
