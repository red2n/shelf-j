package com.shelfj.payment.service;

import com.shelfj.payment.client.OrderClient;
import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.domain.Domain.RefundTender;
import com.shelfj.payment.dto.Dtos.RecordRefundRequest;
import com.shelfj.payment.dto.Dtos.RecordTenderRequest;
import com.shelfj.payment.repo.PaymentRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class PaymentService {

  private static final Set<String> VALID_METHODS =
      Set.of(
          PaymentTender.METHOD_CASH,
          PaymentTender.METHOD_CARD,
          PaymentTender.METHOD_GIFT_CARD,
          PaymentTender.METHOD_VOUCHER);

  @Inject PaymentRepository repo;
  @Inject OrderClient orderClient;

  /** Staff-recorded tender (POS/back-office) — the caller's role is the trust boundary. */
  public PaymentTender recordTender(
      RecordTenderRequest req, TenantContext ctx, String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    return capture(req, tenantId, UUID.fromString(req.orderId()), idempotencyKey);
  }

  /**
   * Customer-initiated online tender — no staff role guards this endpoint, so the claim is verified
   * against order-svc (the data owner) before it's captured: the order must exist in the tenant,
   * must be an ONLINE order, must belong to the caller when the caller is an authenticated
   * customer, and the claimed amount must match the order total exactly.
   */
  public PaymentTender recordOnlinePayment(
      RecordTenderRequest req, TenantContext ctx, String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    UUID orderId = UUID.fromString(req.orderId());
    OrderClient.OrderInfo order = orderClient.getOrder(tenantId, orderId);

    if (!"ONLINE".equalsIgnoreCase(order.channel())) {
      throw ApiException.notFound("PAYMENT_ORDER_NOT_FOUND", "order " + orderId + " not found");
    }
    UUID callerId = ctx.userId();
    if (callerId != null
        && order.customerId() != null
        && !order.customerId().equals(callerId.toString())) {
      throw ApiException.notFound("PAYMENT_ORDER_NOT_FOUND", "order " + orderId + " not found");
    }
    if (order.total().compareTo(req.amount()) != 0) {
      throw ApiException.badRequest(
          "PAYMENT_AMOUNT_MISMATCH",
          "tendered amount " + req.amount() + " does not match order total " + order.total());
    }

    return capture(req, tenantId, orderId, idempotencyKey);
  }

  private PaymentTender capture(
      RecordTenderRequest req, UUID tenantId, UUID orderId, String idempotencyKey) {
    String method = req.method().toUpperCase(Locale.ROOT);
    if (!VALID_METHODS.contains(method))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD",
          "method must be one of CASH, CARD, GIFT_CARD, VOUCHER — got: " + req.method());

    UUID tenderId = UUID.randomUUID();
    UUID storeId = req.storeId() == null ? null : UUID.fromString(req.storeId());
    PaymentTender tender =
        new PaymentTender(
            tenderId,
            tenantId,
            orderId,
            req.amount(),
            method,
            req.reference(),
            idempotencyKey,
            PaymentTender.STATUS_CAPTURED,
            req.notes(),
            Instant.now(),
            storeId);

    return repo.createTender(
        tender, Events.paymentCaptured(tenantId, tenderId, orderId, req.amount()));
  }

  public PaymentTender getTender(UUID tenantId, UUID tenderId) {
    return repo.findTender(tenantId, tenderId)
        .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "payment tender not found"));
  }

  public List<PaymentTender> listTendersByOrder(UUID tenantId, UUID orderId) {
    return repo.findTendersByOrder(tenantId, orderId);
  }

  public RefundTender recordRefund(
      UUID tenantId, UUID orderId, RecordRefundRequest req, String idempotencyKey) {
    String method = req.method().toUpperCase(Locale.ROOT);
    if (!VALID_METHODS.contains(method))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD",
          "method must be one of CASH, CARD, GIFT_CARD, VOUCHER — got: " + req.method());

    UUID refundId = UUID.randomUUID();
    RefundTender refund =
        new RefundTender(
            refundId,
            tenantId,
            orderId,
            UUID.fromString(req.paymentId()),
            req.amount(),
            method,
            req.reference(),
            idempotencyKey,
            req.reason(),
            Instant.now());

    // Existence, order-match, and the cumulative refund cap are all enforced inside ONE
    // transaction with the payment row locked — checking them here first would be a TOCTOU race
    // letting two concurrent refunds together exceed the original payment.
    return repo.createRefundGuarded(refund, Events.paymentRefunded(tenantId, refundId, orderId));
  }

  public List<RefundTender> listRefundsByOrder(UUID tenantId, UUID orderId) {
    return repo.findRefundsByOrder(tenantId, orderId);
  }
}
