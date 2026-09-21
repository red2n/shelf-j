package com.storeql.pricing.service;

import com.storeql.service.OutboxRow;
import java.util.UUID;

/** Builds {@link OutboxRow} instances for all events published by pricing-svc. */
final class Events {

  private Events() {}

  static OutboxRow priceChanged(UUID tenantId, UUID priceListId) {
    return new OutboxRow(
        "PriceChanged",
        "storeql.pricing.price-changed",
        tenantId,
        priceListId,
        String.format(
            "{\"eventType\":\"PriceChanged\",\"tenantId\":\"%s\",\"priceListId\":\"%s\"}",
            tenantId, priceListId));
  }

  static OutboxRow promotionActivated(UUID tenantId, UUID promotionId) {
    return new OutboxRow(
        "PromotionActivated",
        "storeql.pricing.promotion-activated",
        tenantId,
        promotionId,
        String.format(
            "{\"eventType\":\"PromotionActivated\",\"tenantId\":\"%s\",\"promotionId\":\"%s\"}",
            tenantId, promotionId));
  }
}
