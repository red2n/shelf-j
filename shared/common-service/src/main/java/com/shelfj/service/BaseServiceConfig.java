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

  /**
   * JDBC URL the application pool ({@link DataSourceProducer}) connects to for normal traffic — may
   * point at PgBouncer. Property: {@code shelfj.db.url}. Default: local-dev Postgres on {@code
   * localhost:5432/shelfj}. Misconfiguring this surfaces as {@link HealthChecks.DatabaseReadiness}
   * reporting DOWN, not a startup crash.
   */
  @Inject
  @ConfigProperty(name = "shelfj.db.url", defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
  String dbUrl;

  /**
   * JDBC URL {@link FlywayRunner} migrates against — see {@link ServiceSettings#dbMigrationUrl()}
   * for why this can differ from {@link #dbUrl}. Property: {@code shelfj.db.migration-url}.
   * Default: same local-dev Postgres as {@link #dbUrl}. On failure to connect, {@link FlywayRunner}
   * logs a warning and defers rather than failing startup.
   */
  @Inject
  @ConfigProperty(
      name = "shelfj.db.migration-url",
      defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
  String dbMigrationUrl;

  /** Postgres user for both the app pool and Flyway. Property: {@code shelfj.db.user}. */
  @Inject
  @ConfigProperty(name = "shelfj.db.user", defaultValue = "shelfj")
  String dbUser;

  /**
   * Postgres password for both the app pool and Flyway. Property: {@code shelfj.db.password}. The
   * default is a local-dev-only placeholder — production deployments MUST override this via
   * external config/secrets (golden rule #5); never bake a real credential into an image.
   */
  @Inject
  @ConfigProperty(name = "shelfj.db.password", defaultValue = "shelfj_dev_change_me")
  String dbPassword;

  /**
   * Max HikariCP pool size for this service instance ({@link DataSourceProducer}). Property: {@code
   * shelfj.db.pool-max-size}. Too high risks exhausting Postgres's {@code max_connections} across
   * replicas; too low serializes concurrent requests waiting on a connection.
   */
  @Inject
  @ConfigProperty(name = "shelfj.db.pool-max-size", defaultValue = "10")
  int dbPoolMaxSize;

  /** Consul agent host used by {@link ConsulRegistrar}. Property: {@code shelfj.consul.host}. */
  @Inject
  @ConfigProperty(name = "shelfj.consul.host", defaultValue = "localhost")
  String consulHost;

  /** Consul agent port used by {@link ConsulRegistrar}. Property: {@code shelfj.consul.port}. */
  @Inject
  @ConfigProperty(name = "shelfj.consul.port", defaultValue = "8500")
  int consulPort;

  /**
   * Whether {@link ConsulRegistrar} registers/deregisters this service. Property: {@code
   * shelfj.consul.enabled}. Set {@code false} only for isolated tests that don't need discovery —
   * production and normal dev always register (golden rule #4).
   */
  @Inject
  @ConfigProperty(name = "shelfj.consul.enabled", defaultValue = "true")
  boolean consulEnabled;

  /**
   * Whether Kafka consumers ({@link BaseKafkaConsumer}) and the {@link OutboxPublisher} start.
   * Property: {@code shelfj.kafka.enabled}. When {@code false}, both no-op at startup instead of
   * attempting to connect — used by tests/tools that don't need eventing.
   */
  @Inject
  @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
  boolean kafkaEnabled;

  /**
   * Kafka bootstrap servers, e.g. {@code "kafka:9092"}. Property: {@code shelfj.kafka.bootstrap}.
   * Used by both the outbox producer and every consumer's {@link KafkaEventLoop}. An unreachable
   * broker does not fail startup — the producer/consumer retry on their own schedules and {@link
   * HealthChecks.KafkaConsumerReadiness} reflects consumer start failures.
   */
  @Inject
  @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
  String kafkaBootstrap;

  /**
   * Seconds between {@link OutboxPublisher} drain ticks. Property: {@code
   * shelfj.outbox.poll-seconds}. Lower values reduce event-publish latency at the cost of more
   * frequent {@code FOR UPDATE SKIP LOCKED} polling queries.
   */
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
