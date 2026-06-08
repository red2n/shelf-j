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

  String dbUser();

  String dbPassword();

  /** Per-service Postgres schema (database-per-service on a shared instance). */
  String dbSchema();

  boolean consulEnabled();

  String consulHost();

  int consulPort();

  boolean kafkaEnabled();

  String kafkaBootstrap();

  /** Outbox drain interval in seconds. */
  long outboxPollSeconds();
}
