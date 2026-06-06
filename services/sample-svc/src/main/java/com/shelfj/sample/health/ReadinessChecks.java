package com.shelfj.sample.health;

import java.sql.Connection;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe — "can this service serve traffic RIGHT NOW?" (README §7.8, §13.2).
 *
 * <p>Checks the real dependency (database connectivity). When the DB is down, readiness reports DOWN, so the
 * orchestrator routes no traffic here but does NOT kill the pod — the service keeps running and flips back to
 * ready when the DB returns. This is what lets services start in any order.</p>
 */
@Readiness
@ApplicationScoped
public class ReadinessChecks implements HealthCheck {

    @Inject
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        try (Connection c = dataSource.getConnection()) {
            boolean ok = c.isValid(2);
            return HealthCheckResponse.named("database")
                    .status(ok)
                    .withData("checked", "connection.isValid")
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("database")
                    .down()
                    .withData("error", String.valueOf(e.getMessage()))
                    .build();
        }
    }
}
