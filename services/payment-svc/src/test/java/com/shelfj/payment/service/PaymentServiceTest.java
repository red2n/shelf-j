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

  /** Store settings unknown (empty) → per-store method enforcement is skipped (fail-open). */
  private static com.shelfj.payment.client.TenantStoreClient permissiveStoreClient() {
    return new com.shelfj.payment.client.TenantStoreClient() {
      @Override
      public java.util.Optional<java.util.Set<String>> enabledMethods(UUID tenantId, UUID storeId) {
        return java.util.Optional.empty();
      }
    };
  }

  private static RecordTenderRequest req(UUID orderId, BigDecimal amount) {
    return new RecordTenderRequest(
        orderId.toString(), amount, "CARD", null, null, null, null, null, null);
  }

  @Test
  void onlinePayment_rejectsAPosOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                null, "POS", new BigDecimal("10.00"), "PENDING", UUID.randomUUID().toString()));

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
                "PENDING",
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
  void onlinePayment_rejectsAnAlreadyConfirmedOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                null,
                "ONLINE",
                new BigDecimal("10.00"),
                "CONFIRMED",
                UUID.randomUUID().toString()));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("10.00")), ctx(UUID.randomUUID(), null), null));
    assertEquals("PAYMENT_ORDER_NOT_PAYABLE", ex.code());
  }

  @Test
  void onlinePayment_rejectsAmountMismatch() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    svc.orderClient =
        fakeOrderClient(
            new OrderClient.OrderInfo(
                null, "ONLINE", new BigDecimal("10.00"), "PENDING", UUID.randomUUID().toString()));

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
                null, "ONLINE", new BigDecimal("10.00"), "PENDING", UUID.randomUUID().toString()));
    svc.repo = capturingRepo();
    svc.storeClient = permissiveStoreClient();

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
                "PENDING",
                UUID.randomUUID().toString()));
    svc.repo = capturingRepo();
    svc.storeClient = permissiveStoreClient();

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

  // ── N3: store credit as tender ──────────────────────────────────────────────

  private static final class RecordingCustomerClient
      extends com.shelfj.payment.client.CustomerClient {
    int redeems;
    UUID customerId;
    BigDecimal amount;
    String currency;
    UUID orderId;

    @Override
    public void redeemStoreCredit(
        UUID tenantId, UUID customerId, BigDecimal amount, String currency, UUID orderId) {
      this.redeems++;
      this.customerId = customerId;
      this.amount = amount;
      this.currency = currency;
      this.orderId = orderId;
    }
  }

  /** Capturing repo with no pre-existing store-credit tender (first-time capture path). */
  private static PaymentRepository storeCreditRepo() {
    return new PaymentRepository() {
      @Override
      public PaymentTender createTender(PaymentTender t, OutboxRow event) {
        return t;
      }

      @Override
      public java.util.Optional<PaymentTender> findTenderByKey(UUID tenantId, String key) {
        return java.util.Optional.empty();
      }
    };
  }

  private static RecordTenderRequest storeCreditReq(UUID orderId, UUID customerId, String amount) {
    return new RecordTenderRequest(
        orderId.toString(),
        new BigDecimal(amount),
        "STORE_CREDIT",
        null,
        null,
        null,
        null,
        customerId == null ? null : customerId.toString(),
        "GBP");
  }

  @Test
  void storeCreditTender_redeemsThenRecordsTheTender() {
    PaymentService svc = new PaymentService();
    svc.repo = storeCreditRepo();
    var cust = new RecordingCustomerClient();
    svc.customerClient = cust;
    UUID orderId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();

    var tender =
        svc.recordTender(
            storeCreditReq(orderId, customerId, "15.00"), ctx(UUID.randomUUID(), null), "k1");

    assertEquals("STORE_CREDIT", tender.method());
    assertEquals(new BigDecimal("15.00"), tender.amount());
    assertEquals(1, cust.redeems);
    assertEquals(customerId, cust.customerId);
    assertEquals(orderId, cust.orderId);
    assertEquals(new BigDecimal("15.00"), cust.amount);
    assertEquals("GBP", cust.currency);
  }

  @Test
  void storeCreditTender_requiresCustomerId() {
    PaymentService svc = new PaymentService();
    svc.repo = storeCreditRepo();
    svc.customerClient = new RecordingCustomerClient();

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordTender(
                    storeCreditReq(UUID.randomUUID(), null, "15.00"),
                    ctx(UUID.randomUUID(), null),
                    null));
    assertEquals("PAYMENT_CUSTOMER_REQUIRED", ex.code());
  }

  @Test
  void storeCreditTender_isIdempotentWhenTenderAlreadyExists() {
    PaymentService svc = new PaymentService();
    UUID orderId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    var existing =
        new PaymentTender(
            UUID.randomUUID(),
            UUID.randomUUID(),
            orderId,
            new BigDecimal("15.00"),
            "STORE_CREDIT",
            null,
            "sc:" + orderId,
            "CAPTURED",
            null,
            java.time.Instant.now(),
            null);
    svc.repo =
        new PaymentRepository() {
          @Override
          public java.util.Optional<PaymentTender> findTenderByKey(UUID tenantId, String key) {
            return java.util.Optional.of(existing); // replay: the tender for this order exists
          }
        };
    var cust = new RecordingCustomerClient();
    svc.customerClient = cust;

    var tender =
        svc.recordTender(
            storeCreditReq(orderId, customerId, "15.00"), ctx(UUID.randomUUID(), null), null);

    assertEquals(existing.id(), tender.id());
    assertEquals(0, cust.redeems, "a replayed store-credit tender must not redeem again");
  }
}
