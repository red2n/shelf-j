package com.shelfj.order.messaging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Business handler for {@code shelfj.tenant.tenant-currency-declared} events.
 *
 * <p>The repair path for {@link TenantCreatedHandler}'s projection. A tenant onboarded before that
 * consumer existed never had its currency projected, so {@code OrderService.resolveCurrency} falls
 * back to the platform default and stamps the wrong currency onto every order it places — quietly,
 * because a missing projection is indistinguishable from a tenant that genuinely wants the default.
 * tenant-svc re-announces the declared currency on demand, and this projects it.
 *
 * <p>Its own consumer identity, distinct from the TenantCreated one, so the (eventId, consumer)
 * dedupe treats a replay as new work rather than as a redelivery of onboarding.
 */
@ApplicationScoped
class TenantCurrencyDeclaredHandler {

  static final String CONSUMER_NAME = "order-svc/tenant-currency-declared";

  @Inject TenantCurrencyProjector projector;

  void handle(String json) {
    projector.project(json, "TenantCurrencyDeclared", CONSUMER_NAME);
  }
}
