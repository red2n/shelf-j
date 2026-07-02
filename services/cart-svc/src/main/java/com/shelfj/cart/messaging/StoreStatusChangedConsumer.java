package com.shelfj.cart.messaging;

import com.shelfj.service.BaseStoreStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

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
