package com.shelfj.inventory.service;

import com.shelfj.events.EventPayload;
import java.math.BigDecimal;
import java.util.UUID;

/** JSON event payloads for the outbox. Past-tense; topic shelfj.inventory.<event>. */
final class Events {

  private Events() {}

  static String stockReceived(
      UUID tenantId, UUID storeId, UUID variantId, UUID batchId, BigDecimal qty) {
    return EventPayload.base("StockReceived", tenantId, batchId)
        + storeVariant(storeId, variantId)
        + ",\"qty\":"
        + qty.toPlainString()
        + "}";
  }

  static String stockReserved(
      UUID tenantId, UUID storeId, UUID variantId, UUID reservationId, BigDecimal qty) {
    return EventPayload.base("StockReserved", tenantId, reservationId)
        + storeVariant(storeId, variantId)
        + ",\"qty\":"
        + qty.toPlainString()
        + "}";
  }

  /**
   * For consume/release the store/variant aren't known at the service layer (resolved in the repo).
   */
  static String reservationEvent(String type, UUID tenantId, UUID reservationId) {
    return EventPayload.base(type, tenantId, reservationId)
        + ",\"reservationId\":\""
        + reservationId
        + "\"}";
  }

  static String stockAdjusted(UUID tenantId, UUID storeId, UUID variantId, BigDecimal delta) {
    return EventPayload.base("StockAdjusted", tenantId, variantId)
        + storeVariant(storeId, variantId)
        + ",\"delta\":"
        + delta.toPlainString()
        + "}";
  }

  static String lowStock(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal available, BigDecimal threshold) {
    return EventPayload.base("LowStock", tenantId, variantId)
        + storeVariant(storeId, variantId)
        + ",\"available\":"
        + available.toPlainString()
        + ",\"threshold\":"
        + threshold.toPlainString()
        + "}";
  }

  static String replenishmentSuggested(
      UUID tenantId, UUID suggId, UUID storeId, UUID variantId, BigDecimal suggestedQty) {
    return EventPayload.base("ReplenishmentSuggested", tenantId, suggId)
        + storeVariant(storeId, variantId)
        + ",\"suggestedQty\":"
        + suggestedQty.toPlainString()
        + "}";
  }

  static String replenishmentResolved(UUID tenantId, UUID suggId, String status) {
    return EventPayload.base("ReplenishmentResolved", tenantId, suggId)
        + ",\"status\":\""
        + status
        + "\"}";
  }

  static String serialsRegistered(UUID tenantId, UUID batchId, int count) {
    return EventPayload.base("SerialsRegistered", tenantId, batchId) + ",\"count\":" + count + "}";
  }

  static String serialStatusChanged(UUID tenantId, UUID serialId, String status) {
    return EventPayload.base("SerialStatusChanged", tenantId, serialId)
        + ",\"status\":\""
        + status
        + "\"}";
  }

  static String materialStatusChanged(
      UUID tenantId, UUID batchId, String materialStatus, String reason) {
    String r = reason == null ? "null" : "\"" + reason.replace("\"", "\\\"") + "\"";
    return EventPayload.base("MaterialStatusChanged", tenantId, batchId)
        + ",\"materialStatus\":\""
        + materialStatus
        + "\",\"reason\":"
        + r
        + "}";
  }

  static String transferOrderShipped(
      UUID tenantId, UUID orderId, UUID fromStoreId, UUID toStoreId) {
    return EventPayload.base("TransferOrderShipped", tenantId, orderId)
        + ",\"fromStoreId\":\""
        + fromStoreId
        + "\",\"toStoreId\":\""
        + toStoreId
        + "\"}";
  }

  static String transferOrderReceived(UUID tenantId, UUID orderId, UUID toStoreId) {
    return EventPayload.base("TransferOrderReceived", tenantId, orderId)
        + ",\"toStoreId\":\""
        + toStoreId
        + "\"}";
  }

  static String transferOrderCancelled(UUID tenantId, UUID orderId) {
    return EventPayload.base("TransferOrderCancelled", tenantId, orderId) + "}";
  }

  static String moveOrderCompleted(UUID tenantId, UUID orderId, UUID fromStoreId, UUID toStoreId) {
    return EventPayload.base("MoveOrderCompleted", tenantId, orderId)
        + ",\"fromStoreId\":\""
        + fromStoreId
        + "\",\"toStoreId\":\""
        + toStoreId
        + "\"}";
  }

  static String moveOrderCancelled(UUID tenantId, UUID orderId) {
    return EventPayload.base("MoveOrderCancelled", tenantId, orderId) + "}";
  }

  static String cycleCountAdjusted(UUID tenantId, UUID headerId) {
    return EventPayload.base("CycleCountAdjusted", tenantId, headerId) + "}";
  }

  static String physicalInventoryCreated(UUID tenantId, UUID piId, UUID storeId) {
    return EventPayload.base("PhysicalInventoryCreated", tenantId, piId)
        + ",\"storeId\":\""
        + storeId
        + "\"}";
  }

  static String physicalInventoryCompleted(UUID tenantId, UUID piId) {
    return EventPayload.base("PhysicalInventoryCompleted", tenantId, piId) + "}";
  }

  static String costingMethodUpdated(UUID tenantId, UUID storeId, UUID variantId, String method) {
    return EventPayload.base("CostingMethodUpdated", tenantId, null)
        + storeVariant(storeId, variantId)
        + ",\"method\":\""
        + method
        + "\"}";
  }

  static String accountingPeriodOpened(
      UUID tenantId, UUID storeId, String periodName, String periodDate) {
    return EventPayload.base("AccountingPeriodOpened", tenantId, null)
        + ",\"storeId\":\""
        + storeId
        + "\",\"periodName\":\""
        + periodName
        + "\",\"periodDate\":\""
        + periodDate
        + "\"}";
  }

  static String accountingPeriodClosed(UUID tenantId, UUID periodId) {
    return EventPayload.base("AccountingPeriodClosed", tenantId, periodId) + "}";
  }

  private static String storeVariant(UUID storeId, UUID variantId) {
    return ",\"storeId\":\"" + storeId + "\",\"variantId\":\"" + variantId + "\"";
  }
}
