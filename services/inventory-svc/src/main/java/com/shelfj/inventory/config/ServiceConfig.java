package com.shelfj.inventory.config;

import com.shelfj.service.ServiceSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Typed config for inventory-svc. Provides {@link ServiceSettings} for the shared service infrastructure. */
@ApplicationScoped
public class ServiceConfig implements ServiceSettings {

    @Inject @ConfigProperty(name = "shelfj.service.name", defaultValue = "inventory-svc")
    String serviceName;
    @Inject @ConfigProperty(name = "server.port", defaultValue = "8004")
    int servicePort;
    @Inject @ConfigProperty(name = "shelfj.db.url", defaultValue = "jdbc:postgresql://localhost:5432/shelfj")
    String dbUrl;
    @Inject @ConfigProperty(name = "shelfj.db.user", defaultValue = "shelfj")
    String dbUser;
    @Inject @ConfigProperty(name = "shelfj.db.password", defaultValue = "shelfj_dev_change_me")
    String dbPassword;
    @Inject @ConfigProperty(name = "shelfj.db.schema", defaultValue = "inventory")
    String dbSchema;
    @Inject @ConfigProperty(name = "shelfj.consul.host", defaultValue = "localhost")
    String consulHost;
    @Inject @ConfigProperty(name = "shelfj.consul.port", defaultValue = "8500")
    int consulPort;
    @Inject @ConfigProperty(name = "shelfj.consul.enabled", defaultValue = "true")
    boolean consulEnabled;
    @Inject @ConfigProperty(name = "shelfj.kafka.enabled", defaultValue = "true")
    boolean kafkaEnabled;
    @Inject @ConfigProperty(name = "shelfj.kafka.bootstrap", defaultValue = "localhost:9092")
    String kafkaBootstrap;
    @Inject @ConfigProperty(name = "shelfj.outbox.poll-seconds", defaultValue = "5")
    long outboxPollSeconds;

    /** Default reservation lifetime if the caller doesn't specify one (abandoned-cart release). */
    @Inject @ConfigProperty(name = "shelfj.inventory.reservation-ttl-seconds", defaultValue = "900")
    long reservationTtlSeconds;

    public String serviceName() { return serviceName; }
    public int servicePort() { return servicePort; }
    public String dbUrl() { return dbUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    public String dbSchema() { return dbSchema; }
    public String consulHost() { return consulHost; }
    public int consulPort() { return consulPort; }
    public boolean consulEnabled() { return consulEnabled; }
    public boolean kafkaEnabled() { return kafkaEnabled; }
    public String kafkaBootstrap() { return kafkaBootstrap; }
    public long outboxPollSeconds() { return outboxPollSeconds; }
    public long reservationTtlSeconds() { return reservationTtlSeconds; }
}
