package com.shelfj.notification.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "notification-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8012")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "notification")
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
