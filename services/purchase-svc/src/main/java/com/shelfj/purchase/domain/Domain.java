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

  /**
   * Stock on hand, the asset a goods receipt recognises. The default when the store has no GL
   * mapping in inventory-svc; a mapped store posts to its own code instead (17.3).
   */
  public static final String CODE_STOCK = "1001";

  /**
   * Goods received not invoiced: the accrual a receipt credits and the invoice debits, so that
   * between the lorry and the paperwork the liability is visible and after both it nets to zero.
   */
  public static final String CODE_GRIR = "2109";

  public static final String NAME_STOCK = "Stock";
  public static final String NAME_GRIR = "Goods Received Not Invoiced";

  // ── What produced a ledger posting ────────────────────────────────────────────
  public static final String SOURCE_GOODS_RECEIPT = "GOODS_RECEIPT";
  public static final String SOURCE_SUPPLIER_INVOICE = "SUPPLIER_INVOICE";
  public static final String SOURCE_INVOICE_REVERSAL = "INVOICE_REVERSAL";
  public static final String SOURCE_CREDIT_NOTE = "CREDIT_NOTE";
  public static final String SOURCE_INTERCOMPANY = "INTERCOMPANY";
  public static final String SOURCE_SETTLEMENT = "SETTLEMENT";
  public static final String SOURCE_JOURNAL = "JOURNAL";
  public static final String SOURCE_SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT";

  // ── Supplier ──────────────────────────────────────────────────────────────────
  /**
   * A supplier.
   *
   * @param remittanceEmail where remittance advice is emailed when a payment run pays it (17.10)
   * @param bankDetailsChangedAt when its bank details last changed, and {@code
   *     bankDetailsChangedBy} who changed them: a change shortly before a payment is the pattern
   *     payment-diversion fraud leaves, so a run flags it
   */
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
      Instant updatedAt,
      String remittanceEmail,
      String bankAccountName,
      String bankSortCode,
      String bankAccountNumber,
      String bankIban,
      String bankBic,
      Instant bankDetailsChangedAt,
      UUID bankDetailsChangedBy) {

    /** The bank details as one validated value. */
    public BankAccount.Details bank() {
      return new BankAccount.Details(
          bankAccountName, bankSortCode, bankAccountNumber, bankIban, bankBic);
    }

    /** Whether a payment run can pay this supplier. */
    public boolean hasBankDetails() {
      return bank().payable();
    }
  }

  // ── Purchase Order ────────────────────────────────────────────────────────────
  public static final String PO_DRAFT = "DRAFT";

  /**
   * Raised, but above the raiser's own spend authority — nobody entitled to commit this much has
   * agreed to it yet. A state rather than a flag on SUBMITTED: "waiting for a decision" and
   * "decided" are different facts, and a supplier must never be sent an order that is merely
   * waiting.
   */
  public static final String PO_PENDING_APPROVAL = "PENDING_APPROVAL";

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
      String closedReason,
      UUID createdBy,
      UUID approvedBy,
      Instant approvedAt) {}

  // ── Purchase order approval (spend authority) ─────────────────────────────────

  /** A submission that exceeded the raiser's authority and is waiting for someone else's. */
  public static final String APPROVAL_REQUESTED = "REQUESTED";

  public static final String APPROVAL_APPROVED = "APPROVED";
  public static final String APPROVAL_REJECTED = "REJECTED";

  /**
   * One decision in a purchase order's approval history. Append-only (golden rule #8).
   *
   * <p>A column pair on the order would have covered a single decision, the way V3 handled
   * cancellation — but a rejection sends the order back to DRAFT to be corrected and resubmitted,
   * so one order can cycle through several. A spend-authority trail that keeps only the last
   * decision is not an audit trail.
   *
   * @param totalNet the figure the decision was made against, captured at decision time rather than
   *     read back later: the order can be edited after a rejection, and an approval that silently
   *     re-points at a larger total is the whole attack this feature exists to stop
   * @param authority what the decider was entitled to commit, so the trail can still answer "were
   *     they allowed to?" after the configuration has changed
   */
  public record PurchaseOrderApproval(
      UUID id,
      UUID tenantId,
      UUID poId,
      String decision,
      BigDecimal totalNet,
      String currency,
      BigDecimal authority,
      UUID decidedBy,
      String decidedRole,
      String reason,
      Instant decidedAt) {}

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
      UUID variantId,
      BigDecimal qtyOrdered,
      BigDecimal qtyReceived,
      BigDecimal qtyOutstanding,
      /** What has gone back to the supplier out of what was received (07.8). */
      BigDecimal qtyReturned) {

    /** Progress before returns existed: nothing returned. */
    public PurchaseOrderLineProgress(
        UUID variantId, BigDecimal qtyOrdered, BigDecimal qtyReceived, BigDecimal qtyOutstanding) {
      this(variantId, qtyOrdered, qtyReceived, qtyOutstanding, BigDecimal.ZERO);
    }
  }

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

  // ── Supplier invoice (three-way match) ────────────────────────────────────────

  /** Every line agreed with the order and the receipt, inside tolerance. */
  public static final String INVOICE_MATCHED = "MATCHED";

  /** At least one line did not. Captured and posted anyway; blocked for payment until resolved. */
  public static final String INVOICE_FLAGGED = "FLAGGED";

  /** A flagged invoice a manager released for payment, with a reason. */
  public static final String INVOICE_APPROVED = "APPROVED";

  /**
   * A flagged invoice a manager refused. Its posting is reversed and the quantities it billed no
   * longer count against the order, so the supplier's corrected invoice matches cleanly.
   */
  public static final String INVOICE_REJECTED = "REJECTED";

  /**
   * A supplier's invoice against a purchase order.
   *
   * @param invoiceNumber the supplier's own reference as printed on the document; unique per
   *     supplier case-insensitively, because the commonest way to pay twice is for two people to
   *     type the same paper reference on the same morning
   * @param status {@link #INVOICE_MATCHED}, {@link #INVOICE_FLAGGED}, {@link #INVOICE_APPROVED} or
   *     {@link #INVOICE_REJECTED}
   * @param dueDate the invoice date plus the supplier's payment terms
   * @param statedGross the total printed on the document, when keyed; null when not
   * @param headerVariances comma-separated header-level variances, empty when the header agreed
   * @param postedAt when the AP posting was written; null for invoices captured before postings
   * @param resolvedAt when a flagged invoice was approved or rejected
   * @param resolvedBy who decided
   * @param resolutionReason why
   */
  public record SupplierInvoice(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String status,
      Instant matchedAt,
      UUID createdBy,
      Instant createdAt,
      LocalDate dueDate,
      BigDecimal statedGross,
      String headerVariances,
      Instant postedAt,
      Instant resolvedAt,
      UUID resolvedBy,
      String resolutionReason,
      Instant paidAt,
      UUID paymentRunId) {

    /** Whether the invoice may be paid: matched, or flagged and then approved. */
    public boolean payable() {
      return INVOICE_MATCHED.equals(status) || INVOICE_APPROVED.equals(status);
    }
  }

  /**
   * One line of a supplier invoice, carrying the match outcome it was captured with.
   *
   * @param variances comma-separated variance codes, empty when the line agreed. Stored rather than
   *     recomputed on read: the purchase order can be amended afterwards, and an invoice that
   *     silently re-matched against the amended order would erase the disagreement it was flagged
   *     for
   */
  public record SupplierInvoiceLine(
      UUID id,
      UUID tenantId,
      UUID invoiceId,
      UUID variantId,
      BigDecimal qtyInvoiced,
      BigDecimal unitPrice,
      String vatCode,
      String variances,
      Instant createdAt) {}

  // ── Return to vendor and debit note (07.8) ────────────────────────────────────

  /** The goods have gone back and the debit note is issued. */
  public static final String RETURN_RAISED = "RAISED";

  /** The supplier's credit note has been recorded against the return. */
  public static final String RETURN_CREDITED = "CREDITED";

  /** Why goods went back. The reason is what a supplier scorecard and a debit note both cite. */
  public static final java.util.Set<String> RETURN_REASONS =
      java.util.Set.of(
          "DAMAGED", "WRONG_ITEM", "OVER_DELIVERED", "QUALITY", "EXPIRED", "RECALL", "OTHER");

  /**
   * A return to vendor: goods sent back against a purchase order, with the debit note raised for
   * their value at the order's prices, and the supplier's credit note once it arrives.
   */
  public record VendorReturn(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID supplierId,
      UUID storeId,
      String status,
      String reason,
      String notes,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String debitNoteNumber,
      Instant raisedAt,
      UUID raisedBy,
      String creditNoteNumber,
      LocalDate creditNoteDate,
      BigDecimal creditAmount,
      Instant creditedAt,
      UUID creditedBy,
      String idempotencyKey) {}

  /** One variant going back, priced at the order's price when the return was raised. */
  public record VendorReturnLine(
      UUID id,
      UUID tenantId,
      UUID returnId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      BigDecimal lineNet,
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
      Instant createdAt,
      UUID journalId,
      String sourceType,
      UUID storeId) {}

  /** One journal read back whole: its lines and the header they share. */
  public record Journal(
      UUID journalId,
      LocalDate entryDate,
      String description,
      String sourceType,
      UUID sourceRef,
      UUID storeId,
      java.util.List<NominalLedgerEntry> lines) {

    public BigDecimal totalDebit() {
      return lines.stream().map(NominalLedgerEntry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalCredit() {
      return lines.stream()
          .map(NominalLedgerEntry::credit)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /** One nominal code's movement and balance over a trial balance's range. */
  public record TrialBalanceRow(
      String nominalCode, String nominalName, BigDecimal debit, BigDecimal credit) {

    /** Debit less credit: positive for an asset or expense balance, negative for a liability. */
    public BigDecimal balance() {
      return debit.subtract(credit);
    }
  }
}
