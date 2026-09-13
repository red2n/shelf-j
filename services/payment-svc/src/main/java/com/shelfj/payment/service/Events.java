package com.shelfj.payment.service;

import com.shelfj.ids.Ids;
import com.shelfj.service.OutboxRow;
import java.util.UUID;

/**
 * Builds the outbox rows payment-svc publishes.
 *
 * <p>Each factory returns an {@link OutboxRow} for a caller to write in the same transaction as the
 * state change it announces, so the event and the write commit together.
 */
final class Events {

  private Events() {}

  /**
   * A tender captured against an order. {@code method} is how it was paid (CASH, CARD, UPI, WALLET,
   * STORE_CREDIT, or the online provider's name): order-svc records it per tender because a German
   * fiscal file lists every payment as cash or not, and the security module signs that split
   * (18.5). Null is written as an absent field, which older consumers never read.
   */
  static OutboxRow paymentCaptured(
      UUID tenantId,
      UUID paymentId,
      UUID orderId,
      java.math.BigDecimal amount,
      String method,
      UUID storeId) {
    String methodField =
        method == null || method.isBlank() ? "" : ",\"method\":\"" + clean(method) + "\"";
    // The store the tender was taken at, so the ledger posts it to that store (17.7).
    String storeField = storeId == null ? "" : ",\"storeId\":\"" + storeId + "\"";
    return new OutboxRow(
        "PaymentCaptured",
        "shelfj.payment.payment-captured",
        tenantId,
        paymentId,
        String.format(
            "{\"eventType\":\"PaymentCaptured\",\"tenantId\":\"%s\",\"paymentId\":\"%s\","
                + "\"orderId\":\"%s\",\"amount\":%s%s%s}",
            tenantId, paymentId, orderId, amount.toPlainString(), methodField, storeField));
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
      UUID tenantId,
      UUID refundId,
      UUID orderId,
      java.math.BigDecimal amount,
      java.util.List<com.shelfj.payment.domain.Domain.RefundAllocation> tenders) {
    // Each tender's share, so the ledger credits the control account the money left from (17.7).
    StringBuilder shares = new StringBuilder();
    for (var t : tenders) {
      if (shares.length() > 0) shares.append(',');
      shares.append("{\"paymentId\":\"").append(t.paymentId()).append('"');
      if (t.method() != null) shares.append(",\"method\":\"").append(clean(t.method())).append('"');
      if (t.storeId() != null) shares.append(",\"storeId\":\"").append(t.storeId()).append('"');
      shares.append(",\"amount\":").append(t.amount().toPlainString()).append('}');
    }
    return new OutboxRow(
        "PaymentRefunded",
        "shelfj.payment.payment-refunded",
        tenantId,
        refundId,
        String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\"%s\","
                + "\"refundId\":\"%s\",\"orderId\":\"%s\",\"amount\":%s,\"tenders\":[%s]}",
            Ids.newId(), tenantId, refundId, orderId, amount.toPlainString(), shares));
  }

  private static String clean(String s) {
    return s.replace("\\", "").replace("\"", "");
  }
}
