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
