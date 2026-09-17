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

  /**
   * A cardholder's bank has taken a card payment back, or says it will (11.9). The ledger moves the
   * amount out of card clearing into disputed receipts when {@code fundsWithdrawn}, and books the
   * acquirer's fee; notification-svc tells the business and says by when it must answer.
   */
  static OutboxRow disputeOpened(com.shelfj.payment.domain.Disputes.Dispute d) {
    return disputeEvent("PaymentDisputeOpened", "shelfj.payment.dispute-opened", d, null);
  }

  /** The acquirer has debited a dispute opened without it: the same postings, later. */
  static OutboxRow disputeFundsWithdrawn(com.shelfj.payment.domain.Disputes.Dispute d) {
    return disputeEvent(
        "PaymentDisputeFundsWithdrawn", "shelfj.payment.dispute-funds-withdrawn", d, null);
  }

  /**
   * A dispute is over. {@code outcome} is WON (the money comes back), LOST or ACCEPTED (it does
   * not).
   */
  static OutboxRow disputeClosed(com.shelfj.payment.domain.Disputes.Dispute d, String outcome) {
    return disputeEvent("PaymentDisputeClosed", "shelfj.payment.dispute-closed", d, outcome);
  }

  private static OutboxRow disputeEvent(
      String type, String topic, com.shelfj.payment.domain.Disputes.Dispute d, String outcome) {
    StringBuilder json = new StringBuilder(320);
    json.append("{\"eventId\":\"").append(com.shelfj.ids.Ids.newId()).append('"');
    json.append(",\"eventType\":\"").append(type).append('"');
    json.append(",\"tenantId\":\"").append(d.tenantId()).append('"');
    json.append(",\"disputeId\":\"").append(d.id()).append('"');
    json.append(",\"paymentId\":\"").append(d.paymentId()).append('"');
    json.append(",\"orderId\":\"").append(d.orderId()).append('"');
    if (d.storeId() != null) json.append(",\"storeId\":\"").append(d.storeId()).append('"');
    json.append(",\"amount\":").append(d.amount().toPlainString());
    json.append(",\"feeAmount\":").append(d.feeAmount().toPlainString());
    json.append(",\"currency\":\"").append(clean(d.currency())).append('"');
    json.append(",\"reason\":\"").append(clean(d.reason())).append('"');
    json.append(",\"fundsWithdrawn\":").append(d.fundsWithdrawn());
    if (d.evidenceDueBy() != null) {
      json.append(",\"evidenceDueBy\":\"").append(d.evidenceDueBy()).append('"');
    }
    if (outcome != null) json.append(",\"outcome\":\"").append(clean(outcome)).append('"');
    json.append(",\"occurredAt\":\"").append(java.time.Instant.now()).append("\"}");
    return new OutboxRow(type, topic, d.tenantId(), d.id(), json.toString());
  }

  private static String clean(String s) {
    return s.replace("\\", "").replace("\"", "");
  }
}
