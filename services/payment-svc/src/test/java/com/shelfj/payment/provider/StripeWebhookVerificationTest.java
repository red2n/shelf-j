package com.shelfj.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.payment.domain.Domain.PaymentIntent;
import com.shelfj.payment.provider.PaymentProvider.ProviderException;
import com.shelfj.payment.provider.PaymentProvider.WebhookEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The webhook endpoint is public by necessity — Stripe has to reach it — so the signature check is
 * the only thing standing between the internet and "mark any order paid". These tests are that
 * check.
 */
class StripeWebhookVerificationTest {

  private static final String SECRET = "whsec_test_secret";

  private static StripePaymentProvider provider() {
    StripePaymentProvider p = new StripePaymentProvider();
    p.webhookSecret = SECRET;
    p.secretKey = "sk_test";
    p.apiBase = "https://api.stripe.example";
    return p;
  }

  private static String body(String type, String status, long amountReceived) {
    return "{\"id\":\"evt_1\",\"type\":\""
        + type
        + "\",\"data\":{\"object\":{\"id\":\"pi_1\",\"status\":\""
        + status
        + "\",\"currency\":\"gbp\",\"amount_received\":"
        + amountReceived
        + "}}}";
  }

  /** A correctly signed delivery, signed the way Stripe signs it: HMAC over "timestamp.body". */
  private static String signatureFor(String payload, long timestamp) {
    String signed = StripePaymentProvider.hmacSha256Hex(SECRET, timestamp + "." + payload);
    return "t=" + timestamp + ",v1=" + signed;
  }

  @Test
  void aCorrectlySignedEventVerifiesAndParses() {
    String payload = body("payment_intent.succeeded", "succeeded", 1050);
    long now = Instant.now().getEpochSecond();

    WebhookEvent event =
        provider()
            .verifyWebhook(payload.getBytes(StandardCharsets.UTF_8), signatureFor(payload, now));

    assertEquals("evt_1", event.providerEventId());
    assertEquals("pi_1", event.providerRef());
    assertEquals(PaymentIntent.STATUS_CAPTURED, event.status());
    // 1050 minor units of GBP is £10.50, not £1050 — getting this wrong overcharges by 100x.
    assertEquals(new BigDecimal("10.50"), event.capturedAmount());
  }

  @Test
  void aForgedSignatureIsRejected() {
    String payload = body("payment_intent.succeeded", "succeeded", 1050);
    long now = Instant.now().getEpochSecond();
    String forged = "t=" + now + ",v1=" + "0".repeat(64);

    assertThrows(
        ProviderException.class,
        () -> provider().verifyWebhook(payload.getBytes(StandardCharsets.UTF_8), forged));
  }

  /** The signature covers the body, so altering the body after signing must invalidate it. */
  @Test
  void aTamperedBodyIsRejected() {
    String signedPayload = body("payment_intent.succeeded", "succeeded", 1050);
    long now = Instant.now().getEpochSecond();
    String signature = signatureFor(signedPayload, now);
    String tampered = body("payment_intent.succeeded", "succeeded", 999999);

    assertThrows(
        ProviderException.class,
        () -> provider().verifyWebhook(tampered.getBytes(StandardCharsets.UTF_8), signature));
  }

  /** A genuine, correctly signed delivery replayed days later must not be accepted. */
  @Test
  void anOldSignatureIsRejectedEvenThoughItIsGenuine() {
    String payload = body("payment_intent.succeeded", "succeeded", 1050);
    long ancient = Instant.now().getEpochSecond() - 86_400;

    assertThrows(
        ProviderException.class,
        () ->
            provider()
                .verifyWebhook(
                    payload.getBytes(StandardCharsets.UTF_8), signatureFor(payload, ancient)));
  }

  @Test
  void aMissingOrMalformedHeaderIsRejected() {
    byte[] payload =
        body("payment_intent.succeeded", "succeeded", 1050).getBytes(StandardCharsets.UTF_8);

    assertThrows(ProviderException.class, () -> provider().verifyWebhook(payload, null));
    assertThrows(ProviderException.class, () -> provider().verifyWebhook(payload, ""));
    assertThrows(ProviderException.class, () -> provider().verifyWebhook(payload, "nonsense"));
    // A timestamp with no v1 signature, and a v1 with no timestamp, are both incomplete.
    assertThrows(ProviderException.class, () -> provider().verifyWebhook(payload, "t=123"));
    assertThrows(ProviderException.class, () -> provider().verifyWebhook(payload, "v1=abc"));
  }

