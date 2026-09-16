package com.shelfj.purchase.service;

import com.shelfj.einvoice.EInvoices;
import com.shelfj.einvoice.ElectronicAddress;
import com.shelfj.einvoice.Invoice;
import com.shelfj.purchase.client.TenantLookupClient;
import com.shelfj.purchase.domain.SupplierEInvoices;
import com.shelfj.purchase.domain.SupplierEInvoices.Document;
import com.shelfj.purchase.service.SupplierEInvoiceService.Receipt;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A network delivering a supplier's e-invoice into the business it names (07.13, the transport
 * seam).
 *
 * <p>An access point pushes what it received over AS4; France's approved platform what was
 * deposited for the business; the platform's own simulated provider what another business here
 * issued. None of them holds a token. The request presents the deployment's delivery key instead,
 * held against the one configured before a byte of the document is read; then the receiver is the
 * business the document itself names as its buyer — by electronic address, else by VAT identifier —
 * found platform-wide through tenant-svc, and never anything in the request. From there the
 * document goes through the intake exactly as an upload does, with the network as its channel and
 * the network's reference kept beside it, and the network learns the document's id and nothing of
 * the receiver's own.
 */
@ApplicationScoped
public class EInvoiceDeliveryService {

  private static final Logger LOG = System.getLogger(EInvoiceDeliveryService.class.getName());

  /** The role a delivery acts with inside the receiver: what a service-to-service call carries. */
  static final String DELIVERY_ROLE = "MANAGER";

  static final int MAX_REFERENCE = 200;

  @Inject SupplierEInvoiceService inbox;
  @Inject TenantLookupClient tenants;

  /** The key every network's provider presents; none means this deployment takes no deliveries. */
  @Inject
  @ConfigProperty(name = "shelfj.einvoice.inbound.key")
  Optional<String> deliveryKey;

  /** What a delivery came to: the document in the receiver's inbox, and whether it was there. */
  public record Delivery(Document document, boolean alreadyReceived) {}

  /**
   * Takes a delivery.
   *
   * @param ctx the request's context, which acts for the receiver from here on
   * @param network the network delivering, as the path names it
   * @param key the delivery key the request presents
   * @param reference the network's own reference for the delivery, or null
   * @param body the document
   * @param contentType its media type
   * @return the document as it now stands in the receiver's inbox
   * @throws ApiException {@code 503 PURCHASE_EINVOICE_DELIVERY_OFF} on a deployment with no key,
   *     {@code 401 PURCHASE_EINVOICE_KEY_REFUSED} for a missing or wrong one, {@code 400
   *     PURCHASE_EINVOICE_NETWORK_UNKNOWN}, {@code 400 PURCHASE_EINVOICE_REFERENCE_TOO_LONG}, what
   *     the intake refuses a document for ({@code 400}, {@code 413}, {@code 415}), {@code 422
   *     PURCHASE_EINVOICE_RECEIVER_UNNAMED} for a document naming no buyer address or VAT
   *     identifier, {@code 404 PURCHASE_EINVOICE_RECEIVER_UNKNOWN} when no business here holds what
   *     it names, {@code 409 PURCHASE_EINVOICE_RECEIVER_SHARED} when more than one does
   */
  public Delivery deliver(
      TenantContext ctx,
      String network,
      String key,
      String reference,
      byte[] body,
      String contentType) {
    requireKey(key);
    String channel = channelOf(network);
    String ref = referenceOf(reference);
    SupplierEInvoiceService.mediaType(contentType);
    SupplierEInvoiceService.requireDocument(body);
    EInvoices.Received received = SupplierEInvoiceService.read(body);
    Invoice.Party buyer = received.invoice().buyer();
    ElectronicAddress address =
        buyer == null ? null : ElectronicAddress.of(buyer.electronicAddress());
    String vat =
        buyer == null || buyer.vatId() == null || buyer.vatId().isBlank() ? null : buyer.vatId();
    if (address == null && vat == null) {
      throw ApiException.unprocessable(
          "PURCHASE_EINVOICE_RECEIVER_UNNAMED",
          "the document names its buyer by neither electronic address nor VAT identifier, so no"
              + " business can receive it");
    }
    UUID receiver = tenants.receiver(address, vat);
    ctx.assume(receiver, DELIVERY_ROLE);
    Receipt r = inbox.receive(ctx, body, contentType, channel, ref, received);
    LOG.log(
        Level.INFO,
        "{0} delivered {1} to {2}: {3}{4}",
        channel,
        ref == null ? "a document" : ref,
        receiver,
        r.document().id(),
        r.alreadyReceived() ? " (already received)" : "");
    return new Delivery(r.document(), r.alreadyReceived());
  }

  /** Holds the presented key against the deployment's, in constant time. */
  void requireKey(String presented) {
    String expected =
        deliveryKey == null ? null : deliveryKey.filter(k -> !k.isBlank()).orElse(null);
    if (expected == null) {
      throw new ApiException(
          503,
          "PURCHASE_EINVOICE_DELIVERY_OFF",
          "this deployment takes no deliveries: no delivery key is configured",
          List.of());
    }
    if (presented == null
        || !MessageDigest.isEqual(
            presented.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8))) {
      throw ApiException.unauthorized(
          "PURCHASE_EINVOICE_KEY_REFUSED", "the delivery key is missing or not this deployment's");
    }
  }

  /** The channel a network delivers as, or a refusal naming the networks that deliver in. */
  static String channelOf(String network) {
    String channel = network == null ? "" : network.strip().toUpperCase(Locale.ROOT);
    if (!SupplierEInvoices.NETWORKS.contains(channel)) {
      throw ApiException.badRequest(
          "PURCHASE_EINVOICE_NETWORK_UNKNOWN",
          "no network delivers in as '"
              + shown(network)
              + "': one of "
              + String.join(", ", SupplierEInvoices.NETWORKS)
              + " (KSeF is pulled by the buyer, and India's portal delivers nothing)");
    }
    return channel;
  }

  /** The reference as kept: trimmed, null when none, refused when longer than a reference is. */
  static String referenceOf(String reference) {
    if (reference == null || reference.isBlank()) return null;
    String ref = reference.strip();
    if (ref.length() > MAX_REFERENCE) {
      throw ApiException.badRequest(
          "PURCHASE_EINVOICE_REFERENCE_TOO_LONG",
          "a delivery reference is at most " + MAX_REFERENCE + " characters");
    }
    return ref;
  }

  /** A caller's path segment as it may be echoed: short, and nothing but word characters. */
  private static String shown(String network) {
    String safe = network == null ? "" : network.replaceAll("[^A-Za-z0-9_-]", "?");
    return safe.length() > 30 ? safe.substring(0, 30) + "…" : safe;
  }
}
