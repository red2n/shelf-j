package com.shelfj.iam;

import com.shelfj.discovery.ConsulClient;
import com.shelfj.iam.config.ServiceConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/** Self-registers iam-svc with Consul on startup; deregisters on shutdown. Uses the shared ConsulClient. */
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

    private String advertiseHost() {
        String host = System.getenv("SHELFJ_ADVERTISE_HOST");
        return (host == null || host.isBlank()) ? "localhost" : host;
    }
}
