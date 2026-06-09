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

  private static String storeVariant(UUID storeId, UUID variantId) {
    return ",\"storeId\":\"" + storeId + "\",\"variantId\":\"" + variantId + "\"";
  }
}
