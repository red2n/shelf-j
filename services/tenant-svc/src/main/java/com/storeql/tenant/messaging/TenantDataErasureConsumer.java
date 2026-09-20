package com.storeql.tenant.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from tenant-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "tenant-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "tenant-svc-tenant-data-erasure";
  }
}
