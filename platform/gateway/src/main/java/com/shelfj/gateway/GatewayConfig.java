package com.shelfj.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Gateway configuration (Consul location for upstream resolution). */
@ApplicationScoped
public class GatewayConfig {

    @Inject @ConfigProperty(name = "shelfj.consul.host", defaultValue = "localhost")
    String consulHost;

    @Inject @ConfigProperty(name = "shelfj.consul.port", defaultValue = "8500")
    int consulPort;

    public String consulHost() { return consulHost; }
    public int consulPort() { return consulPort; }
}
