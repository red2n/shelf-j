package com.shelfj.product.service;

import com.shelfj.events.EventPayload;
import java.util.List;
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

  /**
   * Where a product sits in the category tree, and which variants are its (03.8). {@code
   * categoryPath} runs from the product's own category up to the root, so a consumer scoping by a
   * parent category matches its children; empty when the product has no category. pricing-svc
   * projects it to honour category-scoped promotions; anything else that needs a variant's category
   * — sales by category, say — has the same event to read.
   */
  static String productCategorised(
      UUID tenantId,
      UUID productId,
      java.util.List<UUID> categoryPath,
      java.util.List<UUID> variantIds) {
    return EventPayload.base("ProductCategorised", tenantId, productId)
        + ",\"productId\":\""
        + productId
        + "\",\"categoryPath\":"
        + ids(categoryPath)
        + ",\"variantIds\":"
        + ids(variantIds)
        + "}";
  }

  private static String ids(java.util.List<UUID> ids) {
    return ids.stream()
        .map(id -> "\"" + id + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  /**
   * A variant's measure as its unit price needs it (03.13): the standard unit and the quantity one
   * price buys, or nulls when none is declared.
   */
  static String variantMeasured(
      UUID tenantId,
      UUID variantId,
      UUID productId,
      String soldBy,
      com.shelfj.product.domain.Domain.UnitMeasure measure,
      long version) {
    return EventPayload.base("VariantMeasured", tenantId, variantId)
        + ",\"version\":"
        + version
        + ",\"productId\":\""
        + productId
        + "\",\"soldBy\":\""
        + EventPayload.esc(soldBy)
        + "\""
        + (measure == null
            ? ",\"unit\":null,\"quantity\":null"
            : ",\"unit\":\""
                + measure.unit()
                + "\",\"quantity\":\""
                + measure.quantity().toPlainString()
                + "\"")
        + "}";
  }

  static String productDelisted(UUID tenantId, UUID productId) {
    return EventPayload.base("ProductDelisted", tenantId, productId) + "}";
  }

  /**
   * A lifecycle move with the variants it covers, so a consumer keyed by variant (inventory's
   * replenishment) needs no read of the catalogue: {@code ProductDiscontinued}, {@code
   * ProductDelisted}, {@code ProductLaunched}, {@code ProductReinstated}.
   */
  static String productLifecycle(
      String eventType, UUID tenantId, UUID productId, String status, List<UUID> variantIds) {
    StringBuilder ids = new StringBuilder();
    for (UUID v : variantIds) {
      if (ids.length() > 0) ids.append(',');
      ids.append('"').append(v).append('"');
    }
    return EventPayload.base(eventType, tenantId, productId)
        + ",\"status\":\""
        + status
        + "\",\"variantIds\":["
        + ids
        + "]}";
  }

  static String variantCreated(UUID tenantId, UUID variantId, UUID productId, String sku) {
    return EventPayload.base("VariantCreated", tenantId, variantId)
        + ",\"productId\":\""
        + productId
        + "\",\"sku\":\""
        + EventPayload.esc(sku)
        + "\"}";
  }

  static String itemTemplateCreated(UUID tenantId, UUID templateId, String name) {
    return EventPayload.base("ItemTemplateCreated", tenantId, templateId)
        + ",\"name\":\""
        + EventPayload.esc(name)
        + "\"}";
  }

  static String itemTemplateApplied(UUID tenantId, UUID variantId, UUID templateId) {
    return EventPayload.base("ItemTemplateApplied", tenantId, variantId)
        + ",\"templateId\":\""
        + templateId
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
