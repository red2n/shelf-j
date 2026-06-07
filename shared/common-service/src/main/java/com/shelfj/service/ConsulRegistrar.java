package com.shelfj.service;

import com.shelfj.discovery.ConsulClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Self-registers the service with Consul on startup and deregisters on shutdown (golden rule #4). Shared so each
 * service no longer hand-writes this. The advertise host comes from {@code SHELFJ_ADVERTISE_HOST} (set per
 * container in compose), defaulting to localhost for host-run dev.
 */
@ApplicationScoped
public class ConsulRegistrar {

    @Inject
    ServiceSettings settings;

    private ConsulClient consul;
    private String serviceId;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        if (!settings.consulEnabled()) {
            return;
        }
        this.consul = new ConsulClient(settings.consulHost(), settings.consulPort());
        this.serviceId = consul.register(settings.serviceName(), advertiseHost(), settings.servicePort());
    }

    @PreDestroy
    void onStop() {
        if (consul != null && serviceId != null) {
            consul.deregister(serviceId);
        }
    }

    private static String advertiseHost() {
        String host = System.getenv("SHELFJ_ADVERTISE_HOST");
        return (host == null || host.isBlank()) ? "localhost" : host;
    }
}
