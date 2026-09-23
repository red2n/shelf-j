package com.storeql.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.SaleLine;
import com.storeql.reporting.service.ReportingService;
import java.math.BigDecimal;
import java.util.List;
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
    List<SaleLine> lines;
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
        String currency,
        List<SaleLine> lines) {
      this.sales++;
      this.lines = lines;
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

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(ORDER, service.orderId);
    assertEquals(STORE, service.storeId);
    assertEquals("ONLINE", service.channel);
    assertEquals(CUSTOMER, service.customerId);
    assertEquals(new BigDecimal("100.00"), service.gross);
    assertEquals("GBP", service.currency);
  }

  /** Sales by category needs the sale line by line: each line's variant, quantity and money. */
  @Test
  void orderConfirmedCarriesItsLinesToTheProjection() {
    UUID v1 = Ids.newId();
    UUID v2 = Ids.newId();
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"customerId\":null,\"total\":5.50,\"currency\":\"GBP\","
            + "\"lines\":[{\"variantId\":\""
            + v1
            + "\",\"qty\":2,\"unitPrice\":2.00,\"lineTotal\":4.00},{\"variantId\":\""
            + v2
            + "\",\"qty\":1.500,\"lineTotal\":1.50}]}";

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(2, service.lines.size());
    assertEquals(v1, service.lines.get(0).variantId());
    assertEquals(new BigDecimal("2"), service.lines.get(0).qty());
    assertEquals(new BigDecimal("2.00"), service.lines.get(0).unitPrice());
    assertEquals(new BigDecimal("4.00"), service.lines.get(0).lineTotal());
    assertEquals(v2, service.lines.get(1).variantId());
    assertNull(service.lines.get(1).unitPrice(), "a line priced off-platform has no unit price");
    assertEquals(new BigDecimal("1.50"), service.lines.get(1).lineTotal());
  }

  /** An event minted before lines existed is still a sale, with nothing to say by category. */
  @Test
  void anOlderOrderConfirmedWithoutLinesIsASaleWithNone() {
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

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(List.of(), service.lines);
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

    dispatcher.dispatch("storeql.order.order-confirmed", json);

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

    dispatcher.dispatch("storeql.payment.payment-refunded", json);

    assertEquals(1, service.refunds);
    assertEquals(EVENT, service.refundEventId);
    assertEquals(new BigDecimal("25.00"), service.refundAmount);
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    dispatcher.dispatch("storeql.order.order-confirmed", "{not valid json");

    assertEquals(0, service.sales);
    assertEquals(0, service.refunds);
  }
}
