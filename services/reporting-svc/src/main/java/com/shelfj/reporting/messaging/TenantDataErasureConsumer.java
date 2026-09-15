package com.shelfj.reporting.messaging;

import com.shelfj.service.BaseTenantDataErasureConsumer;
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
