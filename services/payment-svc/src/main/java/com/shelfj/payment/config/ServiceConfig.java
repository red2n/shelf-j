package com.shelfj.payment.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for payment-svc — extends {@link BaseServiceConfig} for the 9 common properties.
 *
 * <p>Provider credentials (Stripe keys, webhook secrets) are injected where they are used rather
 * than gathered here, so a deployment that has not configured a provider still starts.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8009")
  int servicePort;

  /**
   * {@inheritDoc}
   *
   * @return always {@code payment-svc}
   */
  @Override
  public String serviceName() {
    return "payment-svc";
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8009} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code payment}, the Postgres schema this service owns
   */
  @Override
  public String dbSchema() {
    return "payment";
  }
}
