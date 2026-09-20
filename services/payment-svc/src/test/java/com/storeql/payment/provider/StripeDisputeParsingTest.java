package com.storeql.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.payment.provider.PaymentProvider.DisputeNotice;
import com.storeql.payment.provider.PaymentProvider.WebhookEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Stripe's {@code charge.dispute.*} events, read into the provider-neutral shape (11.9). */
class StripeDisputeParsingTest {

  private static WebhookEvent parse(String type, String status, String extra) {
    String body =
        "{\"id\":\"evt_1\",\"type\":\""
            + type
            + "\",\"data\":{\"object\":{\"id\":\"dp_1\",\"object\":\"dispute\",\"amount\":4599,"
            + "\"currency\":\"gbp\",\"charge\":\"ch_1\",\"payment_intent\":\"pi_1\",\"reason\":"
            + "\"product_not_received\",\"status\":\""
            + status
            + "\",\"network_reason_code\":\"13.1\",\"evidence_details\":{\"due_by\":1790000000}"
            + extra
            + "}}}";
    return StripePaymentProvider.parseEvent(body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aDisputeCreatedNamesThePaymentTheAmountTheReasonAndTheDate() {
    WebhookEvent e = parse("charge.dispute.created", "needs_response", "");
    assertEquals("evt_1", e.providerEventId());
    assertEquals("pi_1", e.providerRef(), "the intent the disputed charge belongs to");
    assertNull(e.status(), "not an event about the intent's own state");
    DisputeNotice d = e.dispute();
    assertNotNull(d);
    assertEquals("dp_1", d.disputeRef());
    assertEquals(DisputeNotice.PHASE_OPENED, d.phase());
    assertNull(d.outcome());
    assertEquals(0, new BigDecimal("45.99").compareTo(d.amount()), "minor units to major");
    assertEquals("GBP", d.currency());
    assertEquals("PRODUCT_NOT_RECEIVED", d.reason());
    assertEquals("13.1", d.networkReasonCode());
    assertEquals(Instant.ofEpochSecond(1790000000L), d.evidenceDueBy());
    assertEquals(0, BigDecimal.ZERO.compareTo(d.fee()), "no fee said yet");
  }

  @Test
  void fundsWithdrawnCarriesTheFeeStripeCharged() {
    WebhookEvent e =
        parse(
            "charge.dispute.funds_withdrawn",
            "needs_response",
            ",\"balance_transactions\":[{\"amount\":-4599,\"fee\":1500,\"net\":-6099},"
                + "{\"amount\":0,\"fee\":500,\"net\":-500}]");
    assertEquals(DisputeNotice.PHASE_FUNDS_WITHDRAWN, e.dispute().phase());
    assertEquals(0, new BigDecimal("20.00").compareTo(e.dispute().fee()), "the fees, summed");
  }

  @Test
  void aClosedDisputeSaysWhoWon() {
    assertEquals("LOST", parse("charge.dispute.closed", "lost", "").dispute().outcome());
    assertEquals("WON", parse("charge.dispute.closed", "won", "").dispute().outcome());
    assertEquals(
        "WON",
        parse("charge.dispute.closed", "warning_closed", "").dispute().outcome(),
        "an inquiry that never became a chargeback: the money never left");
    assertEquals(
        DisputeNotice.PHASE_CLOSED, parse("charge.dispute.closed", "won", "").dispute().phase());
    assertEquals(
        DisputeNotice.PHASE_UPDATED,
        parse("charge.dispute.updated", "under_review", "").dispute().phase());
    assertEquals(
        DisputeNotice.PHASE_FUNDS_REINSTATED,
        parse("charge.dispute.funds_reinstated", "won", "").dispute().phase());
  }

  @Test
  void stripesReasonsFallIntoTheCategoriesKept() {
    assertEquals("FRAUDULENT", StripePaymentProvider.disputeReason("fraudulent"));
    assertEquals("DUPLICATE", StripePaymentProvider.disputeReason("duplicate"));
    assertEquals(
        "CREDIT_NOT_PROCESSED", StripePaymentProvider.disputeReason("credit_not_processed"));
    assertEquals(
        "SUBSCRIPTION_CANCELLED", StripePaymentProvider.disputeReason("subscription_canceled"));
    assertEquals(
        "PRODUCT_UNACCEPTABLE", StripePaymentProvider.disputeReason("product_unacceptable"));
    assertEquals("UNRECOGNIZED", StripePaymentProvider.disputeReason("unrecognized"));
    assertEquals("GENERAL", StripePaymentProvider.disputeReason("bank_cannot_process"));
    assertEquals("GENERAL", StripePaymentProvider.disputeReason(""));
  }

  @Test
  void aDisputeWithNoAmountOrCurrencyIsRefusedNotGuessedAt() {
    byte[] noCurrency =
        "{\"id\":\"evt_2\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_2\",\"amount\":100}}}"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PaymentProvider.ProviderException.class,
        () -> StripePaymentProvider.parseEvent(noCurrency));
    byte[] noAmount =
        "{\"id\":\"evt_3\",\"type\":\"charge.dispute.created\",\"data\":{\"object\":{\"id\":\"dp_3\",\"currency\":\"eur\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PaymentProvider.ProviderException.class, () -> StripePaymentProvider.parseEvent(noAmount));
  }

  @Test
  void anEventAboutAnIntentIsStillReadAsOne() {
    byte[] body =
        "{\"id\":\"evt_4\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_9\",\"currency\":\"gbp\",\"status\":\"succeeded\",\"amount_received\":1000}}}"
            .getBytes(StandardCharsets.UTF_8);
    WebhookEvent e = StripePaymentProvider.parseEvent(body);
    assertNull(e.dispute());
    assertEquals("pi_9", e.providerRef());
    assertEquals("CAPTURED", e.status());
  }
}
