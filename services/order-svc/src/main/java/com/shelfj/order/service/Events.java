package com.shelfj.order.service;

import static com.shelfj.events.EventPayload.esc;

import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.service.OutboxRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Builds {@link OutboxRow} instances for all events published by order-svc. Request-supplied
 * strings (channel, cancel reason) are escaped — they must not be able to corrupt event JSON.
 */
final class Events {

  private Events() {}

  static OutboxRow orderPlaced(
      UUID tenantId, UUID orderId, String channel, UUID customerId, UUID storeId) {
    String customerPart =
        customerId != null ? ",\"customerId\":\"" + customerId + "\"" : ",\"customerId\":null";
    return new OutboxRow(
        "OrderPlaced",
        "shelfj.order.order-placed",
        tenantId,
        orderId,
        "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
            + tenantId
            + "\",\"orderId\":\""
            + orderId
            + "\",\"channel\":\""
            + esc(channel)
            + "\",\"storeId\":\""
            + storeId
            + "\""
            + customerPart
            + "}");
  }

  /**
   * OrderConfirmed carries an {@code eventId} (consumer dedupe) plus the buyer and settled amount
   * so downstream consumers can react to the sale without a callback to order-svc — customer-svc
   * accrues loyalty from {@code customerId}/{@code total} (guest orders send {@code
   * customerId:null} and earn nothing). Emitted exactly once, at full payment (see
   * OrderRepository.applyPaymentCaptured).
   */
  static OutboxRow orderConfirmed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String channel,
      UUID customerId,
      BigDecimal total,
      String currency) {
    String customerPart = customerId != null ? "\"" + customerId + "\"" : "null";
    String amount = total != null ? total.toPlainString() : "0";
    String cur = currency != null ? currency : "GBP";
    return new OutboxRow(
        "OrderConfirmed",
        "shelfj.order.order-confirmed",
        tenantId,
        orderId,
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + tenantId
            + "\",\"orderId\":\""
            + orderId
            + "\",\"storeId\":\""
            + storeId
            + "\",\"channel\":\""
            + esc(channel)
            + "\",\"customerId\":"
            + customerPart
            + ",\"total\":"
            + amount
            + ",\"currency\":\""
            + esc(cur)
            + "\"}");
  }

  static OutboxRow orderCancelled(UUID tenantId, UUID orderId, String reason) {
    // eventId lets payment-svc dedupe the automatic refund of a cancelled (paid) order; existing
    // consumers (inventory-svc hold release) ignore the extra field.
    return new OutboxRow(
        "OrderCancelled",
        "shelfj.order.order-cancelled",
        tenantId,
        orderId,
        String.format(
            "{\"eventId\":\"%s\",\"eventType\":\"OrderCancelled\",\"tenantId\":\"%s\","
                + "\"orderId\":\"%s\",\"reason\":\"%s\"}",
            UUID.randomUUID(), tenantId, orderId, esc(reason)));
  }

  static OutboxRow orderFulfilled(
      UUID tenantId, UUID orderId, UUID storeId, List<OrderItem> items) {
    // eventId is required by inventory-svc's OrderEventHandler for per-line dedupe — without it,
    // every OrderFulfilled is dropped as a malformed event and stock is never deducted.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(UUID.randomUUID())
        .append("\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"items\":[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(items.get(i).variantId())
          .append("\",\"qty\":")
          .append(items.get(i).qty().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderFulfilled", "shelfj.order.order-fulfilled", tenantId, orderId, sb.toString());
  }

  static OutboxRow orderReturned(
      UUID tenantId,
      UUID orderId,
      UUID returnId,
      UUID storeId,
      List<ReturnItem> items,
      BigDecimal refundAmount,
      String refundMethod,
      String currency) {
    // eventId is required by inventory-svc's OrderEventHandler for per-line dedupe — without it
    // every OrderReturned is dropped as malformed and stock is never restocked. refundAmount +
    // refundMethod let payment-svc reverse the captured payment for ORIGINAL-tender returns.
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(UUID.randomUUID())
        .append("\",\"eventType\":\"OrderReturned\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"returnId\":\"")
        .append(returnId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"refundAmount\":")
        .append(refundAmount != null ? refundAmount.toPlainString() : "0")
        .append(",\"refundMethod\":\"")
        .append(esc(refundMethod))
        .append("\",\"currency\":\"")
        .append(esc(currency))
        .append("\",\"items\":[");
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(items.get(i).variantId())
          .append("\",\"qty\":")
          .append(items.get(i).qty().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "OrderReturned", "shelfj.order.order-returned", tenantId, orderId, sb.toString());
  }

  static OutboxRow orderVoided(UUID tenantId, UUID orderId) {
    return new OutboxRow(
        "OrderVoided",
        "shelfj.order.order-voided",
        tenantId,
        orderId,
        String.format(
            "{\"eventType\":\"OrderVoided\",\"tenantId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, orderId));
  }

  static OutboxRow layawayCreated(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCreated",
        "shelfj.order.layaway-created",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCreated\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }

  static OutboxRow layawayCompleted(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCompleted",
        "shelfj.order.layaway-completed",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCompleted\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }

  static OutboxRow layawayCancelled(UUID tenantId, UUID layawayId) {
    return new OutboxRow(
        "LayawayCancelled",
        "shelfj.order.layaway-cancelled",
        tenantId,
        layawayId,
        String.format(
            "{\"eventType\":\"LayawayCancelled\",\"tenantId\":\"%s\",\"layawayId\":\"%s\"}",
            tenantId, layawayId));
  }
}
