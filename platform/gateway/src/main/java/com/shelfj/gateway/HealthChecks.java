package com.shelfj.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * Gateway health. Liveness = process alive. Readiness = serving (the gateway degrades gracefully per-route when an
 * upstream is missing, so it stays ready even if a downstream is down).
 */
public class HealthChecks {

    @Liveness
    @ApplicationScoped
    public static class Live implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("gateway");
        }
    }

    @Readiness
    @ApplicationScoped
    public static class Ready implements HealthCheck {
        @Override public HealthCheckResponse call() {
            return HealthCheckResponse.up("gateway");
        }
    }
}
