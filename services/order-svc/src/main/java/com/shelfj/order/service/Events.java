package com.shelfj.order.service;

import com.shelfj.service.OutboxRow;
import java.util.UUID;

/** Builds {@link OutboxRow} instances for all events published by order-svc. */
final class Events {

  private Events() {}

  static OutboxRow orderPlaced(UUID tenantId, UUID orderId, String channel) {
    return new OutboxRow(
        "OrderPlaced",
        "shelfj.order.order-placed",
        tenantId,
        orderId,
        String.format(
            "{\"eventType\":\"OrderPlaced\",\"tenantId\":\"%s\",\"orderId\":\"%s\",\"channel\":\"%s\"}",
            tenantId, orderId, channel));
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
            tenantId, orderId, reason == null ? "" : reason));
  }

  static OutboxRow orderFulfilled(UUID tenantId, UUID orderId) {
    return new OutboxRow(
        "OrderFulfilled",
        "shelfj.order.order-fulfilled",
        tenantId,
        orderId,
        String.format(
            "{\"eventType\":\"OrderFulfilled\",\"tenantId\":\"%s\",\"orderId\":\"%s\"}",
            tenantId, orderId));
  }

  static OutboxRow orderReturned(UUID tenantId, UUID orderId, UUID returnId) {
    return new OutboxRow(
        "OrderReturned",
        "shelfj.order.order-returned",
        tenantId,
        orderId,
        String.format(
            "{\"eventType\":\"OrderReturned\",\"tenantId\":\"%s\",\"orderId\":\"%s\",\"returnId\":\"%s\"}",
            tenantId, orderId, returnId));
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
