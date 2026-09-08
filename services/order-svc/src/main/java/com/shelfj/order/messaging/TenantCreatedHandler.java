package com.shelfj.order.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Business handler for {@code shelfj.tenant.tenant-created} events. Projects the tenant's trading
 * currency onto this service's local {@code tenant_status} row so {@code OrderService} can stamp
 * money-bearing rows with the tenant's own currency instead of a hardcoded literal, without a
 * synchronous call to tenant-svc on the checkout path.
 *
 * <p>Separated from {@link TenantCreatedConsumer} so Kafka lifecycle and domain logic each have a
 * single reason to change (SRP). The parse-validate-project sequence itself lives in {@link
 * TenantCurrencyProjector}, shared with {@link TenantCurrencyDeclaredHandler}, which projects the
 * same fact when tenant-svc re-announces it to repair a tenant this event never covered.
 */
@ApplicationScoped
class TenantCreatedHandler {

  static final String CONSUMER_NAME = "order-svc/tenant-created";

  @Inject TenantCurrencyProjector projector;

  void handle(String json) {
    projector.project(json, "TenantCreated", CONSUMER_NAME);
  }
}
