package com.shelfj.iam.messaging;

import com.shelfj.service.BaseTenantStatusChangedConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps iam-svc's local tenant-status projection current, so a POS session cannot be opened for a
 * suspended or blocked tenant.
 */
@ApplicationScoped
class TenantStatusChangedConsumer extends BaseTenantStatusChangedConsumer {

  @Inject PosSessionTenantStatusHandler posSessionHandler;

  @Override
  protected String consumerName() {
    return "iam-tenant-status-changed-consumer";
  }

  @Override
  protected String groupId() {
    return "iam-svc-tenant-status";
  }

  /**
   * Updates the shared {@code tenant_status} projection (inherited handler), then dispatches to
   * {@link PosSessionTenantStatusHandler} to end active POS sessions tenant-wide once the tenant
   * goes non-ACTIVE. Kafka lifecycle stays here; both pieces of business logic live in Handler
   * beans (SRP — see docs/coding-standards.md §2.1).
   */
  @Override
  protected void handle(String topic, String value) {
    super.handle(topic, value);
    posSessionHandler.handle(value);
  }
}
