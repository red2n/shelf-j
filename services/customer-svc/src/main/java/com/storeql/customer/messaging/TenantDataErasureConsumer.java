package com.storeql.customer.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from customer-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "customer-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "customer-svc-tenant-data-erasure";
  }
}
