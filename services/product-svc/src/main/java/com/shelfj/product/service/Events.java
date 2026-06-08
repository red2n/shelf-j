package com.shelfj.product.service;

import java.time.Instant;
import java.util.UUID;

/** JSON event payloads for the outbox. Past-tense; topic shelfj.catalog.<event>. */
final class Events {

  private Events() {}

  static String productCreated(UUID tenantId, UUID productId, String name) {
    return base("ProductCreated", tenantId, productId) + ",\"name\":\"" + esc(name) + "\"}";
  }

  static String productUpdated(UUID tenantId, UUID productId, String status) {
    return base("ProductUpdated", tenantId, productId) + ",\"status\":\"" + status + "\"}";
  }

  static String productDelisted(UUID tenantId, UUID productId) {
    return base("ProductDelisted", tenantId, productId) + "}";
  }

  static String variantCreated(UUID tenantId, UUID variantId, UUID productId, String sku) {
    return base("VariantCreated", tenantId, variantId)
        + ",\"productId\":\""
        + productId
        + "\",\"sku\":\""
        + esc(sku)
        + "\"}";
  }

  private static String base(String type, UUID tenantId, UUID aggregateId) {
    return "{\"eventId\":\""
        + UUID.randomUUID()
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + tenantId
        + "\",\"aggregateId\":\""
        + aggregateId
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\"";
  }

  private static String esc(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
