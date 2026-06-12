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
    var out = new java.util.HashSet<String>();
    for (String s : routableServices.split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return java.util.Set.copyOf(out);
  }

  public boolean trustForwardedHeaders() {
    return trustForwardedHeaders;
  }

  public java.util.Set<String> corsAllowedOrigins() {
    var out = new java.util.HashSet<String>();
    for (String s : corsAllowedOrigins.orElse("").split(",")) {
      String trimmed = s.trim();
      if (!trimmed.isEmpty()) out.add(trimmed);
    }
    return java.util.Set.copyOf(out);
  }

  public boolean bruteForceEnabled() {
    return bruteForceEnabled;
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
