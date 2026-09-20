package com.storeql.product.service;

import com.storeql.events.EventPayload;
import java.util.List;
import java.util.UUID;

/** JSON event payloads for the outbox. Past-tense; topic storeql.catalog.<event>. */
final class Events {

  private Events() {}

  /**
   * What a published planogram says a shelf should hold, per variant, for one store (07.17).
   *
   * <p>The one thing merchandising owes replenishment. inventory-svc projects it and uses capacity
   * as the shelf target and {@code minPresentation} as the point below which the shelf looks picked
   * over — which is a different number from a stockroom minimum, and the reason replenishment is
   * driven from the shelf at all.
   *
   * <p>Sent whole per store rather than as a delta: a layout is replaced version by version, and a
   * consumer that missed one delta would hold a shelf that never existed. Whole means a projection
   * can be rebuilt from the latest event alone.
   */
  static String shelfCapacityPublished(
      UUID tenantId,
      UUID storeId,
      UUID planogramId,
      int version,
      UUID fixtureId,
      List<UUID> variantIds,
      List<Integer> capacities,
      List<Integer> minPresentations) {
    StringBuilder shelves = new StringBuilder("[");
    for (int i = 0; i < variantIds.size(); i++) {
      if (i > 0) shelves.append(',');
      shelves
          .append("{\"variantId\":\"")
          .append(variantIds.get(i))
          .append("\",\"capacity\":")
          .append(capacities.get(i))
          .append(",\"minPresentation\":")
          .append(minPresentations.get(i))
          .append('}');
    }
    shelves.append(']');
    return EventPayload.base("ShelfCapacityPublished", tenantId, planogramId)
        + ",\"storeId\":\""
        + storeId
        + "\",\"fixtureId\":\""
        + fixtureId
        + "\",\"planogramId\":\""
        + planogramId
        + "\",\"version\":"
        + version
        + ",\"positions\":"
        + shelves
        + "}";
  }

  /**
   * A fixture is gone. Its capacity goes with it: a projection holding the shelf of a bay that has
   * been taken out would have replenishment filling furniture nobody can see.
   */
  static String fixtureRetired(UUID tenantId, UUID storeId, UUID fixtureId) {
    return EventPayload.base("FixtureRetired", tenantId, fixtureId)
        + ",\"storeId\":\""
        + storeId
        + "\",\"fixtureId\":\""
        + fixtureId
        + "\"}";
  }

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
      com.storeql.product.domain.Domain.UnitMeasure measure,
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
