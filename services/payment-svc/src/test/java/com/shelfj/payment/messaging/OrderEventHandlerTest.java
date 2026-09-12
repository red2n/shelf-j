package com.shelfj.payment.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shelfj.ids.Ids;
import com.shelfj.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OrderEventHandler drives the automatic-refund path. OrderReturned refunds only for
 * ORIGINAL-tender returns; OrderCancelled refunds whatever is still captured (null amount);
 * other/malformed events are skipped without throwing so the consumer loop acks them. payment-svc
 * has no mocking framework on the test classpath, so a capturing subclass stands in for {@link
 * PaymentService}.
 */
class OrderEventHandlerTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();

  private static final class CapturingPaymentService extends PaymentService {
    int calls;
    UUID eventId;
    String consumer;
    UUID tenantId;
    UUID orderId;
    BigDecimal amount;

    @Override
    public void refundForOrderEvent(
        UUID eventId,
        String consumer,
        UUID tenantId,
        UUID orderId,
        BigDecimal requestedAmount,
        String reason) {
      this.calls++;
      this.eventId = eventId;
      this.consumer = consumer;
      this.tenantId = tenantId;
      this.orderId = orderId;
      this.amount = requestedAmount;
    }
  }

  private CapturingPaymentService service;
  private OrderEventHandler handler;

  @BeforeEach
  void setUp() {
    service = new CapturingPaymentService();
    handler = new OrderEventHandler();
    handler.service = service;
  }

  private static String returned(String refundMethod, String refundAmount) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"OrderReturned\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"returnId\":\""
        + Ids.newId()
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"refundAmount\":"
        + refundAmount
        + ",\"refundMethod\":\""
        + refundMethod
        + "\",\"currency\":\"GBP\",\"items\":[]}";
  }

  @Test
  void originalTenderReturnRefundsTheReturnAmount() {
    handler.handle(returned("ORIGINAL", "25.00"));

    assertEquals(1, service.calls);
    assertEquals(EVENT, service.eventId);
    assertEquals(OrderEventHandler.CONSUMER_NAME, service.consumer);
    assertEquals(TENANT, service.tenantId);
    assertEquals(ORDER, service.orderId);
    assertEquals(new BigDecimal("25.00"), service.amount);
  }

  @Test
  void storeCreditReturnDoesNotReversePayment() {
    handler.handle(returned("STORE_CREDIT", "25.00"));

    assertEquals(0, service.calls);
  }

  @Test
  void cancelRefundsAllRemainingCaptured() {
    String cancelled =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderCancelled\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"reason\":\"changed mind\"}";

    handler.handle(cancelled);

    assertEquals(1, service.calls);
    assertEquals(EVENT, service.eventId);
    // null requestedAmount => "refund whatever is still captured".
    assertNull(service.amount);
  }

  @Test
  void unrelatedEventIsIgnored() {
    handler.handle(
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\"}");

    assertEquals(0, service.calls);
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    handler.handle("{not valid json");

    assertEquals(0, service.calls);
  }
}
