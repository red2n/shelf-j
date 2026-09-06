package com.shelfj.order.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  private static final Logger LOG = System.getLogger(ServiceConfig.class.getName());

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "order-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8007")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "order")
  String dbSchema;

  /**
   * When true, placeOrder resolves every line's unit price from pricing-svc and ignores the
   * client-supplied unitPrice. Defaults to true (secure). Override to false only in local dev rigs
   * that have no seeded price catalogue (docker-compose sets SHELFJ_ORDER_PRICING_ENFORCE=false).
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.pricing.enforce", defaultValue = "true")
  boolean pricingEnforce;

  public boolean pricingEnforce() {
    return pricingEnforce;
  }

  /**
   * Fallback currency for a tenant whose {@code TenantCreated} has not been projected yet — a
   * tenant onboarded before the projection existed, or plain event-delivery lag. This is the ONLY
   * currency literal left in the service; every money-bearing write resolves through {@code
   * OrderService.resolveCurrency}, which prefers the tenant's own projected currency.
   *
   * <p>GBP matches the default every other service already uses (pricing, purchase, customer); the
   * three divergent literals this replaces were the bug (SJ-D2).
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.currency.default", defaultValue = "GBP")
  String defaultCurrency;

  public String defaultCurrency() {
    return defaultCurrency;
  }

  /**
   * When true, placeOrder holds stock in inventory-svc for every ONLINE order line and rejects the
   * order when stock is short or inventory-svc is unreachable (fail-closed). Override to false only
   * in local dev rigs with no seeded inventory.
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.inventory.reserve-enforce", defaultValue = "true")
  boolean reserveEnforce;

  public boolean reserveEnforce() {
    return reserveEnforce;
  }

  /**
   * Lifetime of the stock holds placed at ONLINE checkout. Long by design: a pay-later order can
   * sit PENDING for hours before staff confirm it. Expired holds are reclaimed by inventory-svc's
   * reservation sweeper; the order itself stays valid (it just loses its hold).
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.inventory.reservation-ttl-seconds", defaultValue = "172800")
  long reservationTtlSeconds;

  public long reservationTtlSeconds() {
    return reservationTtlSeconds;
  }

  /**
   * Fires on every boot so a non-dev environment that inherits docker-compose's pricing.enforce=
   * false override (no seeded price catalogue) cannot silently trust client-supplied prices, tax,
   * and discounts without it showing up in the startup log.
   */
  @PostConstruct
  void warnIfPricingEnforcementDisabled() {
    if (!pricingEnforce) {
      LOG.log(
          Level.WARNING,
          "shelfj.order.pricing.enforce=false — order-svc is TRUSTING client-supplied"
              + " unitPrice/taxAmount/discountAmount instead of resolving them from pricing-svc."
              + " This is only safe for local dev with no seeded price catalogue; it must never be"
              + " set in a staging or production environment.");
    }
    if (!reserveEnforce) {
      LOG.log(
          Level.WARNING,
          "shelfj.order.inventory.reserve-enforce=false — ONLINE orders are placed WITHOUT holding"
              + " stock, so concurrent checkouts can oversell. This is only safe for local dev"
              + " with no seeded inventory; it must never be set in staging or production.");
    }
  }

  @Override
  public String serviceName() {
    return serviceName;
  }

  @Override
  public int servicePort() {
    return servicePort;
  }

  @Override
  public String dbSchema() {
    return dbSchema;
  }
}
