package com.shelfj.pricing.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Typed config for pricing-svc — extends {@link BaseServiceConfig} for the 9 common properties. */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8005")
  int servicePort;

  /**
   * {@inheritDoc}
   *
   * @return always {@code pricing-svc}
   */
  @Override
  public String serviceName() {
    return "pricing-svc";
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8005} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code pricing}, the Postgres schema this service owns
   */
  @Override
  public String dbSchema() {
    return "pricing";
  }
}
