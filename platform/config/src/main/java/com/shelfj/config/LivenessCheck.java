package com.shelfj.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * Health for the config service. It has no external dependency in Phase 0 (reads local files), so liveness and
 * readiness are both simply "process is serving".
 */
@ApplicationScoped
public class LivenessCheck {

    @Liveness
    @ApplicationScoped
    public static class Live implements HealthCheck {
        @Override
        public HealthCheckResponse call() {
            return HealthCheckResponse.up("config-svc");
        }
    }

    @Readiness
    @ApplicationScoped
    public static class Ready implements HealthCheck {
        @Override
        public HealthCheckResponse call() {
            return HealthCheckResponse.up("config-svc");
        }
    }
}
