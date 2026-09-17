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

  // --- JWT: RS256 (20.15). shelfj.jwt.secret seals the signing keys at rest; it mints nothing ---
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

  /** How old the signing key may grow before it is rotated by itself (20.15). */
  @Inject
  @ConfigProperty(name = "shelfj.jwt.rotation-days", defaultValue = "90")
  int jwtRotationDays;

  /**
   * How long a rotated key stays published: at least as long as a token it signed can live (20.15).
   */
  @Inject
  @ConfigProperty(name = "shelfj.jwt.retire-after-seconds", defaultValue = "3600")
  long jwtRetireAfterSeconds;

  /**
   * How long a rotated key is published before it signs anything (20.15): the time verifiers that
   * re-read the key set on a schedule — the MQTT broker — need to have seen it. The previous key
   * signs until then.
   */
  @Inject
  @ConfigProperty(name = "shelfj.jwt.publish-lead-seconds", defaultValue = "60")
  long jwtPublishLeadSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.access-ttl-seconds", defaultValue = "900")
  long accessTtlSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.refresh-ttl-seconds", defaultValue = "1209600")
  long refreshTtlSeconds;

  /**
   * {@inheritDoc}
   *
   * @return the Consul registration name, {@code iam-svc} unless overridden
   */
  @Override
  public String serviceName() {
    return serviceName;
  }

  /**
   * {@inheritDoc}
   *
   * @return the HTTP listen port; the {@code 8001} default is a local-dev convenience only, as
   *     every service listens on 8080 in production
   */
  @Override
  public int servicePort() {
    return servicePort;
  }

  /**
   * {@inheritDoc}
   *
   * @return the Postgres schema this service owns, {@code iam} unless overridden
   */
  @Override
  public String dbSchema() {
    return dbSchema;
  }

  /**
   * The {@code iss} claim stamped on every token this service mints, and required of every token it
   * verifies.
   *
   * @return the configured issuer, {@code shelfj} unless overridden
   */
  public String jwtIssuer() {
    return jwtIssuer;
  }

  /**
   * The HS256 signing secret.
   *
   * <p>Deliberately has no default: a missing secret fails deployment rather than falling back to a
   * publicly known value.
   *
   * @return the configured signing secret
   */
  public int jwtRotationDays() {
    return jwtRotationDays;
  }

  public long jwtRetireAfterSeconds() {
    return jwtRetireAfterSeconds;
  }

  public long jwtPublishLeadSeconds() {
    return jwtPublishLeadSeconds;
  }

  public String jwtSecret() {
    return jwtSecret;
  }

  /**
   * How long a freshly minted access token stays valid.
   *
   * @return the access-token lifetime in seconds, 15 minutes unless overridden
   */
  public long accessTtlSeconds() {
    return accessTtlSeconds;
  }

  /**
   * How long a refresh token stays valid, bounding how long a signed-in session can be renewed
   * without re-authenticating.
   *
   * @return the refresh-token lifetime in seconds, 14 days unless overridden
   */
  public long refreshTtlSeconds() {
    return refreshTtlSeconds;
  }
}
