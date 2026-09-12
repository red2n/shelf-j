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

  /**
   * {@inheritDoc}
   *
   * @return the Consul registration name, {@code inventory-svc} unless overridden
   */
  @Override
  public String serviceName() {
    return serviceName;
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8004} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return the Postgres schema this service owns, {@code inventory} unless overridden
   */
  @Override
  public String dbSchema() {
    return dbSchema;
  }

  /**
   * How long a stock hold lives when the caller names no lifetime of its own.
   *
   * @return the default reservation lifetime in seconds, 15 minutes unless overridden
   */
  public long reservationTtlSeconds() {
    return reservationTtlSeconds;
  }
}
