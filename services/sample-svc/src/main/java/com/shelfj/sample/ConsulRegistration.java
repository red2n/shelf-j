package com.shelfj.sample;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import com.shelfj.sample.config.ServiceConfig;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Self-registers the service with Consul on startup and deregisters on shutdown (golden rule #4 — discover, don't
 * hardcode). Uses the Consul HTTP API directly via Helidon WebClient (no third-party Consul client needed).
 *
 * <p>Consul registration includes an HTTP health check pointing at {@code /health/ready}, so Consul/the gateway only
 * route to this instance while it is ready.</p>
 */
@ApplicationScoped
public class ConsulRegistration {

    private static final Logger LOG = System.getLogger(ConsulRegistration.class.getName());

    @Inject
    ServiceConfig config;

    private String serviceId;

    void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        if (!config.consulEnabled()) {
            LOG.log(Level.INFO, "Consul registration disabled");
            return;
        }
        this.serviceId = config.serviceName() + "-" + config.servicePort();
        String consulBase = "http://" + config.consulHost() + ":" + config.consulPort();
        String body = """
                {
                  "ID": "%s",
                  "Name": "%s",
                  "Port": %d,
                  "Check": {
                    "HTTP": "http://%s:%d/health/ready",
                    "Interval": "10s",
                    "DeregisterCriticalServiceAfter": "1m"
                  }
                }
                """.formatted(serviceId, config.serviceName(), config.servicePort(),
                hostForCheck(), config.servicePort());
        try {
            WebClient.builder().baseUri(consulBase).build()
                    .put("/v1/agent/service/register")
                    .submit(body);
            LOG.log(Level.INFO, "Registered with Consul as {0}", serviceId);
        } catch (Exception e) {
            // Non-fatal: the service still runs; it just isn't discoverable until Consul is reachable.
            LOG.log(Level.WARNING, "Consul registration failed (will run unregistered): " + e.getMessage());
        }
    }

    @PreDestroy
    void onStop() {
        if (serviceId == null) {
            return;
        }
        String consulBase = "http://" + config.consulHost() + ":" + config.consulPort();
        try {
            WebClient.builder().baseUri(consulBase).build()
                    .put("/v1/agent/service/deregister/" + serviceId)
                    .request();
            LOG.log(Level.INFO, "Deregistered {0} from Consul", serviceId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Consul deregistration failed: " + e.getMessage());
        }
    }

    /** Host Consul should call for the health check. In containers this is the service's hostname; locally, localhost. */
    private String hostForCheck() {
        String host = System.getenv("SHELFJ_ADVERTISE_HOST");
        return (host == null || host.isBlank()) ? "localhost" : host;
    }
}
