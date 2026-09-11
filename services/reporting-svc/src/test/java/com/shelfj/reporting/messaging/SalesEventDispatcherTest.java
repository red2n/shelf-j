package com.shelfj.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.shelfj.ids.Ids;
import com.shelfj.reporting.service.ReportingService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SalesEventDispatcher routes OrderConfirmed → recordSale and PaymentRefunded → applySalesRefund,
 * and skips malformed/unknown events without throwing (so the consumer loop acks them).
 * reporting-svc has no mocking framework, so a capturing subclass stands in for {@link
 * ReportingService}.
 */
class SalesEventDispatcherTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID EVENT = Ids.newId();

  private static final class CapturingService extends ReportingService {
    int sales;
    int refunds;
    UUID orderId;
    UUID storeId;
    String channel;
    UUID customerId;
    BigDecimal gross;
    String currency;
    UUID refundEventId;
    BigDecimal refundAmount;

    @Override
    public void recordSale(
        UUID tenantId,
        UUID orderId,
        UUID storeId,
        String channel,
        UUID customerId,
        BigDecimal gross,
        String currency) {
      this.sales++;
      this.orderId = orderId;
      this.storeId = storeId;
      this.channel = channel;
      this.customerId = customerId;
      this.gross = gross;
      this.currency = currency;
    }

    @Override
    public void applySalesRefund(
        UUID eventId, String consumer, UUID tenantId, UUID orderId, BigDecimal amount) {
      this.refunds++;
      this.refundEventId = eventId;
      this.refundAmount = amount;
    }
  }

  private CapturingService service;
  private SalesEventDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    service = new CapturingService();
    dispatcher = new SalesEventDispatcher();
    dispatcher.service = service;
  }

  @Test
  void orderConfirmedRecordsASale() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"ONLINE\",\"customerId\":\""
            + CUSTOMER
            + "\",\"total\":100.00,\"currency\":\"GBP\"}";

    dispatcher.dispatch("shelfj.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(ORDER, service.orderId);
    assertEquals(STORE, service.storeId);
    assertEquals("ONLINE", service.channel);
    assertEquals(CUSTOMER, service.customerId);
    assertEquals(new BigDecimal("100.00"), service.gross);
    assertEquals("GBP", service.currency);
  }

  @Test
  void guestOrderConfirmedRecordsASaleWithNullCustomer() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"customerId\":null,\"total\":12.50,\"currency\":\"GBP\"}";

    dispatcher.dispatch("shelfj.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertNull(service.customerId);
  }

  @Test
  void paymentRefundedAppliesARefund() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\""
            + TENANT
            + "\",\"refundId\":\""
            + Ids.newId()
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"amount\":25.00}";

    dispatcher.dispatch("shelfj.payment.payment-refunded", json);

    assertEquals(1, service.refunds);
    assertEquals(EVENT, service.refundEventId);
    assertEquals(new BigDecimal("25.00"), service.refundAmount);
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    dispatcher.dispatch("shelfj.order.order-confirmed", "{not valid json");

    assertEquals(0, service.sales);
    assertEquals(0, service.refunds);
  }
}
