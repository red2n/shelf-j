package com.shelfj.pricing.messaging;

import com.shelfj.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from pricing-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "pricing-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "pricing-svc-tenant-data-erasure";
  }
}
