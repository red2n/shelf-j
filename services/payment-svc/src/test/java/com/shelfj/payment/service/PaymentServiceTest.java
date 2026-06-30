package com.shelfj.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.payment.client.OrderClient;
import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.dto.Dtos.RecordTenderRequest;
import com.shelfj.payment.repo.PaymentRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * No DI seam exists for TenantContext (its setter is package-private, populated only by the request
 * filter) or for OrderClient/PaymentRepository (concrete classes, no interface) — so, same as
 * CartServiceTest, these are driven with anonymous subclasses instead of a mocking framework.
 */
class PaymentServiceTest {

  private static TenantContext ctx(UUID tenantId, UUID userId) {
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        return tenantId;
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }

      @Override
      public UUID userId() {
        return userId;
      }
    };
  }

  private static OrderClient fakeOrderClient(OrderClient.OrderInfo info) {
    return new OrderClient() {
      @Override
      public OrderInfo getOrder(UUID tenantId, UUID orderId) {
        return info;
      }
    };
  }

  private static PaymentRepository capturingRepo() {
    return new PaymentRepository() {
      @Override
      public PaymentTender createTender(PaymentTender t, OutboxRow event) {
        return t;
      }
    };
  }

  private static RecordTenderRequest req(UUID orderId, BigDecimal amount) {
    return new RecordTenderRequest(orderId.toString(), amount, "CARD", null, null, null, null);
  }

  @Test
  void onlinePayment_rejectsAPosOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                null, "POS", new BigDecimal("10.00"), "OPEN", UUID.randomUUID().toString()));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("10.00")), ctx(UUID.randomUUID(), null), null));
    assertEquals("PAYMENT_ORDER_NOT_FOUND", ex.code());
  }

  @Test
  void onlinePayment_rejectsWhenCallerDoesNotOwnTheOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                ownerId.toString(),
                "ONLINE",
                new BigDecimal("10.00"),
                "OPEN",
                UUID.randomUUID().toString()));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("10.00")),
                    ctx(UUID.randomUUID(), UUID.randomUUID()),
                    null));
    assertEquals("PAYMENT_ORDER_NOT_FOUND", ex.code());
  }

  @Test
  void onlinePayment_rejectsAmountMismatch() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                null, "ONLINE", new BigDecimal("10.00"), "OPEN", UUID.randomUUID().toString()));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("1.00")), ctx(UUID.randomUUID(), null), null));
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.code());
  }

  @Test
  void onlinePayment_capturesAGuestOrderWhenChannelAndAmountMatch() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                null, "ONLINE", new BigDecimal("10.00"), "OPEN", UUID.randomUUID().toString()));
    svc.repo = capturingRepo();

    var tender =
        svc.recordOnlinePayment(
            req(orderId, new BigDecimal("10.00")), ctx(UUID.randomUUID(), null), "idem-1");
    assertEquals(orderId, tender.orderId());
    assertEquals(new BigDecimal("10.00"), tender.amount());
  }

  @Test
  void onlinePayment_capturesWhenCallerOwnsTheOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                customerId.toString(),
                "ONLINE",
                new BigDecimal("25.50"),
                "OPEN",
                UUID.randomUUID().toString()));
    svc.repo = capturingRepo();

    var tender =
        svc.recordOnlinePayment(
            req(orderId, new BigDecimal("25.50")), ctx(UUID.randomUUID(), customerId), null);
    assertEquals(new BigDecimal("25.50"), tender.amount());
  }

  @Test
  void staffTender_skipsOrderVerification() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.repo = capturingRepo();
    // orderClient deliberately left null — recordTender (the staff/POS path) must never touch it.

    var tender =
        svc.recordTender(req(orderId, new BigDecimal("99.99")), ctx(UUID.randomUUID(), null), null);
    assertEquals(orderId, tender.orderId());
  }
}
