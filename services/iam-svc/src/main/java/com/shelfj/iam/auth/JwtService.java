package com.shelfj.iam.auth;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.shelfj.iam.config.ServiceConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Issues and verifies access tokens.
 *
 * <p><strong>Phase 1: HS256</strong> with a shared secret from config. The gateway verifies with the same secret.
 * <strong>Production:</strong> switch to RS256 — iam-svc signs with a private key, the gateway/services verify with
 * the public key (JWKS), and the secret never leaves a secret store. The claim shape stays the same.</p>
 *
 * <p>Claims: {@code sub}=userId, {@code tenant}=tenantId (absent for global customers), {@code roles}=string list,
 * {@code type}=STAFF|CUSTOMER. These map to what the gateway forwards as X-Tenant-Id / X-User-Id / X-Roles.</p>
 */
@ApplicationScoped
public class JwtService {

    @Inject
    ServiceConfig config;

    private Algorithm algorithm;
    private JWTVerifier verifier;

    @PostConstruct
    void init() {
        this.algorithm = Algorithm.HMAC256(config.jwtSecret());
        this.verifier = JWT.require(algorithm).withIssuer(config.jwtIssuer()).build();
    }

    /** Issue a signed access token for a user. */
    public String issueAccessToken(UUID userId, UUID tenantId, String userType, Set<String> roles) {
        Instant now = Instant.now();
        var builder = JWT.create()
                .withIssuer(config.jwtIssuer())
                .withSubject(userId.toString())
                .withClaim("type", userType)
                .withClaim("roles", List.copyOf(roles))
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(config.accessTtlSeconds()));
        if (tenantId != null) {
            builder.withClaim("tenant", tenantId.toString());
        }
        return builder.sign(algorithm);
    }

    /** Verify a token and return its decoded claims, or throw {@link JWTVerificationException}. */
    public DecodedJWT verify(String token) throws JWTVerificationException {
        return verifier.verify(token);
    }
}
