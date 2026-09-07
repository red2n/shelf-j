package com.shelfj.pricing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** All request and response DTOs for pricing-svc (records = immutable, no domain types). */
public final class Dtos {

  private Dtos() {}

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  @Schema(name = "CreateVatRateRequest")
  public record CreateVatRateRequest(
      @Schema(description = "HMRC VAT code, e.g. T1, T0, T5.") @NotBlank String code,
      @NotBlank String name,
      @Schema(description = "Fraction between 0 and 1, e.g. 0.20 for 20%.") @NotNull @PositiveOrZero
          BigDecimal rate,
      @Schema(description = "True if this code is VAT-exempt (no VAT charged).") boolean exempt,
      String description,
      @Schema(description = "ISO-8601 instant this rate takes effect, e.g. 2026-01-01T00:00:00Z.")
          @NotBlank
          String effectiveFrom) {}

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
      @Schema(description = "PERCENT or FLAT.") @NotBlank String type,
      @Schema(description = "Percentage (0-100) for PERCENT, or a flat amount for FLAT.")
          @NotNull
          @Positive
          BigDecimal value,
      BigDecimal minOrderAmount,
      @Schema(description = "ALL, ONLINE, or POS. Defaults to ALL.") String channel,
      @Schema(description = "UUID of the store this promotion is scoped to, if any.")
          String storeId,
      @Schema(description = "ISO-8601 timestamp the promotion becomes active.") @NotBlank
          String startsAt,
      @Schema(description = "ISO-8601 timestamp the promotion ends; open-ended if omitted.")
          String endsAt) {}

  @Schema(name = "PromotionResponse")
  public record PromotionResponse(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      @Schema(description = "PERCENT or FLAT.") String type,
      BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      boolean active,
      String startsAt,
      String endsAt,
      String createdAt) {}

  @Schema(name = "AddPromotionItemRequest")
  public record AddPromotionItemRequest(
      @Schema(description = "ALL, VARIANT, or CATEGORY.") @NotBlank String scopeType,
      @Schema(description = "UUID of the variant or category; null when scopeType is ALL.")
          String scopeId) {}

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
      String periodTo) {}

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
}
