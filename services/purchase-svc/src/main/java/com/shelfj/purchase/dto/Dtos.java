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
      @Schema(
              description =
                  "ISO 4217 currency code — the currency this supplier invoices in. Defaults to"
                      + " the tenant's own declared currency; set it explicitly for an overseas"
                      + " supplier (a Japanese supplier billing a UK tenant in JPY). Every purchase"
                      + " order raised against this supplier inherits it.")
          String currency,
      @Schema(description = "Payment terms in days. Defaults to 30 (BACS standard).") @Min(1)
          Integer paymentTermsDays) {}

  @Schema(
      name = "UpdateSupplierRequest",
      description =
          "Replace a supplier's master data. Terms, VAT number, country and currency can all be"
              + " corrected after creation (SJ-D34); the currency only while no purchase order"
              + " against the supplier is open, because every open order is denominated in it.")
  public record UpdateSupplierRequest(
      @NotBlank String name,
      String vatNumber,
      boolean vatRegistered,
      @Schema(description = "ISO 3166-1 alpha-2 country code; unchanged when omitted.")
          String countryCode,
      @Schema(description = "ISO 4217 currency code; unchanged when omitted.") String currency,
      @Schema(description = "Payment terms in days; unchanged when omitted.") @Min(1)
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
      @Schema(
              description =
                  "ISO 4217 currency code. Optional, and taken from the supplier when omitted —"
                      + " a purchase order is denominated in the currency its supplier invoices"
                      + " in. Supplying one that differs from the supplier's is rejected with"
                      + " PURCHASE_CURRENCY_MISMATCH rather than silently overridden.")
          String currency,
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
      @Schema(
              description =
                  "DRAFT, PENDING_APPROVAL (above the submitter's spend authority), SUBMITTED,"
                      + " PARTIALLY_RECEIVED (some arrived, more expected), RECEIVED"
                      + " (all arrived), CLOSED (short-closed — the balance is not coming) or"
                      + " CANCELLED (nothing was ever received).")
          String status,
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
          String cancelledReason,
      @Schema(description = "When the order was short-closed; null unless status is CLOSED.")
          Instant closedAt,
      @Schema(description = "Why the balance was abandoned; null unless status is CLOSED.")
          String closedReason,
      @Schema(
              description =
                  "Who raised the order, from the verified JWT. Null on orders raised before this"
                      + " was captured — which means 'not recorded', not 'nobody'.")
          UUID createdBy,
      @Schema(
              description =
                  "Who approved it, when it needed approval. Null on an order that never did —"
                      + " one within its submitter's own spend authority goes straight to"
                      + " SUBMITTED.")
          UUID approvedBy,
      @Schema(description = "When it was approved; null unless it needed and received approval.")
          Instant approvedAt) {}

  @Schema(
      name = "PurchaseOrderLineProgressResponse",
      description = "How much of one ordered line has actually turned up.")
  public record PurchaseOrderLineProgressResponse(
      UUID variantId,
      @Schema(description = "What the purchase order asked for.") BigDecimal qtyOrdered,
      @Schema(
              description =
                  "What has arrived across every receipt against this order. Receipts are matched"
                      + " to order lines by variant, not by line id — a delivery note names"
                      + " products, not order rows.")
          BigDecimal qtyReceived,
      @Schema(description = "Ordered minus received, floored at zero.")
          BigDecimal qtyOutstanding) {}

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
      @Schema(
              description =
                  "ISO 4217 currency code. Defaults to the tenant's own declared currency —"
                      + " intercompany invoicing is store-to-store inside one tenant, so there is"
                      + " no outside counterparty whose currency could differ.")
          String currency) {}

  // ── Purchase order approval (spend authority) ─────────────────────────────────

  @Schema(
      name = "DecidePurchaseOrderRequest",
      description = "Approve or reject a purchase order awaiting approval.")
  public record DecidePurchaseOrderRequest(
      @Schema(
              description =
                  "Why. Required on a rejection, because only a rejection leaves the buyer with"
                      + " work to do and no idea what to change. Optional on an approval.")
          String reason) {}

  @Schema(
      name = "PurchaseOrderApprovalResponse",
      description = "One entry in a purchase order's append-only approval history.")
  public record PurchaseOrderApprovalResponse(
      UUID id,
      UUID poId,
      @Schema(description = "REQUESTED (submitted for approval), APPROVED or REJECTED.")
          String decision,
      @Schema(
              description =
                  "The order's net value as it stood when this decision was made, captured here"
                      + " rather than read back from the order later — a rejected order can be"
                      + " edited and resubmitted, so the figure a decision was made against is not"
                      + " necessarily the one it carries now.")
          BigDecimal totalNet,
      String currency,
      @Schema(
              description =
                  "What the decider was entitled to commit in this currency, so the trail still"
                      + " answers 'were they allowed to?' after the configuration changes. Null"
                      + " when they held unlimited authority or none at all.")
          BigDecimal authority,
      UUID decidedBy,
      @Schema(description = "The role the decision was made under — the decider's most generous.")
          String decidedRole,
      String reason,
      Instant decidedAt) {}

  @Schema(
      name = "SpendAuthorityResponse",
      description =
          "What the caller may commit in one currency, so a buyer is told before building the order"
              + " rather than after trying to submit it.")
  public record SpendAuthorityResponse(
      String currency,
      @Schema(
              description =
                  "The ceiling this caller may submit without anyone else's approval, measured on"
                      + " the order's NET value — VAT is recoverable for a VAT-registered business"
                      + " and is therefore not spend. Null when unlimited, or when the caller holds"
                      + " no authority in this currency at all; check 'unlimited' to tell those"
                      + " apart.")
          BigDecimal ceiling,
      boolean unlimited,
      @Schema(description = "The role the ceiling comes from; null when the caller holds none.")
          String role,
      @Schema(
              description =
                  "True when approval is switched off platform-wide, in which case any staff role"
                      + " may submit any amount — the behaviour before spend authority existed.")
          boolean approvalDisabled,
      @Schema(description = "Why the caller holds no authority here; null when they do.")
          String reason) {}

  // ── Supplier invoice / three-way match ────────────────────────────────────────

  @Schema(
      name = "CaptureSupplierInvoiceRequest",
      description =
          "Record a supplier's invoice against a purchase order and match it. The invoice is stored"
              + " whether or not it matches: an invoice that arrived is a fact, and refusing to"
              + " record one that disagrees with the order destroys the evidence of the"
              + " disagreement.")
  public record CaptureSupplierInvoiceRequest(
      @NotNull UUID poId,
      @Schema(description = "The supplier's own reference as printed on the document.") @NotBlank
          String invoiceNumber,
      @Schema(description = "Invoice date, as yyyy-MM-dd (e.g. 2026-01-31).") @NotBlank
          String invoiceDate,
      @Schema(
              description =
                  "ISO 4217 code. Optional — taken from the purchase order when omitted. Supplying"
                      + " one that differs is rejected: an invoice in a currency the order was not"
                      + " placed in is not a variance to flag, it is a different document.")
          String currency,
      @Schema(description = "VAT charged on the invoice. Defaults to zero.") BigDecimal vatAmount,
      @NotNull List<CaptureSupplierInvoiceLine> lines) {}

  @Schema(name = "CaptureSupplierInvoiceLine")
  public record CaptureSupplierInvoiceLine(
      @NotNull UUID variantId,
      @Schema(description = "Quantity billed on this line.") @NotNull BigDecimal qty,
      @Schema(description = "Price per unit charged. May carry more precision than the currency.")
          @NotNull
          BigDecimal unitPrice,
      String vatCode) {}

  @Schema(name = "SupplierInvoiceResponse")
  public record SupplierInvoiceResponse(
      UUID id,
      UUID poId,
      UUID supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      @Schema(
              description =
                  "MATCHED when every line agreed with the order and the receipt inside tolerance;"
                      + " FLAGGED when at least one did not. Flagging never blocks capture.")
          String status,
      UUID createdBy,
      Instant createdAt,
      List<SupplierInvoiceMatchLineResponse> lines) {}

  @Schema(
      name = "SupplierInvoiceMatchLineResponse",
      description = "One line, with all three documents' figures side by side.")
  public record SupplierInvoiceMatchLineResponse(
      UUID variantId,
      @Schema(description = "What the purchase order asked for.") BigDecimal qtyOrdered,
      @Schema(description = "What has arrived across every receipt on that order.")
          BigDecimal qtyReceived,
      @Schema(description = "What earlier invoices on this order already billed for this variant.")
          BigDecimal qtyInvoicedBefore,
      @Schema(description = "What this invoice bills.") BigDecimal qtyInvoiced,
      @Schema(description = "The price the order agreed; null when the variant was never ordered.")
          BigDecimal orderedUnitPrice,
      BigDecimal invoicedUnitPrice,
      @Schema(
              description =
                  "Every disagreement found, empty when the line agreed. INVOICED_ABOVE_RECEIVED,"
                      + " NOT_RECEIVED, NOT_ON_ORDER, PRICE_ABOVE_ORDER, PRICE_BELOW_ORDER.")
          List<String> variances) {}

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
