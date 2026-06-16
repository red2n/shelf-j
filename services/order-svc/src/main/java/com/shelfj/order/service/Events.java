package com.shelfj.order.service;

import static com.shelfj.events.EventPayload.esc;

import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.service.OutboxRow;
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

  static OutboxRow orderConfirmed(UUID tenantId, UUID orderId) {
    return new OutboxRow(
        "OrderConfirmed",
        "shelfj.order.order-confirmed",
        tenantId,
        orderId,
        String.format(
            "{\"eventType\":\"OrderConfirmed\",\"tenantId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, orderId));
  }

  static OutboxRow orderCancelled(UUID tenantId, UUID orderId, String reason) {
    return new OutboxRow(
        "OrderCancelled",
        "shelfj.order.order-cancelled",
        tenantId,
        orderId,
        String.format(
            "{\"eventType\":\"OrderCancelled\",\"tenantId\":\"%s\",\"orderId\":\"%s\",\"reason\":\"%s\"}",
            tenantId, orderId, esc(reason)));
  }

  static OutboxRow orderFulfilled(
      UUID tenantId, UUID orderId, UUID storeId, List<OrderItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventType\":\"OrderFulfilled\",\"tenantId\":\"")
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
      UUID tenantId, UUID orderId, UUID returnId, UUID storeId, List<ReturnItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventType\":\"OrderReturned\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"orderId\":\"")
        .append(orderId)
        .append("\",\"returnId\":\"")
        .append(returnId)
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
