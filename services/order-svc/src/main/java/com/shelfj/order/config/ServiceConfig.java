package com.shelfj.order.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

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
