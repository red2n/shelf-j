package com.shelfj.order.dto;

import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Response DTOs for invoices and credit notes to business buyers (18.9). */
public final class SalesInvoiceDtos {

  private SalesInvoiceDtos() {}

  @Schema(
      name = "SalesInvoice",
      description = "An invoice or credit note to a business buyer, as it was issued.")
  public record SalesInvoiceResponse(
      String id,
      String orderId,
      @Schema(description = "The return a credit note credits; null for an invoice.")
          String returnId,
      String storeId,
      @Schema(enumeration = {"INVOICE", "CREDIT_NOTE"}) String kind,
      @Schema(description = "UNTDID 1001: 380 for an invoice, 381 for a credit note.")
          String typeCode,
      @Schema(description = "What the document prints, e.g. INV/2026/000001.") String fullNumber,
      @Schema(description = "INV for invoices, CRN for credit notes.") String seriesCode,
      @Schema(description = "The calendar year the numbering restarts in.") String period,
      long number,
      String issueDate,
      String issuedAt,
      String issuedBy,
      String customerId,
      String buyerName,
      String buyerVatId,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal payableAmount,
      @Schema(description = "The invoice a credit note credits.") String precedingInvoiceId,
      @Schema(description = "EN 16931 alone, or Peppol BIS Billing 3.0.") String customizationId,
      @Schema(description = "Whether both parties are on the Peppol network.") boolean peppol,
      @Schema(
              description =
                  "What the document can be downloaded as: UBL as issued, CII, FACTURX and, for"
                      + " an Indian business whose document passes the portal's checks, IRP.")
          List<String> formats,
      @Schema(
              description =
                  "India: what the Invoice Registration Portal would refuse, rule by rule. Empty"
                      + " elsewhere, and when it would accept the document.")
          List<String> irpProblems) {}
}
