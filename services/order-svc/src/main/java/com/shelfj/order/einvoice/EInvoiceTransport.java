package com.shelfj.order.einvoice;

import java.util.Set;
import java.util.UUID;

/**
 * A way of sending an e-invoice over a network: the seam behind 07.13 and 18.9. A provider serves
 * one or more networks and says whether this deployment can use it; the transport service decides
 * what to send and when, records every answer, and tries again only when the network could not be
 * reached, never when it refused.
 */
public interface EInvoiceTransport {

  /** The networks this provider sends on, as {@code EInvoiceTransports.NETWORK_*} names them. */
  Set<String> networks();

  /** The provider's name, as the settings record it: {@code SIMULATED}, {@code ACCESS_POINT} … */
  String name();

  /**
   * Whether this deployment can use the provider: a real one needs credentials, the simulated one
   * nothing.
   */
  default boolean isConfigured() {
    return true;
  }

  /** What the provider needs from the deployment, for the settings screen. */
  default String configuration() {
    return "nothing";
  }

  /**
   * Whether the provider takes the business's own credentials — a portal user and password, an
   * authorisation token — which the settings keep sealed and never show again.
   */
  default boolean needsSecret() {
    return false;
  }

  /**
   * A document to send.
   *
   * @param documentKind {@code Invoice} or {@code CreditNote}
   * @param number the document's number, for the network's records
   * @param sender the business's electronic address, {@code scheme:identifier}, when it has one
   * @param receiver the buyer's, when the network addresses documents
   * @param ubl the document as issued, the UBL text
   * @param irpJson India's INV-01, when the business is Indian and the portal would take it
   * @param sellerVatId the business's VAT number — its GSTIN in India, which the portal signs in by
   * @param providerAccount the business at the provider, as the settings name it
   * @param providerSecret the business's own credential at the provider, opened; null when none
   */
  record Outbound(
      UUID tenantId,
      UUID invoiceId,
      String documentKind,
      String number,
      String sender,
      String receiver,
      String ubl,
      String irpJson,
      String sellerVatId,
      String providerAccount,
      String providerSecret) {}

  /**
   * What the network said.
   *
   * @param state {@code ACCEPTED}, {@code REJECTED} or {@code PENDING} (taken, not yet answered)
   * @param detail the answer in words
   * @param response the answer as it came, JSON, for the record
   * @param reference the network's final reference for the document when it differs from the one it
   *     was taken under — a KSeF number for a session reference; null to keep the first
   */
  record Outcome(String state, String detail, String response, String reference) {

    public static Outcome accepted(String detail, String response) {
      return new Outcome(
          com.shelfj.order.domain.EInvoiceTransports.STATUS_ACCEPTED, detail, response, null);
    }

    public static Outcome rejected(String detail, String response) {
      return new Outcome(
          com.shelfj.order.domain.EInvoiceTransports.STATUS_REJECTED, detail, response, null);
    }

    public static Outcome pending(String detail, String response) {
      return new Outcome(
          com.shelfj.order.domain.EInvoiceTransports.STATUS_PENDING, detail, response, null);
    }
  }

  /**
   * A document handed to the network.
   *
   * @param providerRef the network's reference for it, to ask after it by
   */
  record Dispatch(String providerRef, Outcome outcome) {}

  /**
   * Hands a document to the network.
   *
   * @throws TransportException when the network could not be reached or answered with an error of
   *     its own; the document will be sent again later
   */
  Dispatch send(Outbound document);

  /**
   * Whether this provider carries e-reporting as well as invoices.
   *
   * <p>False by default, and that is the honest default: e-reporting is a French duty deposited
   * with a partner platform, so a Peppol access point, KSeF and India's portal do not take one. A
   * network that silently accepted a report it does not carry would leave a business believing it
   * had reported.
   */
  default boolean carriesReports() {
    return false;
  }

  /**
   * Deposits e-reporting data.
   *
   * <p>The {@code ubl} field carries the report, and {@code number} names the return and its
   * period. A provider that does not carry reports refuses here rather than pretending; the service
   * asks {@link #carriesReports()} first and never gets this far.
   *
   * @throws TransportException when the network could not be reached
   */
  default Dispatch sendReport(Outbound report) {
    throw new TransportException(name() + " does not carry e-reporting", null);
  }

  /**
   * Asks after a document the network took but has not answered for.
   *
   * @throws TransportException as {@link #send}
   */
  Outcome status(Outbound document, String providerRef);

  /** The network could not be reached, or failed on its side: try again later. */
  class TransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TransportException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
