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
        s.updatedAt());
  }

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
        rows);
  }

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

  public static GoodsReceiptResponse toDto(GoodsReceipt gr, List<GoodsReceiptLine> lines) {
    return new GoodsReceiptResponse(
        gr.id(),
        gr.tenantId(),
        gr.poId(),
        gr.storeId(),
        gr.receivedAt(),
        lines.stream().map(Mappers::toDto).toList());
  }

  public static GoodsReceiptLineResponse toDto(GoodsReceiptLine l) {
    return new GoodsReceiptLineResponse(l.id(), l.variantId(), l.qtyReceived(), l.createdAt());
  }

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

  public static NominalLedgerEntryResponse toDto(NominalLedgerEntry e) {
    return new NominalLedgerEntryResponse(
        e.id(),
        e.tenantId(),
        e.entryDate(),
        e.nominalCode(),
        e.nominalName(),
        e.debit(),
        e.credit(),
        e.description(),
        e.sourceRef(),
        e.createdAt());
  }

  public static PurchaseOrderLineProgressResponse toDto(Domain.PurchaseOrderLineProgress p) {
    return new PurchaseOrderLineProgressResponse(
        p.variantId(), p.qtyOrdered(), p.qtyReceived(), p.qtyOutstanding());
  }
}
