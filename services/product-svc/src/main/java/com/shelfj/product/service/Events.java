package com.shelfj.product.service;

import com.shelfj.events.EventPayload;
import java.util.UUID;

/** JSON event payloads for the outbox. Past-tense; topic shelfj.catalog.<event>. */
final class Events {

  private Events() {}

  static String productCreated(UUID tenantId, UUID productId, String name) {
    return EventPayload.base("ProductCreated", tenantId, productId)
        + ",\"name\":\""
        + EventPayload.esc(name)
        + "\"}";
  }

  static String productUpdated(UUID tenantId, UUID productId, String status) {
    return EventPayload.base("ProductUpdated", tenantId, productId)
        + ",\"status\":\""
        + status
        + "\"}";
  }

  static String productDelisted(UUID tenantId, UUID productId) {
    return EventPayload.base("ProductDelisted", tenantId, productId) + "}";
  }

  static String variantCreated(UUID tenantId, UUID variantId, UUID productId, String sku) {
    return EventPayload.base("VariantCreated", tenantId, variantId)
        + ",\"productId\":\""
        + productId
        + "\",\"sku\":\""
        + EventPayload.esc(sku)
        + "\"}";
  }

  static String itemRevisionCreated(
      UUID tenantId, UUID variantId, UUID revisionId, String revision) {
    return EventPayload.base("ItemRevisionCreated", tenantId, revisionId)
        + ",\"variantId\":\""
        + variantId
        + "\",\"revision\":\""
        + EventPayload.esc(revision)
        + "\"}";
  }
}
