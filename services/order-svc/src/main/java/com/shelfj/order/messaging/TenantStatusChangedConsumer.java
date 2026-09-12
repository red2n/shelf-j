package com.shelfj.order.messaging;

import com.shelfj.service.BaseTenantStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps order-svc's local tenant-status projection current, so orders cannot be placed for a
 * suspended or blocked tenant.
 */
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
