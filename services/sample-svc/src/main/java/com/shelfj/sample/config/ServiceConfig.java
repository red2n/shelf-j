package com.shelfj.sample.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed access to this service's configuration, sourced via MicroProfile Config.
 *
 * <p>Values come from {@code microprofile-config.properties} (defaults) and are overridden by environment
 * variables / the central config service in real deployments (golden rule #5 — config is external, no secrets in code).</p>
 */
@ApplicationScoped
public class ServiceConfig {

    @Inject @ConfigProperty(name = "shelfj.service.name", defaultValue = "sample-svc")
    String serviceName;

    @Inject @ConfigProperty(name = "server.port", defaultValue = "8000")
    int servicePort;

    @Inject @ConfigProperty(name = "shelfj.db.url", defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
    String dbUrl;

    @Inject @ConfigProperty(name = "shelfj.db.user", defaultValue = "shelfj")
    String dbUser;

    @Inject @ConfigProperty(name = "shelfj.db.password", defaultValue = "shelfj_dev_change_me")
    String dbPassword;

    @Inject @ConfigProperty(name = "shelfj.consul.host", defaultValue = "localhost")
    String consulHost;

    @Inject @ConfigProperty(name = "shelfj.consul.port", defaultValue = "8500")
    int consulPort;

    @Inject @ConfigProperty(name = "shelfj.consul.enabled", defaultValue = "true")
    boolean consulEnabled;

    public String serviceName() { return serviceName; }
    public int servicePort() { return servicePort; }
    public String dbUrl() { return dbUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    public String consulHost() { return consulHost; }
    public int consulPort() { return consulPort; }
    public boolean consulEnabled() { return consulEnabled; }
}
