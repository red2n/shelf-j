package com.shelfj.iam.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Typed config for iam-svc (MicroProfile Config; overridden by env / config service). */
@ApplicationScoped
public class ServiceConfig {

    @Inject @ConfigProperty(name = "shelfj.service.name", defaultValue = "iam-svc")
    String serviceName;

    @Inject @ConfigProperty(name = "server.port", defaultValue = "8001")
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

    // --- JWT (HS256 dev secret; production uses RS256 keys from a secret store) ---
    @Inject @ConfigProperty(name = "shelfj.jwt.issuer", defaultValue = "shelfj")
    String jwtIssuer;

    @Inject @ConfigProperty(name = "shelfj.jwt.secret", defaultValue = "dev-only-hmac-secret-change-me-please-32+chars")
    String jwtSecret;

    @Inject @ConfigProperty(name = "shelfj.jwt.access-ttl-seconds", defaultValue = "900")
    long accessTtlSeconds;

    @Inject @ConfigProperty(name = "shelfj.jwt.refresh-ttl-seconds", defaultValue = "1209600")
    long refreshTtlSeconds;

    public String serviceName() { return serviceName; }
    public int servicePort() { return servicePort; }
    public String dbUrl() { return dbUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    public String consulHost() { return consulHost; }
    public int consulPort() { return consulPort; }
    public boolean consulEnabled() { return consulEnabled; }
    public String jwtIssuer() { return jwtIssuer; }
    public String jwtSecret() { return jwtSecret; }
    public long accessTtlSeconds() { return accessTtlSeconds; }
    public long refreshTtlSeconds() { return refreshTtlSeconds; }
}
