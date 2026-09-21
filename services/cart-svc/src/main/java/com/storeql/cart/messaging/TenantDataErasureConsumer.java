package com.storeql.cart.messaging;

import com.storeql.service.BaseTenantDataErasureConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/** Erases a departed business's data from cart-svc when its retrieval period ends (21.14). */
@ApplicationScoped
class TenantDataErasureConsumer extends BaseTenantDataErasureConsumer {

  @Override
  protected String consumerName() {
    return "cart-tenant-data-erasure-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-tenant-data-erasure";
  }
}
