package com.shelfj.iam.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shelfj.iam.config.ServiceConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * <p><strong>Phase 1: HS256</strong> with a shared secret from config. The gateway verifies with
 * the same secret. <strong>Production:</strong> switch to RS256 — iam-svc signs with a private key,
 * the gateway/services verify with the public key (JWKS), and the secret never leaves a secret
 * store. The claim shape stays the same.
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

  private Algorithm algorithm;
  private JWTVerifier verifier;

  @PostConstruct
  void init() {
    String secret = config.jwtSecret();
    if (secret == null || secret.trim().length() < 32) {
      throw new IllegalStateException(
          "shelfj.jwt.secret must be set and at least 32 characters; refusing to start with a"
              + " weak or missing JWT secret");
    }
    this.algorithm = Algorithm.HMAC256(secret);
    this.verifier = JWT.require(algorithm).withIssuer(config.jwtIssuer()).build();
  }

  /** Issue a signed access token for a user. */
  public String issueAccessToken(
      UUID userId,
      UUID tenantId,
      String userType,
      String email,
      Set<String> roles,
      Set<UUID> storeIds) {
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
    return builder.sign(algorithm);
  }

  /** Verify a token and return its decoded claims, or throw {@link JWTVerificationException}. */
  public DecodedJWT verify(String token) throws JWTVerificationException {
    return verifier.verify(token);
  }
}
