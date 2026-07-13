package com.shelfj.payment.service;

import com.shelfj.service.OutboxRow;
import java.util.UUID;

final class Events {

  private Events() {}

  static OutboxRow paymentCaptured(
      UUID tenantId, UUID paymentId, UUID orderId, java.math.BigDecimal amount) {
    return new OutboxRow(
        "PaymentCaptured",
        "shelfj.payment.payment-captured",
        tenantId,
        paymentId,
        String.format(
            "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\"%s\",\"paymentId\":\"%s\","
                + "\"orderId\":\"%s\",\"amount\":%s}",
            tenantId, paymentId, orderId, amount.toPlainString()));
  }

  static OutboxRow paymentFailed(UUID tenantId, UUID paymentId, UUID orderId) {
    return new OutboxRow(
        "PaymentFailed",
        "shelfj.payment.payment-failed",
        tenantId,
        paymentId,
        String.format(
            "{\"eventType\":\"PaymentFailed\",\"tenantId\":\"%s\",\"paymentId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, paymentId, orderId));
  }

  /**
   * PaymentRefunded carries an {@code eventId} (consumer dedupe) and the refunded {@code amount} so
   * order-svc can accumulate it against the order total and flip the order to REFUNDED /
   * PARTIALLY_REFUNDED without a callback. Emitted by both the manual refund endpoint and the
   * automatic order-event refund path.
   */
  static OutboxRow paymentRefunded(
      UUID tenantId, UUID refundId, UUID orderId, java.math.BigDecimal amount) {
    return new OutboxRow(
        "PaymentRefunded",
        "shelfj.payment.payment-refunded",
        tenantId,
        refundId,
        String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\"%s\","
                + "\"refundId\":\"%s\",\"orderId\":\"%s\",\"amount\":%s}",
            UUID.randomUUID(), tenantId, refundId, orderId, amount.toPlainString()));
  }
}
