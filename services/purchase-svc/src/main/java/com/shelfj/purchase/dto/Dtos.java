package com.shelfj.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request and response DTOs for purchase-svc. These are the HTTP contract — never expose domain.
 */
public final class Dtos {

  private Dtos() {}

  // ── Supplier ──────────────────────────────────────────────────────────────────
  public record CreateSupplierRequest(
      @NotBlank String name,
      String vatNumber,
      boolean vatRegistered,
      String countryCode,
      String currency,
      @Min(1) Integer paymentTermsDays) {}

  public record SupplierResponse(
      UUID id,
      UUID tenantId,
      String name,
      String vatNumber,
      boolean vatRegistered,
      String countryCode,
      String currency,
      int paymentTermsDays,
      Instant createdAt,
      Instant updatedAt) {}

  // ── Purchase Order ────────────────────────────────────────────────────────────
  public record CreatePurchaseOrderRequest(
      @NotNull UUID supplierId, @NotNull UUID storeId, String currency, String expectedDelivery) {}

  public record AddPurchaseOrderLineRequest(
      @NotNull UUID variantId,
      @NotNull @DecimalMin("0.001") BigDecimal qty,
      @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
      String vatCode) {}

  public record PurchaseOrderResponse(
      UUID id,
      UUID tenantId,
      UUID supplierId,
      UUID storeId,
      String status,
      String currency,
      BigDecimal totalNet,
      BigDecimal totalVat,
      BigDecimal totalGross,
      LocalDate expectedDelivery,
      Instant createdAt,
      Instant updatedAt) {}

  public record PurchaseOrderLineResponse(
      UUID id,
      UUID poId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      Instant createdAt) {}

  // ── Goods Receipt ─────────────────────────────────────────────────────────────
  public record CreateGoodsReceiptRequest(
      @NotNull UUID poId,
      @NotNull UUID storeId,
      @NotNull @Valid List<GoodsReceiptLineRequest> lines) {}

  public record GoodsReceiptLineRequest(
      @NotNull UUID variantId, @NotNull @DecimalMin("0.001") BigDecimal qtyReceived) {}

  public record GoodsReceiptResponse(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID storeId,
      Instant receivedAt,
      List<GoodsReceiptLineResponse> lines) {}

  public record GoodsReceiptLineResponse(
      UUID id, UUID variantId, BigDecimal qtyReceived, Instant createdAt) {}

  // ── Intercompany Invoice ──────────────────────────────────────────────────────
  public record RaiseIntercompanyInvoiceRequest(
      @NotNull String fromStoreId,
      @NotNull String toStoreId,
      String transferRef,
      @NotNull @DecimalMin("0.01") BigDecimal netAmount,
      @NotNull @DecimalMin("0") BigDecimal vatAmount,
      @NotNull @DecimalMin("0.01") BigDecimal grossAmount,
      String vatCode,
      boolean vatDisregarded,
      String currency) {}

  public record IntercompanyInvoicePairResponse(
      IntercompanyInvoiceResponse arInvoice, IntercompanyInvoiceResponse apInvoice) {}

  public record IntercompanyInvoiceResponse(
      UUID id,
      UUID tenantId,
      String invoiceType,
      UUID fromStoreId,
      UUID toStoreId,
      UUID transferRef,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String vatCode,
      boolean vatDisregarded,
      String status,
      LocalDate invoiceDate,
      LocalDate paymentDueDate,
      String currency,
      Instant createdAt) {}

  // ── Nominal Ledger ────────────────────────────────────────────────────────────
  public record NominalLedgerEntryResponse(
      UUID id,
      UUID tenantId,
      LocalDate entryDate,
      String nominalCode,
      String nominalName,
      BigDecimal debit,
      BigDecimal credit,
      String description,
      UUID sourceRef,
      Instant createdAt) {}
}
