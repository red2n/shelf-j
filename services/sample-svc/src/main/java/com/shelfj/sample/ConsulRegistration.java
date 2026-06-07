package com.shelfj.sample;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.sample.config.ServiceConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Self-registers the service with Consul on startup and deregisters on shutdown (golden rule #4 — discover, don't
 * hardcode). Delegates to the shared {@link ConsulClient} so registration logic is written once for all services.
 *
 * <p>Consul registration includes an HTTP health check pointing at {@code /health/ready}, so Consul (and the
 * gateway) only route to this instance while it is ready.</p>
 */
@ApplicationScoped
public class ConsulRegistration {

    @Inject
    ServiceConfig config;

    private ConsulClient consul;
    private String serviceId;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        if (!config.consulEnabled()) {
            return;
        }
        this.consul = new ConsulClient(config.consulHost(), config.consulPort());
        this.serviceId = consul.register(config.serviceName(), advertiseHost(), config.servicePort());
    }

    @PreDestroy
    void onStop() {
        if (consul != null && serviceId != null) {
            consul.deregister(serviceId);
        }
    }

    /** Host Consul should call for the health check. In containers set SHELFJ_ADVERTISE_HOST; locally, localhost. */
    private String advertiseHost() {
        String host = System.getenv("SHELFJ_ADVERTISE_HOST");
        return (host == null || host.isBlank()) ? "localhost" : host;
    }
}
