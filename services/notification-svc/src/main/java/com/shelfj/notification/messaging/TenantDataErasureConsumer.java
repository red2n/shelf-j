package com.shelfj.notification.messaging;

import com.shelfj.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Erases a departed business's data from notification-svc when its retrieval period ends (21.14).
 */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "notification-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "notification-svc-tenant-data-erasure";
  }
}
