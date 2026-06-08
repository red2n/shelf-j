package com.shelfj.iam.repo;

import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence for refresh tokens. Only a HASH of each token is stored, never the raw value. */
@ApplicationScoped
public class RefreshTokenRepository extends BaseJdbcRepository {

  public void store(UUID userId, String tokenHash, Instant expiresAt) {
    exec(
        "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked)"
            + " VALUES (?,?,?,?,false)",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, userId);
          ps.setString(3, tokenHash);
          ps.setTimestamp(4, Timestamp.from(expiresAt));
        },
        "store refresh token");
  }

  /** Returns the owning userId if the token hash is valid (not revoked, not expired). */
  public Optional<UUID> validate(String tokenHash) {
    return query(
            "SELECT user_id FROM refresh_tokens"
                + " WHERE token_hash = ? AND revoked = false AND expires_at > now()",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "validate refresh token")
        .stream()
        .findFirst();
  }

  public void revoke(String tokenHash) {
    exec(
        "UPDATE refresh_tokens SET revoked = true WHERE token_hash = ?",
        ps -> ps.setString(1, tokenHash),
        "revoke refresh token");
  }

  public void revokeAllForUser(UUID userId) {
    exec(
        "UPDATE refresh_tokens SET revoked = true WHERE user_id = ?",
        ps -> ps.setObject(1, userId),
        "revoke user tokens");
  }
}
