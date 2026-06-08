package com.shelfj.discovery;

import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import io.helidon.webclient.api.WebClient;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

/**
 * Reusable client for the Consul HTTP API: self-register, deregister, and look up healthy instances.
 *
 * <p>This is the one place Consul registration/discovery logic lives (golden rule #4 — discover, don't hardcode).
 * Services use it to register themselves; the gateway uses it to resolve upstreams. There is no Helidon-native
 * Consul integration, so we call the Consul agent HTTP API directly via Helidon WebClient.</p>
 */
public class ConsulClient implements ServiceRegistry {

    private static final Logger LOG = System.getLogger(ConsulClient.class.getName());

    private final WebClient webClient;
    private final String consulBaseUri;

    public ConsulClient(String consulHost, int consulPort) {
        this.consulBaseUri = "http://" + consulHost + ":" + consulPort;
        this.webClient = WebClient.builder().baseUri(consulBaseUri).build();
    }

    /**
     * Register this service instance with Consul, including an HTTP health check against its readiness probe so
     * Consul only advertises the instance while it is ready.
     *
     * @return the registered service id (use it to deregister)
     */
    public String register(String serviceName, String advertiseHost, int port) {
        String serviceId = serviceName + "-" + port;
        String body = """
                {
                  "ID": "%s",
                  "Name": "%s",
                  "Address": "%s",
                  "Port": %d,
                  "Check": {
                    "HTTP": "http://%s:%d/health/ready",
                    "Interval": "10s",
                    "DeregisterCriticalServiceAfter": "1m"
                  }
                }
                """.formatted(serviceId, serviceName, advertiseHost, port, advertiseHost, port);
        try {
            webClient.put("/v1/agent/service/register").submit(body);
            LOG.log(Level.INFO, "Registered with Consul as {0} ({1}:{2})", serviceId, advertiseHost, port);
        } catch (Exception e) {
            // Non-fatal: the service still runs; it just isn't discoverable until Consul is reachable.
            LOG.log(Level.WARNING, "Consul registration failed (running unregistered): " + e.getMessage());
        }
        return serviceId;
    }

    /** Deregister a previously-registered service id (best effort). */
    public void deregister(String serviceId) {
        if (serviceId == null) {
            return;
        }
        try {
            webClient.put("/v1/agent/service/deregister/" + serviceId).request();
            LOG.log(Level.INFO, "Deregistered {0} from Consul", serviceId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Consul deregistration failed: " + e.getMessage());
        }
    }

    /** All currently-healthy ("passing") instances of a service. */
    public List<ServiceInstance> healthyInstances(String serviceName) {
        List<ServiceInstance> out = new ArrayList<>();
        try {
            // NOTE: pass the filter via queryParam — embedding "?passing=true" in the path makes Helidon's
            // WebClient URL-encode the '?' into the path, so Consul returns an empty list.
            String json = webClient.get("/v1/health/service/" + serviceName)
                    .queryParam("passing", "true")
                    .requestEntity(String.class);
            try (var reader = Json.createReader(new StringReader(json))) {
                JsonArray entries = reader.readArray();
                for (int i = 0; i < entries.size(); i++) {
                    JsonObject svc = entries.getJsonObject(i).getJsonObject("Service");
                    JsonObject node = entries.getJsonObject(i).getJsonObject("Node");
                    String address = svc.getString("Address", "");
                    if (address.isBlank() && node != null) {
                        address = node.getString("Address", "");
                    }
                    int port = svc.getInt("Port", 0);
                    if (!address.isBlank() && port > 0) {
                        out.add(new ServiceInstance(serviceName, address, port));
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Consul lookup for {0} failed: {1}", serviceName, e.getMessage());
        }
        return out;
    }

    /** One healthy instance of a service, chosen at random (basic client-side load balancing). */
    public Optional<ServiceInstance> resolve(String serviceName) {
        List<ServiceInstance> instances = healthyInstances(serviceName);
        if (instances.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(instances.get(ThreadLocalRandom.current().nextInt(instances.size())));
    }
}
