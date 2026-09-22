package com.storeql.iam.config;

import com.storeql.service.BaseServiceConfig;
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
  @ConfigProperty(name = "storeql.service.name", defaultValue = "iam-svc")
  String serviceName;

  @Inject
  @ConfigProperty(name = "server.port", defaultValue = "8001")
  int servicePort;

  @Inject
  @ConfigProperty(name = "storeql.db.schema", defaultValue = "iam")
  String dbSchema;

  // --- JWT: RS256 (20.15). storeql.jwt.secret seals the signing keys at rest; it mints nothing ---
  @Inject
  @ConfigProperty(name = "storeql.jwt.issuer", defaultValue = "storeql")
  String jwtIssuer;

  /**
   * No default on purpose: a missing secret must fail deployment, never silently fall back to a
   * publicly known value. Local dev supplies it via docker-compose / .env (golden rule #5).
   */
  @Inject
  @ConfigProperty(name = "storeql.jwt.secret")
  String jwtSecret;

  /** How old the signing key may grow before it is rotated by itself (20.15). */
  @Inject
  @ConfigProperty(name = "storeql.jwt.rotation-days", defaultValue = "90")
  int jwtRotationDays;

  /**
   * How long a rotated key stays published: at least as long as a token it signed can live (20.15).
   */
  @Inject
  @ConfigProperty(name = "storeql.jwt.retire-after-seconds", defaultValue = "3600")
  long jwtRetireAfterSeconds;

  /**
   * How long a rotated key is published before it signs anything (20.15): the time verifiers that
   * re-read the key set on a schedule — the MQTT broker — need to have seen it. The previous key
   * signs until then.
   */
  @Inject
  @ConfigProperty(name = "storeql.jwt.publish-lead-seconds", defaultValue = "60")
  long jwtPublishLeadSeconds;

  @Inject
  @ConfigProperty(name = "storeql.jwt.access-ttl-seconds", defaultValue = "900")
  long accessTtlSeconds;

  @Inject
  @ConfigProperty(name = "storeql.jwt.refresh-ttl-seconds", defaultValue = "1209600")
  long refreshTtlSeconds;

  // --- Second factors (20.12) ---

  /** The name an authenticator app shows beside the code. */
  @Inject
  @ConfigProperty(name = "storeql.mfa.issuer", defaultValue = "StoreQL")
  String mfaIssuer;

  /**
   * The WebAuthn relying party id: the domain the app is served from, without scheme or port. A
   * passkey made for one id is no use under another, which is what makes it phishing-resistant.
   */
  @Inject
  @ConfigProperty(name = "storeql.mfa.rp-id", defaultValue = "localhost")
  String mfaRpId;

  /** The origins the app is served from, exactly as a browser reports them, comma-separated. */
  @Inject
  @ConfigProperty(
      name = "storeql.mfa.origins",
      defaultValue = "http://localhost:8088,http://localhost:40015")
  String mfaOrigins;

  /** Whether a platform administrator must have a second factor: on unless a deployment says no. */
  @Inject
  @ConfigProperty(name = "storeql.mfa.platform-admin-required", defaultValue = "true")
  boolean mfaPlatformAdminRequired;

  /** How long a sign-in may wait for its second factor. */
  @Inject
  @ConfigProperty(name = "storeql.mfa.challenge-ttl-seconds", defaultValue = "300")
  long mfaChallengeTtlSeconds;

  /** Wrong answers a waiting sign-in survives before the password has to be given again. */
  @Inject
  @ConfigProperty(name = "storeql.mfa.max-attempts", defaultValue = "5")
  int mfaMaxAttempts;

  /**
   * Wrong answers, over every sign-in of one login, that lock its second factor for the window
   * below: the password buys five guesses at a time, and this is what stops it being bought for
   * ever (NIST SP 800-63B-4 asks for no more than a hundred consecutive failures).
   */
  @Inject
  @ConfigProperty(name = "storeql.mfa.lockout-failures", defaultValue = "20")
  int mfaLockoutFailures;

  @Inject
  @ConfigProperty(name = "storeql.mfa.lockout-window-seconds", defaultValue = "900")
  long mfaLockoutWindowSeconds;

  // --- Single sign-on (20.x, SSO over OpenID Connect) ---

  /**
   * Where a business's identity provider sends the browser back: this service's callback as the
   * outside world reaches it, through the gateway. The address a business registers with its
   * provider. Unset, single sign-on is unavailable on this deployment.
   */
  @Inject
  @ConfigProperty(name = "storeql.sso.callback-url")
  java.util.Optional<String> ssoCallbackUrl;

  /** The app origins a finished sign-in may return to, comma-separated: nowhere else. */
  @Inject
  @ConfigProperty(
      name = "storeql.sso.return-origins",
      defaultValue = "http://localhost:8088,http://localhost:40015")
  String ssoReturnOrigins;

  /**
   * Hosts this service may reach over plain HTTP and at a private address, comma-separated: a
   * provider on the deployment's own network, as in local development. Empty in production, where a
   * provider is HTTPS on a public address — the issuer is typed by a business, and this service
   * sits inside the cluster.
   */
  @Inject
  @ConfigProperty(name = "storeql.sso.insecure-hosts", defaultValue = " ")
  String ssoInsecureHosts;

  /** How long a sign-in may spend at the provider. */
  @Inject
  @ConfigProperty(name = "storeql.sso.flow-ttl-seconds", defaultValue = "600")
  long ssoFlowTtlSeconds;

  /** How long the app has to redeem the ticket the provider's return left it. */
  @Inject
  @ConfigProperty(name = "storeql.sso.ticket-ttl-seconds", defaultValue = "120")
  long ssoTicketTtlSeconds;

  /**
   * How long a session the provider vouched for is renewed before the provider is asked again: a
   * working day. What makes switching someone off at the provider switch them off here.
   */
  @Inject
  @ConfigProperty(name = "storeql.sso.session-max-seconds", defaultValue = "43200")
  long ssoSessionMaxSeconds;

  /** How long a provider has to answer one request. */
  @Inject
  @ConfigProperty(name = "storeql.sso.http-timeout-seconds", defaultValue = "5")
  long ssoHttpTimeoutSeconds;

  public java.util.Optional<String> ssoCallbackUrl() {
    return ssoCallbackUrl == null
        ? java.util.Optional.empty()
        : ssoCallbackUrl.map(String::trim).filter(s -> !s.isEmpty());
  }

  /** In the order configured: the first is where a sign-in nobody can place is sent back to. */
  public java.util.List<String> ssoReturnOrigins() {
    return java.util.List.copyOf(csv(ssoReturnOrigins));
  }

  public java.util.Set<String> ssoInsecureHosts() {
    return csv(ssoInsecureHosts).stream()
        .map(h -> h.toLowerCase(java.util.Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public long ssoFlowTtlSeconds() {
    return ssoFlowTtlSeconds;
  }

  public long ssoTicketTtlSeconds() {
    return ssoTicketTtlSeconds;
  }

  public long ssoSessionMaxSeconds() {
    return ssoSessionMaxSeconds;
  }

  public long ssoHttpTimeoutSeconds() {
    return ssoHttpTimeoutSeconds;
  }

  private static java.util.SequencedSet<String> csv(String value) {
    java.util.SequencedSet<String> out = new java.util.LinkedHashSet<>();
    if (value == null) return out;
    java.util.Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(o -> !o.isEmpty())
        .forEach(out::add);
    return out;
  }

  public int mfaLockoutFailures() {
    return mfaLockoutFailures;
  }

  public long mfaLockoutWindowSeconds() {
    return mfaLockoutWindowSeconds;
  }

  public String mfaIssuer() {
    return mfaIssuer;
  }

  public String mfaRpId() {
    return mfaRpId;
  }

  public java.util.Set<String> mfaOrigins() {
    return java.util.Arrays.stream(mfaOrigins.split(","))
        .map(String::trim)
        .filter(o -> !o.isEmpty())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public boolean mfaPlatformAdminRequired() {
    return mfaPlatformAdminRequired;
  }

  public long mfaChallengeTtlSeconds() {
    return mfaChallengeTtlSeconds;
  }

  public int mfaMaxAttempts() {
    return mfaMaxAttempts;
  }

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
   * @return the configured issuer, {@code storeql} unless overridden
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
