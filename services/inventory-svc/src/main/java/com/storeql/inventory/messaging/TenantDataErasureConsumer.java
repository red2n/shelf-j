package com.storeql.inventory.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from inventory-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "inventory-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "inventory-svc-tenant-data-erasure";
  }
}
