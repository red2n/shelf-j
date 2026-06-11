package com.shelfj.pricing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/** All request and response DTOs for pricing-svc (records = immutable, no domain types). */
public final class Dtos {

  private Dtos() {}

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  public record CreateVatRateRequest(
      @NotBlank String code,
      @NotBlank String name,
      @NotNull @PositiveOrZero BigDecimal rate,
      boolean exempt,
      String description,
      @NotBlank String effectiveFrom) {}

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

  public record UpsertProductVatCategoryRequest(
      @NotBlank String variantId, @NotBlank String vatCode) {}

  public record ProductVatCategoryResponse(
      UUID id,
      UUID tenantId,
      UUID variantId,
      String vatCode,
      String effectiveFrom,
      String effectiveTo,
      String createdAt) {}

  // ── Customer VAT Status ───────────────────────────────────────────────────

  public record UpsertCustomerVatStatusRequest(
      @NotBlank String customerId,
      String vatNumber,
      boolean vatRegistered,
      boolean reverseChargeEligible,
      String countryCode) {}

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

  public record CreatePriceListRequest(
      @NotBlank String name,
      String channel,
      String currency,
      @NotBlank String effectiveFrom,
      String effectiveTo) {}

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

  public record UpsertPriceListItemRequest(
      @NotBlank String variantId,
      @NotNull @Positive BigDecimal price,
      @NotNull @Positive BigDecimal minQty) {}

  public record BatchUpsertPriceListItemsRequest(
      @NotNull java.util.List<UpsertPriceListItemRequest> items) {}

  public record BatchUpsertResult(int upserted, java.util.List<String> errors) {}

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

  public record ResolvePriceRequest(
      @NotBlank String variantId,
      String storeId,
      String channel,
      BigDecimal qty,
      String customerId) {}

  public record ResolvedPriceResponse(
      UUID variantId,
      BigDecimal unitPrice,
      String vatCode,
      BigDecimal vatRate,
      BigDecimal vatAmount,
      BigDecimal totalWithVat,
      String currency,
      UUID priceListId,
      String promotionApplied) {}

  // ── Promotions ────────────────────────────────────────────────────────────

  public record CreatePromotionRequest(
      @NotBlank String name,
      @NotBlank String type,
      @NotNull @Positive BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      String storeId,
      @NotBlank String startsAt,
      String endsAt) {}

  public record PromotionResponse(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String type,
      BigDecimal value,
      BigDecimal minOrderAmount,
      String channel,
      boolean active,
      String startsAt,
      String endsAt,
      String createdAt) {}

  public record AddPromotionItemRequest(@NotBlank String scopeType, String scopeId) {}

  public record PromotionItemResponse(
      UUID id, UUID tenantId, UUID promotionId, String scopeType, UUID scopeId, String createdAt) {}

  // ── Tax Transactions (POSLog) ─────────────────────────────────────────────

  public record RecordTaxTransactionRequest(
      @NotNull UUID orderId,
      @NotNull UUID orderLineId,
      @NotNull UUID variantId,
      @NotNull UUID storeId,
      @NotBlank String vatCode,
      @NotNull @PositiveOrZero BigDecimal vatRate,
      @NotNull @PositiveOrZero BigDecimal netAmount,
      @NotNull @PositiveOrZero BigDecimal vatAmount,
      @NotNull @PositiveOrZero BigDecimal grossAmount,
      boolean exempt,
      @NotBlank String taxPointDate,
      String invoiceRef) {}

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

  public record VatReturnResponse(
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

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  public record CreatePriceOverrideRequest(
      String orderId,
      @NotBlank String variantId,
      @NotBlank String storeId,
      BigDecimal originalPrice,
      @NotNull @PositiveOrZero BigDecimal overridePrice,
      String overrideReason,
      String overriddenBy) {}

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
