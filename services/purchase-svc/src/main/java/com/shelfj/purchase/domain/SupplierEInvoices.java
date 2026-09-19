package com.shelfj.purchase.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A supplier e-invoice as purchase-svc keeps it (07.13): the document, its lines, the original. */
public final class SupplierEInvoices {

  public static final String CHANNEL_UPLOAD = "UPLOAD";

  /** Delivered by a Peppol access point. */
  public static final String CHANNEL_PEPPOL = "PEPPOL";

  /** Delivered by France's approved platform. */
  public static final String CHANNEL_FR_PDP = "FR_PDP";

  /** Delivered by the platform itself, standing in for a network (the simulated provider). */
  public static final String CHANNEL_SIMULATED = "SIMULATED";

  /** Fetched from KSeF by the buyer, because Poland's system delivers nothing (07.13). */
  public static final String CHANNEL_KSEF = "KSEF";

  /**
   * The networks that deliver in, as {@code POST /e-invoices/inbound/{network}} names them. KSeF is
   * not among them and has its own channel: a Polish buyer asks the ministry's system for its
   * invoices, and nothing is ever pushed to it. India's portal delivers nothing at all.
   */
  public static final List<String> NETWORKS =
      List.of(CHANNEL_PEPPOL, CHANNEL_FR_PDP, CHANNEL_SIMULATED);

  private SupplierEInvoices() {}

  /**
   * One received document: what it says, what was found wrong with it on arrival, and what it
   * became or is waiting for.
   */
  public record Document(
      UUID id,
      UUID tenantId,
      Instant receivedAt,
      UUID receivedBy,
      String channel,
      String deliveryRef,
      String contentType,
      String container,
      String syntax,
      String embeddedFilename,
      String sha256,
      String customizationId,
      String typeCode,
      String invoiceNumber,
      LocalDate issueDate,
      String currency,
      String sellerName,
      String sellerVatId,
      String sellerEndpoint,
      String buyerVatId,
      String buyerEndpoint,
      String orderReference,
      String precedingInvoice,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      BigDecimal payableAmount,
      String violationsJson,
      String status,
      String problem,
      UUID supplierId,
      UUID poId,
      UUID supplierInvoiceId,
      UUID vendorReturnId,
      Instant decidedAt,
      UUID decidedBy,
      String decisionReason,
      Instant updatedAt) {}

  /** One line as the supplier sent it, and the order line it was matched to. */
  public record Line(
      UUID id,
      UUID tenantId,
      UUID einvoiceId,
      int position,
      String lineId,
      String itemName,
      String sellersItemId,
      String buyersItemId,
      String standardItemId,
      String orderLineReference,
      BigDecimal quantity,
      String unitCode,
      BigDecimal netAmount,
      BigDecimal netPrice,
      String vatCategory,
      BigDecimal vatRate,
      UUID poLineId,
      UUID variantId,
      String matchedBy) {}

  /** The document exactly as it arrived. */
  public record Original(
      String contentType, String container, String invoiceNumber, byte[] bytes) {}
}
