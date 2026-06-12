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
   * client-supplied unitPrice (gap #63 — clients must not set their own prices). Defaults to false
   * so local dev / test rigs without a seeded price catalogue keep working; production MUST enable
   * it.
   */
  @Inject
  @ConfigProperty(name = "shelfj.order.pricing.enforce", defaultValue = "false")
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
