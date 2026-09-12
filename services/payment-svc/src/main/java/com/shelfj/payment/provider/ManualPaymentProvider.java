package com.shelfj.payment.provider;

import com.shelfj.payment.domain.Domain.PaymentIntent;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The no-provider provider: authorises and captures in one step, taking no money and calling
 * nothing.
 *
 * <p>This is deliberately the default. A stack brought up with no PSP credentials — every developer
 * machine, every CI run, every tenant who only takes cash at the till — must still start and still
 * complete a sale. Making a real provider mandatory would mean the checkout path could not be
 * exercised without secrets, which is how integrations end up untested.
 *
 * <p>It reproduces exactly what {@code POST /payments/online} did before payment intents existed:
 * record that money arrived, on the word of the caller. That was the defect — nothing authorised
 * the money — so this must never be the configured provider in production. {@code PaymentService}
 * logs a warning at startup when it is, and {@link #name()} is what a tenant's payment settings
 * report, so "we are not actually taking money" is visible rather than assumed.
 */
@ApplicationScoped
public class ManualPaymentProvider implements PaymentProvider {

  /**
   * {@inheritDoc}
   *
   * @return always {@code MANUAL}, which is what makes "no real money is moving" visible in a
   *     tenant's payment settings
   */
  @Override
  public String name() {
    return PaymentIntent.PROVIDER_MANUAL;
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code X-Provider-Signature}; nothing signs webhooks for this provider
   */
  @Override
  public String signatureHeaderName() {
    return "X-Provider-Signature";
  }

  /**
   * Authorises the full amount immediately, with no customer action.
   *
   * @param request what to authorise
   * @return an authorisation that is already {@code AUTHORIZED}, with a synthetic reference and no
   *     SCA step
   */
  @Override
  public Authorization authorize(AuthorizeRequest request) {
    // Synthetic but unique and traceable: it must satisfy the same unique index a real provider
    // reference does, and it should be obvious in the data which rows never touched a PSP.
    return new Authorization("manual_" + request.intentId(), PaymentIntent.STATUS_AUTHORIZED, null);
  }

  /**
   * @param providerRef the synthetic reference from {@link #authorize}
   * @param amount the amount to "capture"
   * @param idempotencyKey unused — there is no remote call to replay
   * @return a capture of exactly {@code amount}
   */
  @Override
  public Capture capture(String providerRef, BigDecimal amount, String idempotencyKey) {
    return new Capture(providerRef, amount, providerRef);
  }

  /**
   * No-op: there is no hold to release.
   *
   * @param providerRef ignored
   * @param idempotencyKey ignored
   */
  @Override
  public void cancel(String providerRef, String idempotencyKey) {
    // Nothing is held, so there is nothing to release.
  }

  /**
   * Always rejects. There is no provider to have sent a webhook, so a request claiming to be one is
   * either a misconfiguration or someone trying to mark an order paid from outside.
   *
   * @param rawBody ignored
   * @param signatureHeader ignored
   * @return never returns
   * @throws ProviderException always
   */
  @Override
  public WebhookEvent verifyWebhook(byte[] rawBody, String signatureHeader) {
    throw new ProviderException("the manual provider has no webhooks", false, null);
  }

  /**
   * @param intentId this service's intent id
   * @return the synthetic provider reference this implementation would assign
   */
  public static String referenceFor(UUID intentId) {
    return "manual_" + intentId;
  }
}
