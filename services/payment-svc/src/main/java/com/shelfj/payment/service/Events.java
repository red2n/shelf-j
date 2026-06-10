package com.shelfj.payment.service;

import com.shelfj.service.OutboxRow;
import java.util.UUID;

final class Events {

  private Events() {}

  static OutboxRow paymentCaptured(UUID tenantId, UUID paymentId, UUID orderId) {
    return new OutboxRow(
        "PaymentCaptured",
        "shelfj.payment.payment-captured",
        tenantId,
        paymentId,
        String.format(
            "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\"%s\",\"paymentId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, paymentId, orderId));
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

  static OutboxRow paymentRefunded(UUID tenantId, UUID refundId, UUID orderId) {
    return new OutboxRow(
        "PaymentRefunded",
        "shelfj.payment.payment-refunded",
        tenantId,
        refundId,
        String.format(
            "{\"eventType\":\"PaymentRefunded\",\"tenantId\":\"%s\",\"refundId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, refundId, orderId));
  }
}
