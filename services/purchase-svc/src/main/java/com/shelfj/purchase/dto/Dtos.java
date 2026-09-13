package com.shelfj.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
          Integer paymentTermsDays,
      @Schema(description = "Where remittance advice is emailed when a payment run pays it.")
          @jakarta.validation.constraints.Email
          @Size(max = 254)
          String remittanceEmail,
      @Schema(description = "The account holder's name, as the bank has it.") @Size(max = 140)
          String bankAccountName,
      @Schema(description = "UK sort code, six digits; dashes and spaces allowed.") @Size(max = 12)
          String bankSortCode,
      @Schema(description = "UK account number, eight digits.") @Size(max = 12)
          String bankAccountNumber,
      @Schema(description = "IBAN for an international payment; its check digits are verified.")
          @Size(max = 42)
          String bankIban,
      @Schema(description = "BIC (SWIFT code), with an IBAN.") @Size(max = 14) String bankBic) {}

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
          Integer paymentTermsDays,
      @Schema(description = "Remittance email; unchanged when omitted, cleared when empty.")
          @Size(max = 254)
          String remittanceEmail,
      @Schema(
              description =
                  "Bank details, all or none: when any is given the whole set is replaced."
                      + " Unchanged when all are omitted. Needs finance.payments.")
          @Size(max = 140)
          String bankAccountName,
      @Size(max = 12) String bankSortCode,
      @Size(max = 12) String bankAccountNumber,
      @Size(max = 42) String bankIban,
      @Size(max = 14) String bankBic,
      @Schema(description = "True to remove the bank details. Needs finance.payments.")
          Boolean clearBankDetails) {}

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
      Instant updatedAt,
      String remittanceEmail,
      String bankAccountName,
      String bankSortCode,
      @Schema(
              description =
                  "The last four digits only; the full number reaches the bank file alone.")
          String bankAccountNumberMasked,
      @Schema(description = "The last four characters only.") String bankIbanMasked,
      String bankBic,
      @Schema(description = "Whether a payment run can pay this supplier.") boolean hasBankDetails,
      @Schema(description = "When the bank details last changed; a run flags a recent change.")
          Instant bankDetailsChangedAt) {}

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
      @Schema(description = "Ordered minus received, floored at zero.") BigDecimal qtyOutstanding,
      @Schema(description = "What has gone back to the supplier out of what was received (07.8).")
          BigDecimal qtyReturned) {}

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

  // ── Return to vendor and debit note (07.8) ────────────────────────────────────

  @Schema(
      name = "RaiseVendorReturnRequest",
      description =
          "Send goods back to the supplier against a received purchase order and raise the debit"
              + " note for their value at the order's prices. The store is the order's; the"
              + " quantity per variant may not exceed what was received less what was already"
              + " returned.")
  public record RaiseVendorReturnRequest(
      @NotNull UUID poId,
      @Schema(
              description =
                  "DAMAGED, WRONG_ITEM, OVER_DELIVERED, QUALITY, EXPIRED, RECALL or OTHER.")
          @NotBlank
          String reason,
      @Size(max = 500) String notes,
      @NotNull @Valid List<VendorReturnLineRequest> lines) {}

  @Schema(name = "VendorReturnLineRequest")
  public record VendorReturnLineRequest(
      @NotNull UUID variantId, @NotNull @DecimalMin("0.001") BigDecimal qty) {}

  @Schema(
      name = "RecordCreditNoteRequest",
      description = "The supplier's credit note against a return, which closes it.")
  public record RecordCreditNoteRequest(
      @NotBlank @Size(max = 64) String creditNoteNumber,
      @Schema(description = "ISO date, e.g. 2026-09-12.") @NotBlank String creditNoteDate,
      @Schema(description = "What the supplier credited; the debit note's gross when omitted.")
          @DecimalMin("0")
          BigDecimal amount) {}

  @Schema(
      name = "VendorReturnResponse",
      description = "A return to vendor and its debit note; the credit note once recorded.")
  public record VendorReturnResponse(
      UUID id,
      UUID poId,
      UUID supplierId,
      UUID storeId,
      @Schema(description = "RAISED or CREDITED.") String status,
      String reason,
      String notes,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      @Schema(description = "The debit note's number, sequential per business, e.g. DN-000012.")
          String debitNoteNumber,
      Instant raisedAt,
      UUID raisedBy,
      String creditNoteNumber,
      String creditNoteDate,
      BigDecimal creditAmount,
      Instant creditedAt,
      UUID creditedBy,
      List<VendorReturnLineResponse> lines) {}

  @Schema(name = "VendorReturnLineResponse")
  public record VendorReturnLineResponse(
      UUID id,
      UUID variantId,
      BigDecimal qty,
      @Schema(description = "The order's price for the variant when the return was raised.")
          BigDecimal unitPrice,
      String vatCode,
      BigDecimal lineNet) {}

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
      @Schema(
              description =
                  "The total printed on the supplier's document, when keyed. Compared with the sum"
                      + " of the lines plus VAT; a difference beyond the configured tolerance flags"
                      + " the invoice with TOTAL_MISMATCH.")
          @DecimalMin("0")
          BigDecimal statedGross,
      @NotNull @Size(max = 200) List<CaptureSupplierInvoiceLine> lines) {}

  @Schema(
      name = "ResolveSupplierInvoiceRequest",
      description = "The decision on a flagged invoice, and why.")
  public record ResolveSupplierInvoiceRequest(
      @Schema(description = "APPROVE releases it for payment; REJECT reverses its posting.")
          @NotBlank
          @Size(max = 16)
          String action,
      @NotBlank @Size(max = 500) String reason) {}

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
                      + " FLAGGED when at least one did not. Flagging never blocks capture."
                      + " APPROVED and REJECTED are a manager's decision on a flagged one.")
          String status,
      UUID createdBy,
      Instant createdAt,
      @Schema(description = "Invoice date plus the supplier's payment terms.") LocalDate dueDate,
      @Schema(description = "The supplier's stated total, when keyed.") BigDecimal statedGross,
      @Schema(description = "Header-level variances: TOTAL_MISMATCH. Empty when the header agreed.")
          List<String> headerVariances,
      @Schema(description = "When the AP posting was written; null for invoices captured before.")
          Instant postedAt,
      @Schema(description = "Whether it may be paid: MATCHED, or FLAGGED and then APPROVED.")
          boolean payable,
      Instant resolvedAt,
      UUID resolvedBy,
      String resolutionReason,
      @Schema(description = "When a payment run paid it (17.10); null while unpaid.")
          Instant paidAt,
      @Schema(description = "The payment run that paid it.") UUID paymentRunId,
      @Schema(description = "Whether it has been paid.") boolean paid,
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
      @Schema(description = "The lines of one double-entry posting share a journal id.")
          UUID journalId,
      @Schema(
              description =
                  "GOODS_RECEIPT, SUPPLIER_INVOICE, INVOICE_REVERSAL, CREDIT_NOTE, INTERCOMPANY,"
                      + " SETTLEMENT or JOURNAL; null for rows written before postings existed.")
          String sourceType,
      @Schema(description = "The store the posting belongs to; null for a tenant-level journal.")
          UUID storeId,
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

  @Schema(name = "JournalLineRequest", description = "One line: a debit or a credit, never both.")
  public record JournalLineRequest(
      @NotBlank @Size(max = 10) String nominalCode,
      @Size(max = 100) String nominalName,
      @DecimalMin("0") BigDecimal debit,
      @DecimalMin("0") BigDecimal credit) {}

  @Schema(name = "PostJournalRequest", description = "A manual journal. Must balance.")
  public record PostJournalRequest(
      @Schema(description = "yyyy-MM-dd") @NotBlank @Size(max = 10) String entryDate,
      @NotBlank @Size(max = 500) String description,
      @Schema(description = "The store it belongs to, optional; its period must be open.")
          String storeId,
      @NotNull @Size(min = 2, max = 50) @Valid List<JournalLineRequest> lines) {}

  @Schema(name = "JournalResponse", description = "One posting, read back whole.")
  public record JournalResponse(
      UUID journalId,
      LocalDate entryDate,
      String description,
      String sourceType,
      UUID sourceRef,
      UUID storeId,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      List<NominalLedgerEntryResponse> lines) {}

  @Schema(name = "TrialBalanceRowResponse")
  public record TrialBalanceRowResponse(
      String nominalCode,
      String nominalName,
      BigDecimal debit,
      BigDecimal credit,
      @Schema(
              description =
                  "Debit less credit: positive for assets and expenses, negative for liabilities"
                      + " and income.")
          BigDecimal balance) {}

  @Schema(
      name = "TrialBalanceResponse",
      description =
          "Every nominal code's movement over the range. balanced is the ledger's own invariant:"
              + " false means a posting was written that does not balance.")
  public record TrialBalanceResponse(
      LocalDate from,
      LocalDate to,
      UUID storeId,
      List<TrialBalanceRowResponse> rows,
      BigDecimal totalDebit,
      BigDecimal totalCredit,
      boolean balanced) {}

  // ── Supplier payment runs (17.10) ─────────────────────────────────────────────

  @Schema(name = "ProposePaymentRunRequest")
  public record ProposePaymentRunRequest(
      @Schema(
              description = "Invoices due on or before this date are proposed.",
              example = "2026-09-30")
          @NotBlank
          String payUpTo,
      @Schema(
              description =
                  "The date the payment is made and posted: today or later, within a year.")
          @NotBlank
          String paymentDate,
      @Schema(description = "ISO 4217. A run pays one currency; the tenant's own when omitted.")
          @Size(min = 3, max = 3)
          String currency) {}

  @Schema(name = "CancelPaymentRunRequest")
  public record CancelPaymentRunRequest(
      @Schema(description = "Why the run is abandoned; kept on the run.") @NotBlank @Size(max = 500)
          String reason) {}

  @Schema(name = "PaymentRunResponse")
  public record PaymentRunResponse(
      UUID id,
      @Schema(description = "What the bank statement and the remittance advice say.")
          String reference,
      @Schema(description = "PROPOSED, APPROVED, PAID or CANCELLED.") String status,
      LocalDate payUpTo,
      LocalDate paymentDate,
      String currency,
      @Schema(description = "What the run pays: invoices less credit notes, across suppliers.")
          BigDecimal total,
      UUID proposedBy,
      Instant proposedAt,
      UUID approvedBy,
      Instant approvedAt,
      UUID paidBy,
      Instant paidAt,
      UUID cancelledBy,
      Instant cancelledAt,
      String cancelReason,
      List<PaymentRunSupplierResponse> suppliers,
      @Schema(
              description =
                  "Suppliers with something due that the run does not pay, and why:"
                      + " NO_BANK_DETAILS or NET_NOT_POSITIVE. On a run already proposed, a"
                      + " supplier that can no longer be paid; approving or paying it is refused.")
          List<PaymentRunExcludedResponse> excluded) {}

  @Schema(name = "PaymentRunSupplierResponse")
  public record PaymentRunSupplierResponse(
      UUID supplierId,
      String name,
      @Schema(description = "Invoices less credit notes: what this supplier is paid.")
          BigDecimal net,
      @Schema(description = "Whether a remittance advice can be emailed.")
          boolean remittanceEmailOnFile,
      @Schema(description = "BANK_DETAILS_CHANGED_RECENTLY when they changed in the last 14 days.")
          List<String> warnings,
      List<PaymentRunDocumentResponse> documents) {}

  @Schema(name = "PaymentRunDocumentResponse")
  public record PaymentRunDocumentResponse(
      @Schema(description = "INVOICE (paid) or CREDIT_NOTE (offset).") String type,
      UUID documentId,
      UUID storeId,
      String reference,
      LocalDate documentDate,
      LocalDate dueDate,
      BigDecimal amount) {}

  @Schema(name = "PaymentRunExcludedResponse")
  public record PaymentRunExcludedResponse(
      UUID supplierId, String name, String reason, BigDecimal net) {}
}
