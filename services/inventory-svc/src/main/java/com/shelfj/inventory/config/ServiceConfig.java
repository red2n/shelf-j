package com.shelfj.inventory.config;

import com.shelfj.service.ServiceSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for inventory-svc. Provides {@link ServiceSettings} for the shared service
 * infrastructure.
 */
@ApplicationScoped
public class ServiceConfig implements ServiceSettings {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "inventory-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8004")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.url", defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
  String dbUrl;

  @Inject
  @ConfigProperty(name = "shelfj.db.user", defaultValue = "shelfj")
  String dbUser;

  @Inject
  @ConfigProperty(name = "shelfj.db.password", defaultValue = "shelfj_dev_change_me")
  String dbPassword;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "inventory")
  String dbSchema;

  @Inject
  @ConfigProperty(name = "shelfj.consul.host", defaultValue = "localhost")
  String consulHost;

  @Inject
  @ConfigProperty(name = "shelfj.consul.port", defaultValue = "8500")
  int consulPort;

  @Inject
  @ConfigProperty(name = "shelfj.consul.enabled", defaultValue = "true")
  boolean consulEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String kafkaBootstrap;

  @Inject
  @ConfigProperty(name = "shelfj.outbox.poll-seconds", defaultValue = "5")
  long outboxPollSeconds;

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
  public String dbUrl() {
    return dbUrl;
  }

  @Override
  public String dbUser() {
    return dbUser;
  }

  @Override
  public String dbPassword() {
    return dbPassword;
  }

  @Override
  public String dbSchema() {
    return dbSchema;
  }

  @Override
  public String consulHost() {
    return consulHost;
  }

  @Override
  public int consulPort() {
    return consulPort;
  }

  @Override
  public boolean consulEnabled() {
    return consulEnabled;
  }

  @Override
  public boolean kafkaEnabled() {
    return kafkaEnabled;
  }

  @Override
  public String kafkaBootstrap() {
    return kafkaBootstrap;
  }

  @Override
  public long outboxPollSeconds() {
    return outboxPollSeconds;
  }

  public long reservationTtlSeconds() {
    return reservationTtlSeconds;
  }
}
