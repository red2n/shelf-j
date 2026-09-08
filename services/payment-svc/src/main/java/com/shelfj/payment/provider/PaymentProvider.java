package com.shelfj.payment.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment service provider, behind one interface.
 *
 * <p>Everything the rest of payment-svc knows about taking money lives here. {@code PaymentService}
 * never names Stripe or Razorpay, so adding the second provider means adding a class and a config
 * value — not editing the checkout path. PRD §11 settles the v1 set as Razorpay (India) + Stripe;
 * {@link ManualPaymentProvider} is the third, and the default, because a stack with no PSP
 * credentials must still start and still sell.
 *
 * <p><b>Nothing here takes a card number.</b> Every implementation hands the customer to the
 * provider's own hosted flow and learns the outcome from a redirect and a webhook. That is what
 * keeps card data out of this service entirely, and it is the difference between a PCI-DSS scope
 * that is a paragraph and one that is a project.
 *
 * <p>Implementations are {@code @ApplicationScoped} CDI beans, selected by {@link
 * PaymentProviders#forName} on the configured name.
 */
public interface PaymentProvider {

  /**
   * @return the {@code Domain.PaymentIntent} provider constant this implementation serves, e.g.
   *     {@code "STRIPE"}
   */
  String name();

  /**
   * The header this provider signs its webhooks with — {@code Stripe-Signature} for Stripe, {@code
   * X-Razorpay-Signature} for Razorpay. Named by the provider rather than fixed by us, because the
   * provider chooses it and reading the wrong one means every delivery is rejected.
   *
   * @return the header name to pass to {@link #verifyWebhook}
   */
  String signatureHeaderName();

  /**
   * Asks the provider to authorise {@code amount} for an order.
   *
   * <p>Called once per intent, inside the request that creates it. Implementations must pass {@code
   * idempotencyKey} to the provider's own idempotency mechanism where it has one, so a retried
   * checkout cannot place two holds on the customer's card.
   *
   * @param request what to authorise, and for whom
   * @return the provider's reference plus the state it left the intent in
   * @throws ProviderException if the provider could not be reached or refused the request outright
   */
  Authorization authorize(AuthorizeRequest request);

  /**
   * Takes money previously authorised. Separate from {@link #authorize} because that split is the
   * point: an online order is authorised at checkout and captured when it is actually fulfilled, so
   * a cancelled order releases a hold rather than owing a refund.
   *
   * @param providerRef the provider's id for the intent, from {@link Authorization#providerRef()}
   * @param amount the amount to capture; must not exceed what was authorised
   * @param idempotencyKey replay guard for the capture call itself
   * @return the captured state
   * @throws ProviderException if the provider could not be reached or refused the capture
   */
  Capture capture(String providerRef, BigDecimal amount, String idempotencyKey);

  /**
   * Releases an authorisation without taking the money.
   *
   * @param providerRef the provider's id for the intent
   * @param idempotencyKey replay guard
   * @throws ProviderException if the provider could not be reached
   */
  void cancel(String providerRef, String idempotencyKey);

  /**
   * Verifies that a webhook body genuinely came from the provider, and parses it.
   *
   * <p>This is the security boundary of the whole feature. The webhook endpoint is necessarily
   * public — the provider has to reach it — so anything that skips or weakens this check lets
   * anyone on the internet mark any order paid by POSTing a plausible body. Implementations must
   * verify the provider's signature over the <em>raw</em> bytes, before any parsing.
   *
   * @param rawBody the exact bytes received, unparsed and unmodified
   * @param signatureHeader the provider's signature header value, or null if absent
   * @return the parsed event
   * @throws ProviderException if the signature is absent, malformed, or does not verify
   */
  WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader);

  /**
   * @param tenantId owning tenant
   * @param orderId the order being paid for
   * @param intentId this service's own intent id, sent to the provider as metadata so a webhook can
   *     be traced back even if the provider reference is lost
   * @param amount amount to authorise
   * @param currency ISO-4217 code, resolved from the tenant
   * @param returnUrl where the provider should send the customer after SCA
   * @param idempotencyKey replay guard
   */
  record AuthorizeRequest(
      UUID tenantId,
      UUID orderId,
      UUID intentId,
      BigDecimal amount,
      String currency,
      String returnUrl,
      String idempotencyKey) {}

  /**
   * @param providerRef the provider's id for the intent
   * @param status one of the {@code Domain.PaymentIntent} status constants
   * @param nextActionUrl where to send the customer for SCA, or null if none is required
   */
  record Authorization(String providerRef, String status, String nextActionUrl) {}

  /**
   * @param providerRef the provider's id for the intent
   * @param capturedAmount how much was actually taken
   * @param reference the provider's reference for the captured payment, recorded on the tender
   */
  record Capture(String providerRef, BigDecimal capturedAmount, String reference) {}

  /**
   * A verified provider webhook.
   *
   * @param providerEventId the provider's own event id, used to dedupe redelivery
   * @param type provider-specific event type, for logging and for the audit trail
   * @param providerRef the intent this event concerns
   * @param status the {@code Domain.PaymentIntent} status this event implies
   * @param capturedAmount amount captured, when the event reports one; null otherwise
   * @param failureCode provider failure code, when the event reports a failure
   * @param failureMessage human-readable failure reason, when the event reports one
   */
  record WebhookEvent(
      String providerEventId,
      String type,
      String providerRef,
      String status,
      BigDecimal capturedAmount,
      String failureCode,
      String failureMessage) {}

  /** A provider call failed. Mapped to 502/503 by the service, never to a 500. */
  class ProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    /**
     * @param message what went wrong
     * @param retryable whether trying again could plausibly succeed (a timeout, not a decline)
     * @param cause underlying failure, or null
     */
    public ProviderException(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }

    /**
     * @return whether retrying could plausibly succeed
     */
    public boolean retryable() {
      return retryable;
    }
  }
}
