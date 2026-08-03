package com.shelfj.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.Set;
import javax.sql.DataSource;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * Shared health probes: liveness = process alive; readiness = DB reachable (so services start in
 * any order — docs/ARCHITECTURE.md §17). Liveness deliberately does NOT check the DB (a DB outage
 * must not get the pod killed).
 */
public final class HealthChecks {

  private HealthChecks() {}

  /** Liveness probe: always UP once the CDI container is up — never checks external deps. */
  @Liveness
  @ApplicationScoped
  public static class ProcessLiveness implements HealthCheck {
    @Inject ServiceSettings settings;

    /**
     * @return an UP response named after {@link ServiceSettings#serviceName()}
     */
    @Override
    public HealthCheckResponse call() {
      return HealthCheckResponse.up(settings.serviceName());
    }
  }

  /** Readiness probe: DOWN when the DB pool can't produce a valid connection. */
  @Readiness
  @ApplicationScoped
  public static class DatabaseReadiness implements HealthCheck {
    @Inject DataSource dataSource;

    /**
     * @return {@code "database"} UP if a pooled connection can be acquired and passes {@link
     *     Connection#isValid(int)} within 2 seconds; otherwise DOWN with the failure message
     *     attached as data (server-side diagnostic only — this response is not client-facing API
     *     output, so it's exempt from the "never leak" rule that applies to {@link
     *     com.shelfj.web.ApiResponse})
     */
    @Override
    public HealthCheckResponse call() {
      try (Connection c = dataSource.getConnection()) {
        return HealthCheckResponse.named("database").status(c.isValid(2)).build();
      } catch (Exception e) {
        return HealthCheckResponse.named("database")
            .down()
            .withData("error", String.valueOf(e.getMessage()))
            .build();
      }
    }
  }

  /**
   * A consumer that fails to start (bad bootstrap config, broker unreachable, etc.) must not leave
   * the service silently "ready" while it never processes another event — see {@link
   * KafkaConsumerRegistry}.
   */
  @Readiness
  @ApplicationScoped
  public static class KafkaConsumerReadiness implements HealthCheck {

    /**
     * @return {@code "kafka-consumers"} UP if {@link KafkaConsumerRegistry#failedConsumers()} is
     *     empty; otherwise DOWN with the failed consumer names attached as data
     */
    @Override
    public HealthCheckResponse call() {
      Set<String> failed = KafkaConsumerRegistry.failedConsumers();
      if (failed.isEmpty()) {
        return HealthCheckResponse.named("kafka-consumers").up().build();
      }
      return HealthCheckResponse.named("kafka-consumers")
          .down()
          .withData("failed", String.join(",", failed))
          .build();
    }
  }
}
