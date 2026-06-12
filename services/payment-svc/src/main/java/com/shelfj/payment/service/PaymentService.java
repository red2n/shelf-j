package com.shelfj.payment.service;

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

  public PaymentTender recordTender(
      RecordTenderRequest req, TenantContext ctx, String idempotencyKey) {
    String method = req.method().toUpperCase(Locale.ROOT);
    if (!VALID_METHODS.contains(method))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD",
          "method must be one of CASH, CARD, GIFT_CARD, VOUCHER — got: " + req.method());

    UUID tenantId = ctx.requireTenantId();
    UUID orderId = UUID.fromString(req.orderId());
    UUID tenderId = UUID.randomUUID();

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
            Instant.now());

    return repo.createTender(tender, Events.paymentCaptured(tenantId, tenderId, orderId));
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
    PaymentTender payment =
        repo.findTender(tenantId, UUID.fromString(req.paymentId()))
            .orElseThrow(
                () -> ApiException.notFound("PAYMENT_NOT_FOUND", "payment tender not found"));

    if (!payment.orderId().equals(orderId))
      throw ApiException.conflict(
          "PAYMENT_ORDER_MISMATCH", "payment does not belong to this order");

    String method = req.method().toUpperCase(Locale.ROOT);
    if (!VALID_METHODS.contains(method))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD",
          "method must be one of CASH, CARD, GIFT_CARD, VOUCHER — got: " + req.method());

    // Cumulative guard: existing refunds + this refund must not exceed original payment.
    java.math.BigDecimal alreadyRefunded = repo.sumRefunds(tenantId, payment.id());
    if (alreadyRefunded.add(req.amount()).compareTo(payment.amount()) > 0)
      throw ApiException.conflict(
          "REFUND_EXCEEDS_PAYMENT",
          "total refunds would exceed original payment of " + payment.amount());

    UUID refundId = UUID.randomUUID();
    RefundTender refund =
        new RefundTender(
            refundId,
            tenantId,
            orderId,
            payment.id(),
            req.amount(),
            method,
            req.reference(),
            idempotencyKey,
            req.reason(),
            Instant.now());

    return repo.createRefund(refund, Events.paymentRefunded(tenantId, refundId, orderId));
  }

  public List<RefundTender> listRefundsByOrder(UUID tenantId, UUID orderId) {
    return repo.findRefundsByOrder(tenantId, orderId);
  }
}
