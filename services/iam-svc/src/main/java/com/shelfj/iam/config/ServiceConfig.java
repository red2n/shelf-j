package com.shelfj.iam.config;

import com.shelfj.service.ServiceSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Typed config for iam-svc (MicroProfile Config; overridden by env / config service). */
@ApplicationScoped
public class ServiceConfig implements ServiceSettings {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "iam-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8001")
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

  // Database-per-service on a shared Postgres = a dedicated schema per service (golden rule #1).
  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "iam")
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

  // --- JWT (HS256 dev secret; production uses RS256 keys from a secret store) ---
  @Inject
  @ConfigProperty(name = "shelfj.jwt.issuer", defaultValue = "shelfj")
  String jwtIssuer;

  @Inject
  @ConfigProperty(
      name = "shelfj.jwt.secret",
      defaultValue = "dev-only-hmac-secret-change-me-please-32+chars")
  String jwtSecret;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.access-ttl-seconds", defaultValue = "900")
  long accessTtlSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.refresh-ttl-seconds", defaultValue = "1209600")
  long refreshTtlSeconds;

  // --- Kafka / outbox (for the shared OutboxPublisher) ---
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

  public String jwtIssuer() {
    return jwtIssuer;
  }

  public String jwtSecret() {
    return jwtSecret;
  }

  public long accessTtlSeconds() {
    return accessTtlSeconds;
  }

  public long refreshTtlSeconds() {
    return refreshTtlSeconds;
  }
}
