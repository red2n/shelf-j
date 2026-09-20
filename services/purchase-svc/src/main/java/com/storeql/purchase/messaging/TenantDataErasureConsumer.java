package com.storeql.purchase.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from purchase-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "purchase-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "purchase-svc-tenant-data-erasure";
  }
}