  /**
   * With no secret configured there is nothing to verify against. Accepting would mean an
   * unconfigured deployment trusts anyone — the failure has to be closed, not open.
   */
  @Test
  void anUnconfiguredSecretRejectsEverything() {
    StripePaymentProvider unconfigured = provider();
    unconfigured.webhookSecret = "";
    String payload = body("payment_intent.succeeded", "succeeded", 1050);
    long now = Instant.now().getEpochSecond();

    assertThrows(
        ProviderException.class,
        () ->
            unconfigured.verifyWebhook(
                payload.getBytes(StandardCharsets.UTF_8), signatureFor(payload, now)));
  }

  // ── event → status mapping ────────────────────────────────────────────────

  /**
   * The case where the event type and the intent status disagree, and the type is right:
   * amount_capturable_updated carries status "requires_capture", which is Stripe for authorised.
   * Reading the status alone would leave a held payment looking like it still needed the customer.
   */
  @Test
  void amountCapturableUpdatedMeansAuthorized() {
    assertEquals(
        PaymentIntent.STATUS_AUTHORIZED,
        StripePaymentProvider.statusForEvent(
            "payment_intent.amount_capturable_updated", "requires_capture"));
  }

  @Test
  void theOtherEventTypesMapAsExpected() {
    assertEquals(
        PaymentIntent.STATUS_CAPTURED,
        StripePaymentProvider.statusForEvent("payment_intent.succeeded", "succeeded"));
    assertEquals(
        PaymentIntent.STATUS_FAILED,
        StripePaymentProvider.statusForEvent(
            "payment_intent.payment_failed", "requires_payment_method"));
    assertEquals(
        PaymentIntent.STATUS_CANCELLED,
        StripePaymentProvider.statusForEvent("payment_intent.canceled", "canceled"));
    // Anything unrecognised falls back to the intent's own status.
    assertEquals(
        PaymentIntent.STATUS_REQUIRES_ACTION,
        StripePaymentProvider.statusForEvent("payment_intent.created", "requires_action"));
  }

  @Test
  void aFailureEventCarriesItsCodeAndMessage() {
    String payload =
        "{\"id\":\"evt_2\",\"type\":\"payment_intent.payment_failed\",\"data\":{\"object\":"
            + "{\"id\":\"pi_2\",\"status\":\"requires_payment_method\",\"currency\":\"gbp\","
            + "\"last_payment_error\":{\"code\":\"card_declined\",\"message\":\"Your card was"
            + " declined.\"}}}}";

    WebhookEvent event = StripePaymentProvider.parseEvent(payload.getBytes(StandardCharsets.UTF_8));

    assertEquals(PaymentIntent.STATUS_FAILED, event.status());
    assertEquals("card_declined", event.failureCode());
    assertEquals("Your card was declined.", event.failureMessage());
    // No amount was captured, and null must mean that rather than zero.
    assertNull(event.capturedAmount());
  }

  // ── currency scaling ──────────────────────────────────────────────────────

  /**
   * Stripe quotes zero-decimal currencies in whole units. Treating JPY as hundredths would
   * authorise 100x the intended amount.
   */
  @Test
  void zeroDecimalCurrenciesAreNotScaled() {
    assertEquals(1000L, StripePaymentProvider.minorUnits(new BigDecimal("1000"), "JPY"));
    assertEquals(new BigDecimal("1000"), StripePaymentProvider.majorUnits(1000L, "JPY"));
  }

  @Test
  void ordinaryCurrenciesAreScaledByAHundred() {
    assertEquals(1050L, StripePaymentProvider.minorUnits(new BigDecimal("10.50"), "GBP"));
    assertEquals(new BigDecimal("10.50"), StripePaymentProvider.majorUnits(1050L, "GBP"));
  }
}
