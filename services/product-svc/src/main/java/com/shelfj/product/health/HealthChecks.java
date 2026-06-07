package com.shelfj.product.health;

import java.sql.Connection;
import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

public class HealthChecks {

    @Liveness
    @ApplicationScoped
    public static class Live implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("product-svc");
        }
    }

    @Readiness
    @ApplicationScoped
    public static class Ready implements HealthCheck {
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
