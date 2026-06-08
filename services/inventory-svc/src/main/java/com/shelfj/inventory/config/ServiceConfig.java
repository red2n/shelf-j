package com.shelfj.inventory.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for inventory-svc — extends {@link BaseServiceConfig} for the 9 common properties.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "inventory-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8004")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "inventory")
  String dbSchema;

  /** Default reservation lifetime if the caller doesn't specify one (abandoned-cart release). */
  @Inject
  @ConfigProperty(name = "shelfj.inventory.reservation-ttl-seconds", defaultValue = "900")
  long reservationTtlSeconds;

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

  public long reservationTtlSeconds() {
    return reservationTtlSeconds;
  }
}
