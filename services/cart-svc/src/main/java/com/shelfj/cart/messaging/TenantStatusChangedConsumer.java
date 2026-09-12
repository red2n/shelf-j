package com.shelfj.cart.messaging;

import com.shelfj.service.BaseTenantStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps cart-svc's local tenant-status projection current so {@code CartService} can reject cart
 * operations for a suspended or blocked tenant without calling tenant-svc.
 */
@ApplicationScoped
class TenantStatusChangedConsumer extends BaseTenantStatusChangedConsumer {

  @Override
  protected String consumerName() {
    return "cart-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "cart-svc-tenant-status";
  }
}
