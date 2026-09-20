package com.storeql.iam.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from iam-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "iam-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-tenant-data-erasure";
  }
}
