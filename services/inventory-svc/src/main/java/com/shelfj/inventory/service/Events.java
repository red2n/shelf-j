package com.shelfj.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JSON event payloads for the outbox. Past-tense; topic shelfj.inventory.<event>. */
final class Events {

    private Events() {}

    static String stockReceived(UUID tenantId, UUID storeId, UUID variantId, UUID batchId, BigDecimal qty) {
        return base("StockReceived", tenantId, batchId)
                + storeVariant(storeId, variantId) + ",\"qty\":" + qty.toPlainString() + "}";
    }

    static String stockReserved(UUID tenantId, UUID storeId, UUID variantId, UUID reservationId, BigDecimal qty) {
        return base("StockReserved", tenantId, reservationId)
                + storeVariant(storeId, variantId) + ",\"qty\":" + qty.toPlainString() + "}";
    }

    /** For consume/release the store/variant aren't known at the service layer (resolved in the repo). */
    static String reservationEvent(String type, UUID tenantId, UUID reservationId) {
        return base(type, tenantId, reservationId) + ",\"reservationId\":\"" + reservationId + "\"}";
    }

    static String stockAdjusted(UUID tenantId, UUID storeId, UUID variantId, BigDecimal delta) {
        return base("StockAdjusted", tenantId, variantId)
                + storeVariant(storeId, variantId) + ",\"delta\":" + delta.toPlainString() + "}";
    }

    static String lowStock(UUID tenantId, UUID storeId, UUID variantId, BigDecimal available, BigDecimal threshold) {
        return base("LowStock", tenantId, variantId) + storeVariant(storeId, variantId)
                + ",\"available\":" + available.toPlainString() + ",\"threshold\":" + threshold.toPlainString() + "}";
    }

    private static String base(String type, UUID tenantId, UUID aggregateId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type
                + "\",\"tenantId\":\"" + tenantId + "\",\"aggregateId\":\"" + aggregateId
                + "\",\"occurredAt\":\"" + Instant.now() + "\"";
    }

    private static String storeVariant(UUID storeId, UUID variantId) {
        return ",\"storeId\":\"" + storeId + "\",\"variantId\":\"" + variantId + "\"";
    }
}
