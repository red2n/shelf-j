package com.shelfj.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Gateway configuration (Consul location for upstream resolution). */
@ApplicationScoped
public class GatewayConfig {

  @Inject
  @ConfigProperty(name = "shelfj.consul.host", defaultValue = "localhost")
  String consulHost;

  @Inject
  @ConfigProperty(name = "shelfj.consul.port", defaultValue = "8500")
  int consulPort;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.rate-limit.enabled", defaultValue = "true")
  boolean rateLimitEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.rate-limit.requests-per-minute", defaultValue = "100")
  int rateLimitRequestsPerMinute;

  /**
   * Services the proxy is allowed to route to. Anything not on this list is 404 even if it is
   * registered in Consul — internal services (config, discovery, observability) must never become
   * internet-reachable just by registering.
   */
  @Inject
  @ConfigProperty(
      name = "shelfj.gateway.routable-services",
      defaultValue =
          "iam-svc,tenant-svc,product-svc,inventory-svc,pricing-svc,cart-svc,order-svc,"
              + "payment-svc,purchase-svc,customer-svc,notification-svc,reporting-svc")
  String routableServices;

  /**
   * Only enable when the gateway runs behind a trusted reverse proxy / LB that overwrites
   * X-Forwarded-For. When false (default) throttling keys on the socket remote address, which a
   * client cannot spoof.
   */
  @Inject
  @ConfigProperty(name = "shelfj.gateway.trust-forwarded-headers", defaultValue = "false")
  boolean trustForwardedHeaders;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.enabled", defaultValue = "true")
  boolean bruteForceEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.max-failures", defaultValue = "5")
  int bruteForceMaxFailures;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.block-minutes", defaultValue = "15")
  int bruteForceBlockMinutes;

  /** Suffix-matched against the request path; must point at the credential-checking endpoint. */
  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.login-path", defaultValue = "/auth/login")
  String bruteForceLoginPath;

  /**
   * Unauthenticated endpoints that accept a secret in the body and answer 404 when it is wrong,
   * comma-separated and suffix-matched like the login path. A wrong secret counts as a failure
   * against the client IP under the same lockout as a wrong password: the opt-out link in a
   * marketing message is a bearer token, and while 256 random bits cannot be guessed, an endpoint
   * that would let someone try forever is the kind of thing an auditor asks about.
   */
  @Inject
  @ConfigProperty(
      name = "shelfj.gateway.brute-force.token-paths",
      defaultValue = "/marketing/unsubscribe")
  String bruteForceTokenPaths;

  /**
   * Browser origins allowed to call the API (CORS). Absent/empty (the default) means no CORS
   * headers are emitted at all, so browser frontends are denied until origins are configured
   * explicitly. "*" allows any origin (dev only). Injected as Optional because MP Config treats an
   * empty value as "property not present" and refuses to inject a plain String for it.
   */
  @Inject
  @ConfigProperty(name = "shelfj.gateway.cors.allowed-origins")
  java.util.Optional<String> corsAllowedOrigins;

  /**
   * No default on purpose: a missing secret must fail deployment, never silently fall back to a
   * publicly known value. Local dev supplies it via docker-compose / .env (golden rule #5).
   */
  @Inject
  @ConfigProperty(name = "shelfj.jwt.secret")
  String jwtSecret;

  @Inject
  @ConfigProperty(name = "shelfj.jwt.issuer", defaultValue = "shelfj")
  String jwtIssuer;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.upstream.connect-timeout-seconds", defaultValue = "2")
  int upstreamConnectTimeoutSeconds;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.upstream.read-timeout-seconds", defaultValue = "10")
  int upstreamReadTimeoutSeconds;

  /**
   * Per-upstream-service circuit breaker (see {@link UpstreamCircuitBreaker}): after this many
   * consecutive connect/read failures to one service, its circuit opens and the gateway fails fast
   * for that service alone instead of dispatching every request at full volume.
   */
  @Inject
  @ConfigProperty(name = "shelfj.gateway.circuit-breaker.failure-threshold", defaultValue = "5")
  int circuitBreakerFailureThreshold;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.circuit-breaker.open-seconds", defaultValue = "10")
  int circuitBreakerOpenSeconds;

  /**
   * Rate-limit and brute-force counters live in Redis, not gateway heap — with multiple gateway
   * replicas a per-instance map lets an attacker simply round-robin past the limit. Defaults match
   * docker-compose's redis service (golden rule #5: external config, no hardcoded host:port).
   */
  @Inject
  @ConfigProperty(name = "shelfj.redis.host", defaultValue = "localhost")
  String redisHost;

  @Inject
  @ConfigProperty(name = "shelfj.redis.port", defaultValue = "6379")
  int redisPort;

  @Inject
  @ConfigProperty(name = "shelfj.redis.password", defaultValue = "redis_dev_change_me")
  String redisPassword;

  /** Parsed once at startup — these are consulted on every proxied request. */
  private java.util.Set<String> routableServiceSet;

  private java.util.Set<String> corsAllowedOriginSet;

  @jakarta.annotation.PostConstruct
  void parseSets() {
    routableServiceSet = csvToSet(routableServices);
    corsAllowedOriginSet = csvToSet(corsAllowedOrigins.orElse(""));
  }

  private static java.util.Set<String> csvToSet(String csv) {
    var out = new java.util.HashSet<String>();
    for (String s : csv.split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return java.util.Set.copyOf(out);
  }

  public String consulHost() {
    return consulHost;
  }

  public int consulPort() {
    return consulPort;
  }

  public boolean rateLimitEnabled() {
    return rateLimitEnabled;
  }

  public int rateLimitRequestsPerMinute() {
    return rateLimitRequestsPerMinute;
  }

  public java.util.Set<String> routableServices() {
    return routableServiceSet;
  }

  public boolean trustForwardedHeaders() {
    return trustForwardedHeaders;
  }

  public java.util.Set<String> corsAllowedOrigins() {
    return corsAllowedOriginSet;
  }

  public int upstreamConnectTimeoutSeconds() {
    return upstreamConnectTimeoutSeconds;
  }

  public int upstreamReadTimeoutSeconds() {
    return upstreamReadTimeoutSeconds;
  }

  public int circuitBreakerFailureThreshold() {
    return circuitBreakerFailureThreshold;
  }

  public int circuitBreakerOpenSeconds() {
    return circuitBreakerOpenSeconds;
  }

  public String redisHost() {
    return redisHost;
  }

  public int redisPort() {
    return redisPort;
  }

  public String redisPassword() {
    return redisPassword;
  }

  public boolean bruteForceEnabled() {
    return bruteForceEnabled;
  }

  /**
   * @return the token-bearing public paths guarded against guessing, as configured
   */
  public String bruteForceTokenPaths() {
    return bruteForceTokenPaths;
  }

  public int bruteForceMaxFailures() {
    return bruteForceMaxFailures;
  }

  public int bruteForceBlockMinutes() {
    return bruteForceBlockMinutes;
  }

  public String bruteForceLoginPath() {
    return bruteForceLoginPath;
  }

  public String jwtSecret() {
    return jwtSecret;
  }

  public String jwtIssuer() {
    return jwtIssuer;
  }
}
