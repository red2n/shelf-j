package com.shelfj.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.shelfj.ids.Ids;
import com.shelfj.notification.service.NotifierTestSupport;
import com.shelfj.notification.service.OnceRepo;
import com.shelfj.notification.service.RecordingChannel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A chargeback reaches the business once, with the sum, the reason in words and the date it must
 * answer by (11.9); a payment with no store is the business's own alert; what is not a
 * dispute-opened event, or is malformed, tells nobody anything.
 */
class PaymentDisputeOpenedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  private RecordingChannel channel;
  private OnceRepo repo;
  private PaymentDisputeOpenedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    handler = new PaymentDisputeOpenedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, repo);
  }

  private static String event(String extra) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"PaymentDisputeOpened\",\"tenantId\":\""
        + TENANT
        + "\",\"disputeId\":\""
        + Ids.newId()
        + "\",\"orderId\":\""
        + Ids.newId()
        + "\",\"amount\":45.9900,\"feeAmount\":15,\"currency\":\"GBP\","
        + "\"reason\":\"PRODUCT_NOT_RECEIVED\",\"fundsWithdrawn\":true"
        + extra
        + "}";
  }

  @Test
  void theStoreIsToldOnceTheSumTheReasonAndTheDate() {
    String payload =
        event(",\"storeId\":\"" + STORE + "\",\"evidenceDueBy\":\"2026-09-27T23:59:00Z\"");

    handler.handle(payload);
    handler.handle(payload);

    assertEquals(1, channel.sends(), "a redelivered event must not alert the store twice");
    assertEquals(STORE.toString(), channel.recipient());
    assertEquals(TENANT, channel.lastTenantId);
    assertEquals("Chargeback: 45.99 GBP disputed", channel.subject());
    assertTrue(channel.body().contains("(product not received)"), channel.body());
    assertTrue(
        channel.body().contains("by 2026-09-27 23:59 UTC: after that it is lost."), channel.body());
    // A store's devices, not a person: nothing here for an erasure to find.
    assertNull(repo.subjectId);
  }

  @Test
  void aPaymentWithNoStoreIsTheBusinesssOwnAlertAndNoDateIsInvented() {
    handler.handle(event(""));

    assertEquals(1, channel.sends());
    assertEquals(TENANT.toString(), channel.recipient());
    assertTrue(channel.body().endsWith("as soon as you can."), channel.body());
  }

  @Test
  void whatIsNotADisputeOpenedOrIsMalformedTellsNobody() {
    handler.handle(event("").replace("PaymentDisputeOpened", "PaymentDisputeClosed"));
    handler.handle("{not json");
    handler.handle("{\"eventType\":\"PaymentDisputeOpened\",\"eventId\":\"not-a-uuid\"}");
    handler.handle(event("").replace("\"amount\":45.9900,", ""));
    handler.handle(event(",\"evidenceDueBy\":\"next tuesday\""));

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }
}
