package com.shelfj.iam.messaging;

import com.shelfj.service.BaseTenantStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class TenantStatusChangedConsumer extends BaseTenantStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "iam-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-tenant-status";
  }
}
