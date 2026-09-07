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
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request and response DTOs for purchase-svc. These are the HTTP contract — never expose domain.
 */
public final class Dtos {

  private Dtos() {}

  // ── Supplier ──────────────────────────────────────────────────────────────────
  @Schema(name = "CreateSupplierRequest", description = "Create a supplier master record.")
  public record CreateSupplierRequest(
      @NotBlank String name,
      String vatNumber,
      boolean vatRegistered,
      @Schema(description = "ISO 3166-1 alpha-2 country code. Defaults to GB.") String countryCode,
      @Schema(description = "ISO 4217 currency code. Defaults to GBP.") String currency,
      @Schema(description = "Payment terms in days. Defaults to 30 (BACS standard).") @Min(1)
          Integer paymentTermsDays) {}

  @Schema(name = "SupplierResponse")
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
  @Schema(name = "CreatePurchaseOrderRequest", description = "Create a DRAFT purchase order.")
  public record CreatePurchaseOrderRequest(
      @NotNull UUID supplierId,
      @NotNull UUID storeId,
      @Schema(description = "ISO 4217 currency code. Defaults to GBP.") String currency,
      @Schema(
              description =
                  "Date the goods are expected to arrive, as yyyy-MM-dd (e.g. 2026-01-31). A"
                      + " value carrying a time is rejected with INVALID_DATE.")
          String expectedDelivery) {}

  @Schema(name = "AddPurchaseOrderLineRequest")
  public record AddPurchaseOrderLineRequest(
      @NotNull UUID variantId,
      @NotNull @DecimalMin("0.001") BigDecimal qty,
      @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
      @Schema(description = "UK VAT code, e.g. T1. Defaults to T1.") String vatCode) {}

  @Schema(
      name = "CancelPurchaseOrderRequest",
      description = "Cancel a purchase order that has not yet been received against.")
  public record CancelPurchaseOrderRequest(
      @Schema(
              description =
                  "Why the order is being cancelled. Recorded on the order and carried on the"
                      + " PurchaseOrderCancelled event; required, because a cancelled order with no"
                      + " stated reason is unauditable.")
          @NotBlank
          String reason) {}

  @Schema(name = "PurchaseOrderResponse")
  public record PurchaseOrderResponse(
      UUID id,
      UUID tenantId,
      UUID supplierId,
      UUID storeId,
      @Schema(description = "DRAFT, SUBMITTED, RECEIVED or CANCELLED.") String status,
      String currency,
      BigDecimal totalNet,
      BigDecimal totalVat,
      BigDecimal totalGross,
      LocalDate expectedDelivery,
      Instant createdAt,
      Instant updatedAt,
      @Schema(description = "When the order was cancelled; null unless status is CANCELLED.")
          Instant cancelledAt,
      @Schema(description = "Why the order was cancelled; null unless status is CANCELLED.")
          String cancelledReason) {}

  @Schema(name = "PurchaseOrderLineResponse")
  public record PurchaseOrderLineResponse(
      UUID id,
      UUID poId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      Instant createdAt) {}

  // ── Goods Receipt ─────────────────────────────────────────────────────────────
  @Schema(
      name = "CreateGoodsReceiptRequest",
      description = "Record goods received against a SUBMITTED purchase order.")
  public record CreateGoodsReceiptRequest(
      @NotNull UUID poId,
      @NotNull UUID storeId,
      @NotNull @Valid List<GoodsReceiptLineRequest> lines) {}

  @Schema(name = "GoodsReceiptLineRequest")
  public record GoodsReceiptLineRequest(
      @NotNull UUID variantId, @NotNull @DecimalMin("0.001") BigDecimal qtyReceived) {}

  @Schema(name = "GoodsReceiptResponse")
  public record GoodsReceiptResponse(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID storeId,
      Instant receivedAt,
      List<GoodsReceiptLineResponse> lines) {}

  @Schema(name = "GoodsReceiptLineResponse")
  public record GoodsReceiptLineResponse(
      UUID id, UUID variantId, BigDecimal qtyReceived, Instant createdAt) {}

  // ── Intercompany Invoice ──────────────────────────────────────────────────────
  @Schema(
      name = "RaiseIntercompanyInvoiceRequest",
      description =
          "Raises an AR/AP intercompany invoice pair for an inter-org stock transfer between two"
              + " stores.")
  public record RaiseIntercompanyInvoiceRequest(
      @Schema(description = "UUID of the sending store.") @NotNull String fromStoreId,
      @Schema(description = "UUID of the receiving store.") @NotNull String toStoreId,
      @Schema(description = "Optional UUID linking this invoice pair to a transfer order.")
          String transferRef,
      @Schema(description = "Net amount, per HMRC INTM arm's-length transfer pricing.")
          @NotNull
          @DecimalMin("0.01")
          BigDecimal netAmount,
      @NotNull @DecimalMin("0") BigDecimal vatAmount,
      @NotNull @DecimalMin("0.01") BigDecimal grossAmount,
      @Schema(description = "UK VAT code, e.g. T1. Defaults to T1.") String vatCode,
      @Schema(
              description =
                  "True when both stores are in the same VAT group (HMRC VAT Notice 700/2) — no"
                      + " VAT nominal entries are posted.")
          boolean vatDisregarded,
      @Schema(description = "ISO 4217 currency code. Defaults to GBP.") String currency) {}

  @Schema(name = "IntercompanyInvoicePairResponse")
  public record IntercompanyInvoicePairResponse(
      IntercompanyInvoiceResponse arInvoice, IntercompanyInvoiceResponse apInvoice) {}

  @Schema(name = "IntercompanyInvoiceResponse")
  public record IntercompanyInvoiceResponse(
      UUID id,
      UUID tenantId,
      @Schema(description = "AR (sending store) or AP (receiving store).") String invoiceType,
      UUID fromStoreId,
      UUID toStoreId,
      UUID transferRef,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String vatCode,
      boolean vatDisregarded,
      @Schema(description = "RAISED or SETTLED.") String status,
      LocalDate invoiceDate,
      @Schema(description = "Invoice date + 30 days (BACS standard terms).")
          LocalDate paymentDueDate,
      String currency,
      Instant createdAt) {}

  // ── Nominal Ledger ────────────────────────────────────────────────────────────
  @Schema(
      name = "NominalLedgerEntryResponse",
      description = "A single double-entry nominal ledger line (debit or credit, never both).")
  public record NominalLedgerEntryResponse(
      UUID id,
      UUID tenantId,
      LocalDate entryDate,
      @Schema(description = "Nominal account code, e.g. 1100 (Debtors), 2100 (Creditors).")
          String nominalCode,
      String nominalName,
      BigDecimal debit,
      BigDecimal credit,
      String description,
      @Schema(description = "UUID of the source document (invoice, settlement, etc.).")
          UUID sourceRef,
      Instant createdAt) {}
}
