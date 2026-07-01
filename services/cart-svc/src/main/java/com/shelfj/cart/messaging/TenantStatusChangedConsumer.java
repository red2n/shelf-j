package com.shelfj.cart.messaging;

import com.shelfj.service.BaseTenantStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class TenantStatusChangedConsumer extends BaseTenantStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "cart-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-tenant-status";
  }
}
