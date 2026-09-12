package com.shelfj.cart.messaging;

import com.shelfj.service.BaseStoreStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps cart-svc's local store-status projection current so {@code CartService} can reject cart
 * operations against a closed or suspended store without calling tenant-svc.
 */
@ApplicationScoped
class StoreStatusChangedConsumer extends BaseStoreStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "cart-store-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-store-status";
  }
}
