package com.shelfj.customer.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shelfj.customer.service.CustomerService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OrderConfirmedHandler parses the {@code shelfj.order.order-confirmed} payload and delegates
 * loyalty accrual to {@link CustomerService}. These tests lock the parsing/guard behaviour: real
 * buyers accrue, guests and malformed events are skipped without throwing (so the consumer loop
 * acks them).
 */
@ExtendWith(MockitoExtension.class)
class OrderConfirmedHandlerTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();
  private static final UUID CUSTOMER = UUID.randomUUID();

  @Mock CustomerService service;

  private OrderConfirmedHandler handler;

  @BeforeEach
  void setUp() {
    handler = new OrderConfirmedHandler();
    handler.service = service;
  }

  private static String payload(String customerIdJson, String total) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"customerId\":"
        + customerIdJson
        + ",\"total\":"
        + total
        + ",\"currency\":\"GBP\"}";
  }

  @Test
  void realBuyerAccruesWithParsedFields() {
    handler.handle(payload("\"" + CUSTOMER + "\"", "40.00"));

    verify(service)
        .accrueLoyaltyFromOrder(
            eq(EVENT), eq(TENANT), eq(CUSTOMER), eq(ORDER), eq(new BigDecimal("40.00")));
  }

  @Test
  void guestOrderWithNullCustomerIsSkipped() {
    handler.handle(payload("null", "40.00"));

    verify(service, never()).accrueLoyaltyFromOrder(any(), any(), any(), any(), any());
  }

  @Test
  void missingCustomerFieldIsSkipped() {
    // A legacy OrderConfirmed (pre-enrichment) omits customerId entirely — treat as a guest, skip.
    String legacy =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\"}";

    handler.handle(legacy);

    verify(service, never()).accrueLoyaltyFromOrder(any(), any(), any(), any(), any());
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    handler.handle("{not valid json");

    verify(service, never()).accrueLoyaltyFromOrder(any(), any(), any(), any(), any());
  }
}
