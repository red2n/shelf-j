package com.shelfj.payment.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shelfj.ids.Ids;
import com.shelfj.payment.domain.Domain.PaymentIntent;
import com.shelfj.payment.provider.PaymentProvider;
import com.shelfj.payment.provider.PaymentProviders;
import com.shelfj.payment.repo.PaymentIntentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The order in which a provider webhook is deduped and applied.
 *
 * <p>The dedupe row used to be committed, in its own transaction, <em>before</em> the effect was
 * applied. A failure in {@code writeCapture} then left that row standing and the capture never made
 * — so the provider's redelivery, which is the one mechanism designed to recover exactly this, was
 * swallowed as "already applied". The money was captured at Stripe and recorded nowhere, silently
 * and permanently.
 *
 * <p>Recording it afterwards is safe because every branch is idempotent, which golden rule #7
 * requires of consumers anyway. These tests pin the ordering rather than the idempotency, because
 * the ordering is the part that was wrong and the part a future edit would most easily undo.
 */
@ExtendWith(MockitoExtension.class)
class WebhookDedupeOrderingTest {

  private static final String PROVIDER = "stripe";
  private static final String EVENT_ID = "evt_test_1";
  private static final String PROVIDER_REF = "pi_test_1";

  @Mock PaymentIntentRepository repo;
  @Mock PaymentProviders providers;
  @Mock PaymentProvider provider;
  @InjectMocks PaymentIntentService service;

  private final UnaryOperator<String> header = name -> "sig";

  @BeforeEach
  void wireProvider() {
    when(providers.forName(PROVIDER)).thenReturn(provider);
    when(provider.name()).thenReturn(PROVIDER);
    when(provider.signatureHeaderName()).thenReturn("Stripe-Signature");
  }

  private void deliver(String status) {
    when(provider.verifyWebhook(any(), anyString()))
        .thenReturn(
            new PaymentProvider.WebhookEvent(
                EVENT_ID, "type", PROVIDER_REF, status, new BigDecimal("10.00"), null, null));
    service.handleWebhook(PROVIDER, "{}".getBytes(), header);
  }

  private PaymentIntent authorizedIntent() {
    return new PaymentIntent(
        Ids.newId(), // id
        Ids.newId(), // tenantId
        Ids.newId(), // orderId
        Ids.newId(), // storeId
        PROVIDER,
        PROVIDER_REF,
        new BigDecimal("10.00"), // amount
        null, // capturedAmount
        "GBP",
        PaymentIntent.STATUS_AUTHORIZED,
        null, // nextActionUrl
        null, // failureCode
        null, // failureMessage
        null, // paymentId
        null, // idempotencyKey
        Instant.now(),
        Instant.now());
  }

  @Test
  @DisplayName("A failing capture leaves NO dedupe row, so the provider's redelivery still works")
  void aFailedEffectIsNotRecordedAsSeen() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF))
        .thenReturn(authorizedIntent());
    when(repo.captureGuarded(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("database went away mid-capture"));

    assertThrows(RuntimeException.class, () -> deliver(PaymentIntent.STATUS_CAPTURED));

    // The whole finding, in one line: had this been called, the money would be gone for good.
    verify(repo, never()).markWebhookSeenIfNew(anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("A successful capture IS recorded, so the next redelivery is skipped")
  void aSucceededEffectIsRecorded() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF))
        .thenReturn(authorizedIntent());

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, times(1)).captureGuarded(any(), any(), any(), any());
    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
  }

  @Test
  @DisplayName("An event already seen applies nothing at all")
  void alreadySeenIsSkipped() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(true);

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, never()).captureGuarded(any(), any(), any(), any());
    verify(repo, never()).findByProviderRefAcrossTenants(anyString(), anyString());
  }

  @Test
  @DisplayName("An event about an unknown intent is recorded, or it is reprocessed forever")
  void unknownIntentIsStillRecorded() {
    when(repo.hasSeenWebhook(PROVIDER, EVENT_ID)).thenReturn(false);
    when(repo.findByProviderRefAcrossTenants(PROVIDER, PROVIDER_REF)).thenReturn(null);

    deliver(PaymentIntent.STATUS_CAPTURED);

    verify(repo, times(1)).markWebhookSeenIfNew(PROVIDER, EVENT_ID, "type");
    assertThat(true, is(true));
  }
}
