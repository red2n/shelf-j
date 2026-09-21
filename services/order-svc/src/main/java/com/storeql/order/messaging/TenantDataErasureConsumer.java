package com.storeql.order.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from order-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "order-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "order-svc-tenant-data-erasure";
  }
}
