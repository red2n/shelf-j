package com.shelfj.service;

/**
 * Configuration the shared service infrastructure needs. Each service provides ONE CDI bean
 * implementing this (typically its {@code ServiceConfig}), and the shared beans (DataSource,
 * Flyway, Consul, outbox publisher) read from it. This is the seam that lets all the per-service
 * boilerplate live here once.
 */
public interface ServiceSettings {

  String serviceName();

  int servicePort();

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

  String dbUser();

  String dbPassword();

  /** Per-service Postgres schema (database-per-service on a shared instance). */
  String dbSchema();

  /** Max pooled DB connections for this service instance. */
  default int dbPoolMaxSize() {
    return 10;
  }

  boolean consulEnabled();

  String consulHost();

  int consulPort();

  boolean kafkaEnabled();

  String kafkaBootstrap();

  /** Outbox drain interval in seconds. */
  long outboxPollSeconds();
}
