package com.shelfj.order.messaging;

import com.shelfj.service.BaseTenantStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class TenantStatusChangedConsumer extends BaseTenantStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "order-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-tenant-status";
  }
}
