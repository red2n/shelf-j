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

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.enabled", defaultValue = "true")
  boolean bruteForceEnabled;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.max-failures", defaultValue = "5")
  int bruteForceMaxFailures;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.block-minutes", defaultValue = "15")
  int bruteForceBlockMinutes;

  @Inject
  @ConfigProperty(name = "shelfj.gateway.brute-force.login-path", defaultValue = "/auth")
  String bruteForceLoginPath;

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
}
