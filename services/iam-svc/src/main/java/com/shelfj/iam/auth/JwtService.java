package com.shelfj.iam.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shelfj.iam.config.ServiceConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * <p>Tokens are signed <strong>RS256</strong> with the key {@link SigningKeys} holds (20.15): the
 * private half never leaves this service, the token's header names the key ({@code kid}), and every
 * verifier — the gateway, the MQTT broker — fetches the public half from {@code
 * /auth/.well-known/jwks.json}. Nothing outside iam-svc can mint a token, and the key rotates. The
 * algorithm is pinned on both sides (RFC 8725): a token that says anything but RS256 is refused.
 *
 * <p>Claims: {@code sub}=userId, {@code tenant}=tenantId (absent for global customers), {@code
 * roles}=string list, {@code type}=STAFF|CUSTOMER, {@code storeIds}=string list (absent means
 * unrestricted — e.g. OWNER/PLATFORM_ADMIN — present means the holder may only operate in those
 * stores, e.g. a CASHIER bound to one store). These map to what the gateway forwards as X-Tenant-Id
 * / X-User-Id / X-Roles / X-Store-Ids.
 */
@ApplicationScoped
public class JwtService {

  @Inject ServiceConfig config;
  @Inject SigningKeys keys;

  /** Issue a signed access token for a user. */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds) {
    return issueAccessToken(userId, tenantId, userType, email, roles, storeIds, null);
  }

  /**
   * Issue a signed access token carrying a permission claim (20.10).
   *
   * @param permissions the {@code perms} claim, or {@code null} to omit it — a login with no custom
   *     role carries none and is judged by its tiers' defaults
   */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds,
      Set<String> permissions) {
    Instant now = Instant.now();
    var builder =
        JWT.create()
            .withIssuer(config.jwtIssuer())
            .withSubject(userId.toString())
            .withClaim("type", userType)
            .withClaim("roles", List.copyOf(roles))
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(config.accessTtlSeconds()));
    if (tenantId != null) {
      builder.withClaim("tenant", tenantId.toString());
    }
    // The holder's own email, which the gateway forwards downstream as X-User-Email. A shopper's
    // login is global while the shop's customer record is not, so without it customer-svc has no
    // way to know who the person signing in actually is, and an online order belongs to nobody the
    // shop can email, credit or erase (SJ-D44). Omitted rather than empty for a deleted login,
    // whose email has been erased.
    if (email != null && !email.isBlank()) {
      builder.withClaim("email", email);
    }
    // Omitted (not an empty claim) when unrestricted, so the gateway/TenantContext distinguish
    // "no claim present" from "claim present but empty" — both mean unrestricted, but only the
    // omitted form is what an unrestricted-access user (OWNER/PLATFORM_ADMIN) actually carries.
    if (storeIds != null && !storeIds.isEmpty()) {
      builder.withClaim("storeIds", storeIds.stream().map(UUID::toString).toList());
    }
    // Present, possibly empty, only for a login that holds a custom role: the gateway stamps the
    // claim as X-Permissions ("-" when empty) and a service judges the holder by it alone.
    if (permissions != null) {
      builder.withClaim("perms", List.copyOf(new java.util.TreeSet<>(permissions)));
    }
    SigningKeys.Signer signer = keys.signer();
    return builder.withKeyId(signer.kid()).sign(Algorithm.RSA256(null, signer.privateKey()));
  }

  /**
   * Verify a token and return its decoded claims, or throw {@link JWTVerificationException}: the
   * algorithm must be RS256 and the key one this service still publishes.
   */
  public DecodedJWT verify(String token) throws JWTVerificationException {
    DecodedJWT decoded = JWT.decode(token);
    if (!"RS256".equals(decoded.getAlgorithm())) {
      throw new JWTVerificationException(
          "tokens are RS256; this one says " + decoded.getAlgorithm());
    }
    var publicKey =
        keys.verifier(decoded.getKeyId())
            .orElseThrow(() -> new JWTVerificationException("unknown signing key"));
    return JWT.require(Algorithm.RSA256(publicKey, null))
        .withIssuer(config.jwtIssuer())
        .build()
        .verify(token);
  }
}
