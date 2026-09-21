package com.storeql.reporting.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from reporting-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "reporting-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "reporting-svc-tenant-data-erasure";
  }
}
