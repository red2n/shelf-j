package com.shelfj.service;

/**
 * Configuration the shared service infrastructure needs. Each service provides ONE CDI bean
 * implementing this (typically its {@code ServiceConfig}), and the shared beans (DataSource,
 * Flyway, Consul, outbox publisher) read from it. This is the seam that lets all the per-service
 * boilerplate live here once.
 */
public interface ServiceSettings {

  /**
   * Short service name (e.g. {@code "inventory-svc"}); used for Consul registration, the HikariCP
   * pool name, and log/thread name prefixes.
   */
  String serviceName();

  /**
   * Port this service instance listens on. Local-dev only detail — in production every service
   * listens on 8080 and is addressed via Consul/k8s DNS, never {@code host:port}.
   */
  int servicePort();

  /** JDBC URL the application connection pool connects to. */
  String dbUrl();

  /**
   * JDBC URL Flyway migrates against. Defaults to {@link #dbUrl()}, but a service can point this
   * directly at Postgres while {@link #dbUrl()} points at PgBouncer — Flyway's multi-statement DDL
   * transactions need full session affinity, which PgBouncer's {@code transaction} pool_mode does
   * not guarantee.
   */
  default String dbMigrationUrl() {
    return dbUrl();
  }

  /** Postgres user for both the application pool and Flyway migrations. */
  String dbUser();

  /** Postgres password for both the application pool and Flyway migrations. */
  String dbPassword();

  /** Per-service Postgres schema (database-per-service on a shared instance). */
  String dbSchema();

  /** Max pooled DB connections for this service instance. */
  default int dbPoolMaxSize() {
    return 10;
  }

  /** Whether {@link ConsulRegistrar} should register/deregister this service. */
  boolean consulEnabled();

  /**
   * Consul agent host, e.g. {@code "consul"} in compose or {@code "localhost"} for host-run dev.
   */
  String consulHost();

  /** Consul agent port, typically {@code 8500}. */
  int consulPort();

  /** Whether Kafka consumers and the {@link OutboxPublisher} should start. */
  boolean kafkaEnabled();

  /** Kafka bootstrap servers, e.g. {@code "kafka:9092"}. */
  String kafkaBootstrap();

  /** Outbox drain interval in seconds. */
  long outboxPollSeconds();
}
