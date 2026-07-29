package com.shelfj.notification.channel;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;

/**
 * Mints the platform JWT {@link MqttChannel}'s own publisher connection authenticates with. The
 * broker (see infra/emqx.conf) verifies every client's JWT the same way regardless of who's
 * connecting — a tenant device or notification-svc itself — and its ACL (infra/emqx-acl.conf)
 * grants the literal username {@code __publisher__} write access to every tenant's topic, so this
 * token's {@code tenant} claim is that same sentinel rather than a real tenant id.
 *
 * <p>Minted once per process (not refreshed): a long expiry means a restart is required to renew
 * it, which is an accepted trade-off for now — see notification-svc notes if this needs to become a
 * background-refreshed credential.
 */
final class MqttPublisherToken {

  static final String PUBLISHER_IDENTITY = "__publisher__";

  private MqttPublisherToken() {}

  static String mint(String jwtSecret, String jwtIssuer, long ttlSeconds) {
    if (jwtSecret == null || jwtSecret.trim().length() < 32) {
      throw new IllegalStateException(
          "shelfj.jwt.secret must be set and at least 32 characters to mint the MQTT publisher"
              + " token");
    }
    Instant now = Instant.now();
    return JWT.create()
        .withIssuer(jwtIssuer)
        .withSubject(PUBLISHER_IDENTITY)
        .withClaim("tenant", PUBLISHER_IDENTITY)
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(ttlSeconds))
        .sign(Algorithm.HMAC256(jwtSecret));
  }
}
