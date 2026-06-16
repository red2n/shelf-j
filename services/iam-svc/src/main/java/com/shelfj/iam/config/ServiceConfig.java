package com.shelfj.iam.config;

import com.shelfj.service.BaseServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Typed config for iam-svc — extends {@link BaseServiceConfig} for the 9 common properties. Adds
 * JWT settings specific to the identity service.
 */
@ApplicationScoped
public class ServiceConfig extends BaseServiceConfig {

  @Inject
  @ConfigProperty(name = "shelfj.service.name", defaultValue = "iam-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8001")
  int servicePort;

  @Inject
  @ConfigProperty(name = "shelfj.db.schema", defaultValue = "iam")
  String dbSchema;

  // --- JWT (HS256 dev secret; production uses RS256 keys from a secret store) ---
  @Inject
  @ConfigProperty(name = "shelfj.jwt.issuer", defaultValue = "shelfj")
  String jwtIssuer;

  /**
   * No default on purpose: a missing secret must fail deployment, never silently fall back to a
   * publicly known value. Local dev supplies it via docker-compose / .env (golden rule #5).
   */
  @Inject
  @ConfigProperty(name = "shelfj.jwt.secret")
  String jwtSecret;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.access-ttl-seconds", defaultValue = "900")
  long accessTtlSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.refresh-ttl-seconds", defaultValue = "1209600")
  long refreshTtlSeconds;

  @Override
  public String serviceName() {
    return serviceName;
  }

  @Override
  public int servicePort() {
    return servicePort;
  }

  @Override
  public String dbSchema() {
    return dbSchema;
  }

  public String jwtIssuer() {
    return jwtIssuer;
  }

  public String jwtSecret() {
    return jwtSecret;
  }

  public long accessTtlSeconds() {
    return accessTtlSeconds;
  }

  public long refreshTtlSeconds() {
    return refreshTtlSeconds;
  }
}
