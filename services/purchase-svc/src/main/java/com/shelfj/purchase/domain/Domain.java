package com.shelfj.purchase.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain records for purchase-svc. Never returned over HTTP — use DTOs. */
public final class Domain {

  private Domain() {}

  // ── Nominal code constants (Sage/Xero UK standard chart of accounts) ─────────
  public static final String CODE_DEBTORS = "1100";
  public static final String CODE_BANK = "1200";
  public static final String CODE_CREDITORS = "2100";
  public static final String CODE_VAT_OUTPUT = "2200";
  public static final String CODE_VAT_INPUT = "2201";
  public static final String CODE_IC_SALES = "4000";
  public static final String CODE_IC_PURCHASES = "5000";

  public static final String NAME_DEBTORS = "Trade Debtors Control Account";
  public static final String NAME_BANK = "Bank Current Account";
  public static final String NAME_CREDITORS = "Trade Creditors Control Account";
  public static final String NAME_VAT_OUTPUT = "VAT Output Account";
  public static final String NAME_VAT_INPUT = "VAT Input Account";
  public static final String NAME_IC_SALES = "Sales - Intercompany";
  public static final String NAME_IC_PURCHASES = "Purchases - Intercompany";

  // ── Supplier ──────────────────────────────────────────────────────────────────
  public record Supplier(
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
  public static final String PO_DRAFT = "DRAFT";
  public static final String PO_SUBMITTED = "SUBMITTED";

  /** Some of the order has arrived and more is still expected. Receivable, like SUBMITTED. */
  public static final String PO_PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";

  public static final String PO_RECEIVED = "RECEIVED";

  /**
   * Short-closed: part arrived, the rest never will. Distinct from {@link #PO_RECEIVED} because "we
   * got it all" and "we gave up on the rest" are different facts, and a supplier scorecard that
   * cannot tell them apart is worthless. Distinct from {@link #PO_CANCELLED} because stock is
   * booked against this order — SJ-D3's reason for refusing to cancel a received one.
   */
  public static final String PO_CLOSED = "CLOSED";

  public static final String PO_CANCELLED = "CANCELLED";

  public record PurchaseOrder(
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
      Instant updatedAt,
      Instant cancelledAt,
      String cancelledReason,
      Instant closedAt,
      String closedReason) {}

  /**
   * How much of one ordered line has actually turned up.
   *
   * <p>Receipts are matched to order lines by variant rather than by line id: {@code
   * goods_receipt_lines} has never carried a {@code po_line_id}, and a delivery note names products
   * rather than order rows. Two lines on one order for the same variant therefore aggregate here,
   * which is also the answer a warehouse gives when counting what arrived.
   *
   * @param variantId the product
   * @param qtyOrdered what the purchase order asked for
   * @param qtyReceived what has arrived across every receipt against this order
   * @param qtyOutstanding ordered minus received, floored at zero
   */
  public record PurchaseOrderLineProgress(
      UUID variantId, BigDecimal qtyOrdered, BigDecimal qtyReceived, BigDecimal qtyOutstanding) {}

  public record PurchaseOrderLine(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      Instant createdAt) {}

  // ── Goods Receipt (GRN) ───────────────────────────────────────────────────────
  public record GoodsReceipt(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID storeId,
      Instant receivedAt,
      Instant createdAt,
      String idempotencyKey) {}

  public record GoodsReceiptLine(
      UUID id,
      UUID tenantId,
      UUID grId,
      UUID variantId,
      BigDecimal qtyReceived,
      Instant createdAt) {}

  // ── Intercompany Invoice (Gap #20) ────────────────────────────────────────────
  public static final String INV_AR = "AR";
  public static final String INV_AP = "AP";
  public static final String INV_RAISED = "RAISED";
  public static final String INV_SETTLED = "SETTLED";

  public record IntercompanyInvoice(
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

  // ── Nominal Ledger Entry (FRS 102, append-only) ───────────────────────────────
  public record NominalLedgerEntry(
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
