package com.shelfj.service;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Shared MicroProfile Config fields for the 9 properties that are identical across every service.
 * Each service's concrete {@code ServiceConfig} extends this and declares only its three unique
 * fields: {@code serviceName}, {@code servicePort}, and {@code dbSchema}.
 *
 * <p>CDI injects superclass fields via reflection, so the {@code @Inject} annotations here are
 * honoured when the concrete subclass bean is resolved.
 */
public abstract class BaseServiceConfig implements ServiceSettings {

  @Inject
  @ConfigProperty(name = "shelfj.db.url", defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
  String dbUrl;

  @Inject
  @ConfigProperty(
      name = "shelfj.db.migration-url",
      defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
  String dbMigrationUrl;

  @Inject
  @ConfigProperty(name = "shelfj.db.user", defaultValue = "shelfj")
  String dbUser;

  @Inject
  @ConfigProperty(name = "shelfj.db.password", defaultValue = "shelfj_dev_change_me")
  String dbPassword;

  @Inject
  @ConfigProperty(name = "shelfj.db.pool-max-size", defaultValue = "10")
  int dbPoolMaxSize;

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

  @Override
  public String dbUrl() {
    return dbUrl;
  }

  @Override
  public String dbMigrationUrl() {
    return dbMigrationUrl;
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
  public int dbPoolMaxSize() {
    return dbPoolMaxSize;
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
}
