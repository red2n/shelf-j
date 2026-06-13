package com.shelfj.customer.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for customer-svc — extends {@link BaseServiceConfig} for the 9 common properties.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "customer-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8013")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "customer")
  String dbSchema;

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
