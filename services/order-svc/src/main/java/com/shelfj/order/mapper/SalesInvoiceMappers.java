package com.shelfj.order.mapper;

import com.shelfj.einvoice.Invoice;
import com.shelfj.order.domain.EInvoiceTransports.Transmission;
import com.shelfj.order.domain.SalesInvoices;
import com.shelfj.order.domain.SalesInvoices.SalesInvoice;
import com.shelfj.order.dto.SalesInvoiceDtos.SalesInvoiceResponse;
import java.util.ArrayList;
import java.util.List;

/** Invoices and credit notes to their DTOs. The document itself is downloaded, never inlined. */
public final class SalesInvoiceMappers {

  private SalesInvoiceMappers() {}

  public static SalesInvoiceResponse toDto(SalesInvoice s) {
    return toDto(s, null);
  }

  /**
   * @param latest the newest attempt to send the document, or null
   */
  public static SalesInvoiceResponse toDto(SalesInvoice s, Transmission latest) {
    List<String> formats =
        new ArrayList<>(
            List.of(
                SalesInvoices.FORMAT_UBL, SalesInvoices.FORMAT_CII, SalesInvoices.FORMAT_FACTURX));
    if (s.irpPayload() != null) formats.add(SalesInvoices.FORMAT_IRP);
    return new SalesInvoiceResponse(
        s.id().toString(),
        s.orderId().toString(),
        str(s.returnId()),
        s.storeId().toString(),
        s.creditNote() ? "CREDIT_NOTE" : "INVOICE",
        s.typeCode(),
        s.fullNumber(),
        s.seriesCode(),
        s.period(),
        s.number(),
        s.issueDate().toString(),
        s.issuedAt().toString(),
        str(s.issuedBy()),
        s.customerId().toString(),
        s.buyerName(),
        s.buyerVatId(),
        s.currency(),
        s.netAmount(),
        s.vatAmount(),
        s.payableAmount(),
        str(s.precedingInvoiceId()),
        s.customizationId(),
        Invoice.PEPPOL_BIS_3.equals(s.customizationId()),
        List.copyOf(formats),
        SalesInvoices.problems(s.irpProblems()),
        EInvoiceTransportMappers.toDto(latest));
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }
}
