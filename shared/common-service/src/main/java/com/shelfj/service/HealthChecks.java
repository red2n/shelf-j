package com.shelfj.service;

import java.sql.Connection;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * Shared health probes: liveness = process alive; readiness = DB reachable (so services start in any order —
 * README §7.8). Liveness deliberately does NOT check the DB (a DB outage must not get the pod killed).
 */
public final class HealthChecks {

    private HealthChecks() {}

    @Liveness
    @ApplicationScoped
    public static class ProcessLiveness implements HealthCheck {
        @Inject ServiceSettings settings;

        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up(settings.serviceName());
        }
    }

    @Readiness
    @ApplicationScoped
    public static class DatabaseReadiness implements HealthCheck {
        @Inject DataSource dataSource;

        @Override public HealthCheckResponse call() {
            try (Connection c = dataSource.getConnection()) {
                return HealthCheckResponse.named("database").status(c.isValid(2)).build();
            } catch (Exception e) {
                return HealthCheckResponse.named("database").down()
                        .withData("error", String.valueOf(e.getMessage())).build();
            }
        }
    }
}
