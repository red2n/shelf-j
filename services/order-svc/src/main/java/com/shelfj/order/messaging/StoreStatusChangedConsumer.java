package com.shelfj.order.messaging;

import com.shelfj.service.BaseStoreStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps order-svc's local store-status projection current, so an order cannot be placed against a
 * store that has since closed or been suspended.
 */
@ApplicationScoped
class StoreStatusChangedConsumer extends BaseStoreStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "order-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-store-status";
  }
}
