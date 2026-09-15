package com.shelfj.payment.messaging;

import com.shelfj.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from payment-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "payment-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "payment-svc-tenant-data-erasure";
  }
}
