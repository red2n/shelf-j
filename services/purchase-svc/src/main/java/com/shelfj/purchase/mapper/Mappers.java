package com.shelfj.purchase.mapper;

import com.shelfj.purchase.domain.Domain;
import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.IntercompanyInvoice;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.Domain.Supplier;
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
        po.closedReason());
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
