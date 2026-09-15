package com.shelfj.purchase.mapper;

import com.shelfj.purchase.domain.Domain;
import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.IntercompanyInvoice;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.Domain.Supplier;
import com.shelfj.purchase.domain.SpendAuthority;
import com.shelfj.purchase.dto.Dtos;
import com.shelfj.purchase.dto.Dtos.GoodsReceiptLineResponse;
import com.shelfj.purchase.dto.Dtos.GoodsReceiptResponse;
import com.shelfj.purchase.dto.Dtos.IntercompanyInvoiceResponse;
import com.shelfj.purchase.dto.Dtos.NominalLedgerEntryResponse;
import com.shelfj.purchase.dto.Dtos.PurchaseOrderLineProgressResponse;
import com.shelfj.purchase.dto.Dtos.PurchaseOrderLineResponse;
import com.shelfj.purchase.dto.Dtos.PurchaseOrderResponse;
import com.shelfj.purchase.dto.Dtos.SupplierResponse;
import java.util.List;

/** Domain → DTO mappers. No business logic. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a supplier to its wire form.
   *
   * @param s the supplier to convert
   * @return its API representation
   */
  public static SupplierResponse toDto(Supplier s) {
    return new SupplierResponse(
        s.id(),
        s.tenantId(),
        s.name(),
        s.vatNumber(),
        s.vatRegistered(),
        s.countryCode(),
        s.currency(),
        s.paymentTermsDays(),
        s.createdAt(),
        s.updatedAt(),
        s.remittanceEmail(),
        s.bankAccountName(),
        s.bankSortCode(),
        com.shelfj.purchase.domain.BankAccount.masked(s.bankAccountNumber()),
        com.shelfj.purchase.domain.BankAccount.masked(s.bankIban()),
        s.bankBic(),
        s.hasBankDetails(),
        s.bankDetailsChangedAt());
  }

  /**
   * Converts a purchase order header to its wire form.
   *
   * @param po the purchase order header to convert
   * @return its API representation, including the stored net/VAT/gross totals
   */
  public static PurchaseOrderResponse toDto(PurchaseOrder po) {
    return new PurchaseOrderResponse(
        po.id(),
        po.tenantId(),
        po.supplierId(),
        po.storeId(),
        po.status(),
        po.currency(),
        po.totalNet(),
        po.totalVat(),
        po.totalGross(),
        po.expectedDelivery(),
        po.createdAt(),
        po.updatedAt(),
        po.cancelledAt(),
        po.cancelledReason(),
        po.closedAt(),
        po.closedReason(),
        po.createdBy(),
        po.approvedBy(),
        po.approvedAt());
  }

  /**
   * @param lines the invoice's stored lines
   * @param positions what the order and receipts say now, keyed by variant, so the screen can show
   *     the three documents side by side
   */
  public static Dtos.SupplierInvoiceResponse toDto(
      Domain.SupplierInvoice inv,
      java.util.List<Domain.SupplierInvoiceLine> lines,
      java.util.List<com.shelfj.purchase.domain.ThreeWayMatch.OrderPosition> positions) {
    var byVariant =
        positions.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    com.shelfj.purchase.domain.ThreeWayMatch.OrderPosition::variantId,
                    p -> p,
                    (a, b) -> a));
    var rows =
        lines.stream()
            .map(
                l -> {
                  var p = byVariant.get(l.variantId());
                  return new Dtos.SupplierInvoiceMatchLineResponse(
                      l.variantId(),
                      p == null ? java.math.BigDecimal.ZERO : p.qtyOrdered(),
                      p == null ? java.math.BigDecimal.ZERO : p.qtyReceived(),
                      // What OTHER invoices billed: the stored total includes this one.
                      p == null
                          ? java.math.BigDecimal.ZERO
                          : p.qtyAlreadyInvoiced()
                              .subtract(l.qtyInvoiced())
                              .max(java.math.BigDecimal.ZERO),
                      l.qtyInvoiced(),
                      p == null ? null : p.orderedUnitPrice(),
                      l.unitPrice(),
                      l.variances() == null || l.variances().isBlank()
                          ? java.util.List.of()
                          : java.util.List.of(l.variances().split(",")));
                })
            .toList();
    return new Dtos.SupplierInvoiceResponse(
        inv.id(),
        inv.poId(),
        inv.supplierId(),
        inv.invoiceNumber(),
        inv.invoiceDate(),
        inv.currency(),
        inv.netAmount(),
        inv.vatAmount(),
        inv.grossAmount(),
        inv.status(),
        inv.createdBy(),
        inv.createdAt(),
        inv.dueDate(),
        inv.statedGross(),
        inv.headerVariances() == null || inv.headerVariances().isBlank()
            ? java.util.List.of()
            : java.util.List.of(inv.headerVariances().split(",")),
        inv.postedAt(),
        inv.payable(),
        inv.resolvedAt(),
        inv.resolvedBy(),
        inv.resolutionReason(),
        inv.paidAt(),
        inv.paymentRunId(),
        inv.paidAt() != null,
        rows);
  }

  /**
   * Converts one approval-trail row to its wire form.
   *
   * @param a the trail entry to convert
   * @return its API representation, carrying the figure and authority as they stood at the time
   */
  public static Dtos.PurchaseOrderApprovalResponse toDto(Domain.PurchaseOrderApproval a) {
    return new Dtos.PurchaseOrderApprovalResponse(
        a.id(),
        a.poId(),
        a.decision(),
        a.totalNet(),
        a.currency(),
        a.authority(),
        a.decidedBy(),
        a.decidedRole(),
        a.reason(),
        a.decidedAt());
  }

  /**
   * @param currency the currency the authority was asked about, echoed so a client caching several
   *     answers cannot mix them up
   */
  public static Dtos.SpendAuthorityResponse toDto(SpendAuthority a, String currency, boolean off) {
    return new Dtos.SpendAuthorityResponse(
        currency, a.ceiling(), a.unlimited(), a.role(), off, a.reason());
  }

  /**
   * Converts a purchase order line to its wire form.
   *
   * @param line the purchase order line to convert
   * @return its API representation
   */
  public static PurchaseOrderLineResponse toDto(PurchaseOrderLine line) {
    return new PurchaseOrderLineResponse(
        line.id(),
        line.poId(),
        line.variantId(),
        line.qty(),
        line.unitPrice(),
        line.vatCode(),
        line.createdAt());
  }

  /**
   * Converts a goods receipt and its lines to the wire form.
   *
   * @param gr the receipt header
   * @param lines the receipt's lines
   * @return its API representation, header and lines together
   */
  public static GoodsReceiptResponse toDto(GoodsReceipt gr, List<GoodsReceiptLine> lines) {
    return new GoodsReceiptResponse(
        gr.id(),
        gr.tenantId(),
        gr.poId(),
        gr.storeId(),
        gr.receivedAt(),
        lines.stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts one goods receipt line to its wire form.
   *
   * @param l the receipt line to convert
   * @return its API representation
   */
  public static GoodsReceiptLineResponse toDto(GoodsReceiptLine l) {
    return new GoodsReceiptLineResponse(l.id(), l.variantId(), l.qtyReceived(), l.createdAt());
  }

  /**
   * Converts an intercompany invoice to its wire form.
   *
   * @param inv the intercompany invoice to convert
   * @return its API representation
   */
  public static IntercompanyInvoiceResponse toDto(IntercompanyInvoice inv) {
    return new IntercompanyInvoiceResponse(
        inv.id(),
        inv.tenantId(),
        inv.invoiceType(),
        inv.fromStoreId(),
        inv.toStoreId(),
        inv.transferRef(),
        inv.netAmount(),
        inv.vatAmount(),
        inv.grossAmount(),
        inv.vatCode(),
        inv.vatDisregarded(),
        inv.status(),
        inv.invoiceDate(),
        inv.paymentDueDate(),
        inv.currency(),
        inv.createdAt());
  }

  /**
   * Converts one nominal-ledger entry to its wire form.
   *
   * @param e the ledger entry to convert
   * @return its API representation
   */
  public static NominalLedgerEntryResponse toDto(NominalLedgerEntry e) {
    return new NominalLedgerEntryResponse(
        e.id(),
        e.tenantId(),
        e.journalId(),
        e.sourceType(),
        e.storeId(),
        e.entryDate(),
        e.nominalCode(),
        e.nominalName(),
        e.debit(),
        e.credit(),
        e.description(),
        e.sourceRef(),
        e.createdAt());
  }

  /**
   * Converts one line-progress row to its wire form.
   *
   * @param p ordered against received for a single variant
   * @return its API representation, including the balance still due
   */
  public static PurchaseOrderLineProgressResponse toDto(Domain.PurchaseOrderLineProgress p) {
    return new PurchaseOrderLineProgressResponse(
        p.variantId(), p.qtyOrdered(), p.qtyReceived(), p.qtyOutstanding(), p.qtyReturned());
  }

  /**
   * Converts a return to vendor and its lines to the wire form: the debit note as a document.
   *
   * @param r the return
   * @param lines its lines
   * @return its API representation
   */
  public static com.shelfj.purchase.dto.Dtos.VendorReturnResponse toDto(
      Domain.VendorReturn r, java.util.List<Domain.VendorReturnLine> lines) {
    return new com.shelfj.purchase.dto.Dtos.VendorReturnResponse(
        r.id(),
        r.poId(),
        r.supplierId(),
        r.storeId(),
        r.status(),
        r.reason(),
        r.notes(),
        r.currency(),
        r.netAmount(),
        r.vatAmount(),
        r.grossAmount(),
        r.debitNoteNumber(),
        r.raisedAt(),
        r.raisedBy(),
        r.creditNoteNumber(),
        r.creditNoteDate() == null ? null : r.creditNoteDate().toString(),
        r.creditAmount(),
        r.creditedAt(),
        r.creditedBy(),
        lines.stream()
            .map(
                l ->
                    new com.shelfj.purchase.dto.Dtos.VendorReturnLineResponse(
                        l.id(), l.variantId(), l.qty(), l.unitPrice(), l.vatCode(), l.lineNet()))
            .toList());
  }

  /**
   * Converts a journal to its wire form: the header its lines share and the lines.
   *
   * @param j the journal
   * @return its API representation
   */
  public static Dtos.JournalResponse toDto(Domain.Journal j) {
    return new Dtos.JournalResponse(
        j.journalId(),
        j.entryDate(),
        j.description(),
        j.sourceType(),
        j.sourceRef(),
        j.storeId(),
        j.totalDebit(),
        j.totalCredit(),
        j.lines().stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts trial balance rows to the wire form, with the totals and whether they agree.
   *
   * @param rows one per nominal code
   * @param from the range start, or null
   * @param to the range end, or null
   * @param storeId the store filter, or null
   * @return the trial balance
   */
  public static Dtos.TrialBalanceResponse toTrialBalance(
      java.util.List<Domain.TrialBalanceRow> rows,
      java.time.LocalDate from,
      java.time.LocalDate to,
      java.util.UUID storeId) {
    java.math.BigDecimal debit = java.math.BigDecimal.ZERO;
    java.math.BigDecimal credit = java.math.BigDecimal.ZERO;
    var out = new java.util.ArrayList<Dtos.TrialBalanceRowResponse>(rows.size());
    for (Domain.TrialBalanceRow r : rows) {
      debit = debit.add(r.debit());
      credit = credit.add(r.credit());
      out.add(
          new Dtos.TrialBalanceRowResponse(
              r.nominalCode(), r.nominalName(), r.debit(), r.credit(), r.balance()));
    }
    return new Dtos.TrialBalanceResponse(
        from, to, storeId, out, debit, credit, debit.compareTo(credit) == 0);
  }

  /**
   * Converts a payment run to its wire form: the run, what it pays each supplier, and any supplier
   * it does not pay. Bank details never appear here; they reach the bank file alone.
   */
  public static Dtos.PaymentRunResponse toDto(com.shelfj.purchase.domain.PaymentRuns.View v) {
    var r = v.run();
    return new Dtos.PaymentRunResponse(
        r.id(),
        r.reference(),
        r.status(),
        r.payUpTo(),
        r.paymentDate(),
        r.currency(),
        r.total(),
        r.proposedBy(),
        r.proposedAt(),
        r.approvedBy(),
        r.approvedAt(),
        r.paidBy(),
        r.paidAt(),
        r.cancelledBy(),
        r.cancelledAt(),
        r.cancelReason(),
        v.proposal().payments().stream()
            .map(
                p -> {
                  var s = v.suppliers().get(p.supplierId());
                  return new Dtos.PaymentRunSupplierResponse(
                      p.supplierId(),
                      p.name(),
                      p.net(),
                      s != null && s.remittanceEmail() != null,
                      p.warnings(),
                      p.documents().stream()
                          .map(
                              d ->
                                  new Dtos.PaymentRunDocumentResponse(
                                      d.type(),
                                      d.documentId(),
                                      d.storeId(),
                                      d.reference(),
                                      d.documentDate(),
                                      d.dueDate(),
                                      d.amount()))
                          .toList());
                })
            .toList(),
        v.proposal().excluded().stream()
            .map(
                e ->
                    new Dtos.PaymentRunExcludedResponse(
                        e.supplierId(), e.name(), e.reason(), e.net()))
            .toList());
  }

  /** An order left open on the sales receipts clearing account, in wire form. */
  public static Dtos.SalesClearingResponse toDto(Domain.OpenClearing o) {
    return new Dtos.SalesClearingResponse(
        o.orderId(), o.storeId(), o.balance(), o.firstPosted(), o.lastPosted());
  }

  public static Dtos.DeferredRevenueResponse toDto(Domain.DeferredRevenueView v) {
    return new Dtos.DeferredRevenueResponse(
        toDto(v.current()),
        v.history().stream().map(s -> toDto(s)).toList(),
        v.points().outstanding(),
        v.points().deferred(),
        v.points().unmatched(),
        v.waiting(),
        v.giftCards().loaded(),
        v.giftCards().redeemed(),
        v.giftCards().breakage(),
        v.giftCards().liability());
  }

  public static Dtos.DeferredRevenueSettingsResponse toDto(Domain.DeferredRevenueSettings s) {
    return s == null
        ? null
        : new Dtos.DeferredRevenueSettingsResponse(
            s.currency(),
            s.pointValue(),
            s.pointsBreakagePct(),
            s.giftCardBreakagePct(),
            s.reason(),
            s.setBy(),
            s.setAt());
  }
}
