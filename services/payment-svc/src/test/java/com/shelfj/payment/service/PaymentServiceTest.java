package com.shelfj.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.ids.Ids;
import com.shelfj.payment.client.OrderClient;
import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.domain.Domain.RefundTender;
import com.shelfj.payment.dto.Dtos.RecordTenderRequest;
import com.shelfj.payment.repo.PaymentRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

  private static TenantContext staffCtx(UUID tenantId) {
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
        return Ids.newId();
      }

      @Override
      public boolean hasRole(String role) {
        return "CASHIER".equals(role);
      }
    };
  }

  /**
   * Wires one fake order into both places that consult order-svc: the guard (which the
   * online-payment path now delegates its checks to, shared with payment intents) and the client
   * directly (which the object-level read checks still use). One order, both readers, so a test
   * cannot accidentally exercise a half-wired service.
   */
  private static void wireOrder(PaymentService svc, OrderClient.OrderInfo info) {
    OrderClient client = fakeOrderClient(info);
    svc.orderClient = client;
    OrderPaymentGuard guard = new OrderPaymentGuard();
    guard.orderClient = client;
    svc.guard = guard;
  }

  private static OrderClient fakeOrderClient(OrderClient.OrderInfo info) {
    return new OrderClient() {
      @Override
      public OrderInfo getOrder(UUID tenantId, UUID orderId) {
        return info;
      }
    };
  }

  private static PaymentTender tender(UUID id, UUID tenantId, UUID orderId) {
    return new PaymentTender(
        id,
        tenantId,
        orderId,
        new BigDecimal("10.00"),
        "CARD",
        null,
        null,
        "CAPTURED",
        null,
        Instant.now(),
        null);
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
    UUID orderId = Ids.newId();
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            null, "POS", new BigDecimal("10.00"), "PENDING", Ids.newId().toString(), "GBP"));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("10.00")), ctx(Ids.newId(), null), null));
    assertEquals("PAYMENT_ORDER_NOT_FOUND", ex.code());
  }

  @Test
  void onlinePayment_rejectsWhenCallerDoesNotOwnTheOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    UUID ownerId = Ids.newId();
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            ownerId.toString(),
            "ONLINE",
            new BigDecimal("10.00"),
            "PENDING",
            Ids.newId().toString(),
            "GBP"));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("10.00")), ctx(Ids.newId(), Ids.newId()), null));
    assertEquals("PAYMENT_ORDER_NOT_FOUND", ex.code());
  }

  @Test
  void onlinePayment_rejectsAnAlreadyConfirmedOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            null, "ONLINE", new BigDecimal("10.00"), "CONFIRMED", Ids.newId().toString(), "GBP"));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("10.00")), ctx(Ids.newId(), null), null));
    assertEquals("PAYMENT_ORDER_NOT_PAYABLE", ex.code());
  }

  @Test
  void onlinePayment_rejectsAmountMismatch() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            null, "ONLINE", new BigDecimal("10.00"), "PENDING", Ids.newId().toString(), "GBP"));

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                svc.recordOnlinePayment(
                    req(orderId, new BigDecimal("1.00")), ctx(Ids.newId(), null), null));
    assertEquals("PAYMENT_AMOUNT_MISMATCH", ex.code());
  }

  @Test
  void onlinePayment_capturesAGuestOrderWhenChannelAndAmountMatch() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            null, "ONLINE", new BigDecimal("10.00"), "PENDING", Ids.newId().toString(), "GBP"));
    svc.repo = capturingRepo();
    svc.storeClient = permissiveStoreClient();

    var tender =
        svc.recordOnlinePayment(
            req(orderId, new BigDecimal("10.00")), ctx(Ids.newId(), null), "idem-1");
    assertEquals(orderId, tender.orderId());
    assertEquals(new BigDecimal("10.00"), tender.amount());
  }

  @Test
  void onlinePayment_capturesWhenCallerOwnsTheOrder() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    UUID customerId = Ids.newId();
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            customerId.toString(),
            "ONLINE",
            new BigDecimal("25.50"),
            "PENDING",
            Ids.newId().toString(),
            "GBP"));
    svc.repo = capturingRepo();
    svc.storeClient = permissiveStoreClient();

    var tender =
        svc.recordOnlinePayment(
            req(orderId, new BigDecimal("25.50")), ctx(Ids.newId(), customerId), null);
    assertEquals(new BigDecimal("25.50"), tender.amount());
  }

  @Test
  void staffTender_skipsOrderVerification() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    svc.repo = capturingRepo();
    // orderClient deliberately left null — recordTender (the staff/POS path) must never touch it.

    var tender =
        svc.recordTender(req(orderId, new BigDecimal("99.99")), ctx(Ids.newId(), null), null);
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
    UUID orderId = Ids.newId();
    UUID customerId = Ids.newId();

    var tender =
        svc.recordTender(
            storeCreditReq(orderId, customerId, "15.00"), ctx(Ids.newId(), null), "k1");

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
                    storeCreditReq(Ids.newId(), null, "15.00"), ctx(Ids.newId(), null), null));
    assertEquals("PAYMENT_CUSTOMER_REQUIRED", ex.code());
  }

  @Test
  void storeCreditTender_isIdempotentWhenTenderAlreadyExists() {
    PaymentService svc = new PaymentService();
    UUID orderId = Ids.newId();
    UUID customerId = Ids.newId();
    var existing =
        new PaymentTender(
            Ids.newId(),
            Ids.newId(),
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
            storeCreditReq(orderId, customerId, "15.00"), ctx(Ids.newId(), null), null);

    assertEquals(existing.id(), tender.id());
    assertEquals(0, cust.redeems, "a replayed store-credit tender must not redeem again");
  }

  // ── F8-shape: object-level authorization on payment reads ──────────────────────

  @Test
  void getTender_ownerCanReadAPaymentOnTheirOwnOrder() {
    PaymentService svc = new PaymentService();
    UUID tenantId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID customerId = Ids.newId();
    UUID tenderId = Ids.newId();
    svc.repo = findableRepo(tender(tenderId, tenantId, orderId));
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            customerId.toString(),
            "ONLINE",
            new BigDecimal("10.00"),
            "CONFIRMED",
            Ids.newId().toString(),
            "GBP"));

    var result = svc.getTender(tenantId, tenderId, ctx(tenantId, customerId));
    assertEquals(tenderId, result.id());
  }

  @Test
  void getTender_nonOwningCustomerGets404NotTheirOrder() {
    PaymentService svc = new PaymentService();
    UUID tenantId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID ownerId = Ids.newId();
    UUID tenderId = Ids.newId();
    svc.repo = findableRepo(tender(tenderId, tenantId, orderId));
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            ownerId.toString(),
            "ONLINE",
            new BigDecimal("10.00"),
            "CONFIRMED",
            Ids.newId().toString(),
            "GBP"));

    var ex =
        assertThrows(
            ApiException.class,
            () -> svc.getTender(tenantId, tenderId, ctx(tenantId, Ids.newId())));
    assertEquals("PAYMENT_NOT_FOUND", ex.code());
  }

  @Test
  void getTender_staffReadsAnyPaymentWithoutOrderVerification() {
    PaymentService svc = new PaymentService();
    UUID tenantId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID tenderId = Ids.newId();
    svc.repo = findableRepo(tender(tenderId, tenantId, orderId));
    // The guard is wired with a client that fails on contact, so this still proves what it always
    // did: a staff read short-circuits before the order lookup and must never reach order-svc.
    svc.guard = new OrderPaymentGuard();
    svc.guard.orderClient =
        new OrderClient() {
          @Override
          public OrderInfo getOrder(UUID t, UUID o) {
            throw new AssertionError("a staff read must not call order-svc");
          }
        };

    var result = svc.getTender(tenantId, tenderId, staffCtx(tenantId));
    assertEquals(tenderId, result.id());
  }

  /**
   * A caller with no principal used to be waved through as a service-to-service lookup. Nothing
   * reads payments service-to-service, so that branch served no caller — and a guest storefront
   * request carries a tenant with no principal too, so the shape was reachable from outside. It is
   * now treated like any other unidentified caller: ownership is resolved against the order, and
   * with no userId to match, the tender is not found.
   */
  @Test
  void getTender_aCallerWithNoPrincipalIsNotWavedThrough() {
    PaymentService svc = new PaymentService();
    UUID tenantId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID tenderId = Ids.newId();
    svc.repo = findableRepo(tender(tenderId, tenantId, orderId));
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            Ids.newId().toString(),
            "ONLINE",
            new BigDecimal("10.00"),
            "CONFIRMED",
            Ids.newId().toString(),
            "GBP"));

    ApiException e =
        assertThrows(
            ApiException.class, () -> svc.getTender(tenantId, tenderId, ctx(tenantId, null)));
    assertEquals(404, e.status());
  }

  @Test
  void listTendersByOrder_enforcesTheSameOwnershipCheck() {
    PaymentService svc = new PaymentService();
    UUID tenantId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID ownerId = Ids.newId();
    svc.repo = findableRepo(tender(Ids.newId(), tenantId, orderId));
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            ownerId.toString(),
            "ONLINE",
            new BigDecimal("10.00"),
            "CONFIRMED",
            Ids.newId().toString(),
            "GBP"));

    assertEquals(1, svc.listTendersByOrder(tenantId, orderId, ctx(tenantId, ownerId)).size());
    var ex =
        assertThrows(
            ApiException.class,
            () -> svc.listTendersByOrder(tenantId, orderId, ctx(tenantId, Ids.newId())));
    assertEquals("PAYMENT_NOT_FOUND", ex.code());
  }

  @Test
  void listRefundsByOrder_enforcesTheSameOwnershipCheck() {
    PaymentService svc = new PaymentService();
    UUID tenantId = Ids.newId();
    UUID orderId = Ids.newId();
    UUID ownerId = Ids.newId();
    var refund =
        new RefundTender(
            Ids.newId(),
            tenantId,
            orderId,
            Ids.newId(),
            new BigDecimal("5.00"),
            "CARD",
            null,
            null,
            "return",
            Instant.now());
    svc.repo = findableRefundsRepo(refund);
    wireOrder(
        svc,
        new OrderClient.OrderInfo(
            ownerId.toString(),
            "ONLINE",
            new BigDecimal("10.00"),
            "CONFIRMED",
            Ids.newId().toString(),
            "GBP"));

    assertEquals(1, svc.listRefundsByOrder(tenantId, orderId, ctx(tenantId, ownerId)).size());
    var ex =
        assertThrows(
            ApiException.class,
            () -> svc.listRefundsByOrder(tenantId, orderId, ctx(tenantId, Ids.newId())));
    assertEquals("PAYMENT_NOT_FOUND", ex.code());
  }

  private static PaymentRepository findableRepo(PaymentTender tender) {
    return new PaymentRepository() {
      @Override
      public Optional<PaymentTender> findTender(UUID tenantId, UUID tenderId) {
        return Optional.of(tender);
      }

      @Override
      public List<PaymentTender> findTendersByOrder(UUID tenantId, UUID orderId) {
        return List.of(tender);
      }
    };
  }

  private static PaymentRepository findableRefundsRepo(RefundTender refund) {
    return new PaymentRepository() {
      @Override
      public List<RefundTender> findRefundsByOrder(UUID tenantId, UUID orderId) {
        return List.of(refund);
      }
    };
  }
}
