package com.shelfj.sample.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe — "is the process alive / not deadlocked?" (README §7.8).
 *
 * <p>Deliberately does NOT check external dependencies: a DB outage must not cause the orchestrator to kill and
 * restart the pod (that's readiness's job). Liveness fails only on unrecoverable internal state.</p>
 */
@Liveness
@ApplicationScoped
public class LivenessChecks implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("sample-svc");
    }
}
