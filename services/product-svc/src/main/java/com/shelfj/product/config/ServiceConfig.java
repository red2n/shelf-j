package com.shelfj.product.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Typed config for product-svc — extends {@link BaseServiceConfig} for the 9 common properties. */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "product-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8003")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "product")
  String dbSchema;

  /**
   * {@inheritDoc}
   *
   * @return the Consul registration name, {@code product-svc} unless overridden
   */
  @Override
  public String serviceName() {
    return serviceName;
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8003} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return the Postgres schema this service owns, {@code product} unless overridden
   */
  @Override
  public String dbSchema() {
    return dbSchema;
  }
}
