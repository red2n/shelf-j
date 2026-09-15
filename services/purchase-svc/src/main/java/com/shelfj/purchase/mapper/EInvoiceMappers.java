package com.shelfj.purchase.mapper;

import com.shelfj.purchase.domain.EInvoiceIntake;
import com.shelfj.purchase.domain.SupplierEInvoices.Document;
import com.shelfj.purchase.domain.SupplierEInvoices.Line;
import com.shelfj.purchase.dto.EInvoiceDtos.RuleViolationResponse;
import com.shelfj.purchase.dto.EInvoiceDtos.SupplierEInvoiceLineResponse;
import com.shelfj.purchase.dto.EInvoiceDtos.SupplierEInvoiceResponse;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Received supplier e-invoices to their wire form. */
public final class EInvoiceMappers {

  private EInvoiceMappers() {}

  public static SupplierEInvoiceResponse toDto(
      Document d, List<Line> lines, boolean alreadyReceived) {
    return new SupplierEInvoiceResponse(
        d.id(),
        d.receivedAt(),
        d.channel(),
        d.container(),
        d.syntax(),
        d.embeddedFilename(),
        d.typeCode(),
        d.typeCode() != null
            && com.shelfj.einvoice.Invoice.CREDIT_NOTE_TYPES.contains(d.typeCode()),
        d.invoiceNumber(),
        d.issueDate(),
        d.currency(),
        d.sellerName(),
        d.sellerVatId(),
        d.sellerEndpoint(),
        d.buyerVatId(),
        d.buyerEndpoint(),
        d.orderReference(),
        d.precedingInvoice(),
        d.netAmount(),
        d.vatAmount(),
        d.grossAmount(),
        d.payableAmount(),
        d.status(),
        EInvoiceIntake.OPEN.contains(d.status()) ? d.problem() : null,
        d.supplierId(),
        d.poId(),
        d.supplierInvoiceId(),
        d.vendorReturnId(),
        d.decidedAt(),
        d.decidedBy(),
        d.decisionReason(),
        alreadyReceived,
        violations(d.violationsJson()),
        lines.stream().map(EInvoiceMappers::toDto).toList());
  }

  static SupplierEInvoiceLineResponse toDto(Line l) {
    return new SupplierEInvoiceLineResponse(
        l.position(),
        l.lineId(),
        l.itemName(),
        l.sellersItemId(),
        l.buyersItemId(),
        l.standardItemId(),
        l.orderLineReference(),
        l.quantity(),
        l.unitCode(),
        l.netAmount(),
        l.netPrice(),
        l.vatCategory(),
        l.vatRate(),
        l.poLineId(),
        l.variantId(),
        l.matchedBy());
  }

  /** The violations as stored on arrival: a JSON array of rule, severity and message. */
  static List<RuleViolationResponse> violations(String json) {
    List<RuleViolationResponse> out = new ArrayList<>();
    if (json == null || json.isBlank()) return out;
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      for (JsonValue v : reader.readArray()) {
        JsonObject o = v.asJsonObject();
        out.add(
            new RuleViolationResponse(
                o.getString("rule", null),
                o.getString("severity", null),
                o.getString("message", null)));
      }
    }
    return out;
  }
}
