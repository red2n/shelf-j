package com.shelfj.product.messaging;

import com.shelfj.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from product-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "product-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "product-svc-tenant-data-erasure";
  }
}
