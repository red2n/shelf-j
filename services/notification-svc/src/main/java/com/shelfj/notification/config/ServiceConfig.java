package com.shelfj.notification.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for notification-svc — extends {@link BaseServiceConfig} for the 9 common
 * properties.
 *
 * <p>The channel and transport settings (SMTP, MQTT) live on {@link
 * com.shelfj.notification.channel.NotificationChannelProducer} instead.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "notification-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8011")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "notification")
  String dbSchema;

  /**
   * {@inheritDoc}
   *
   * @return the Consul registration name, {@code notification-svc} unless overridden
   */
  @Override
  public String serviceName() {
    return serviceName;
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8011} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return the Postgres schema this service owns, {@code notification} unless overridden
   */
  @Override
  public String dbSchema() {
    return dbSchema;
  }
}
