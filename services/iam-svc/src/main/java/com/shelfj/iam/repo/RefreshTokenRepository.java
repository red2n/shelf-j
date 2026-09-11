package com.shelfj.iam.repo;

import com.shelfj.ids.Ids;
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
          ps.setObject(1, Ids.newId());
          ps.setObject(2, userId);
          ps.setString(3, tokenHash);
          ps.setTimestamp(4, Timestamp.from(expiresAt));
        },
        "store refresh token");
  }

  /**
   * Atomically consume (revoke) a valid token and return its owner. Validate-then-revoke as two
   * statements would let two concurrent requests both pass validation and each mint a fresh token
   * pair from the same (supposedly single-use) refresh token.
   */
  public Optional<UUID> consume(String tokenHash) {
    return query(
            "UPDATE refresh_tokens SET revoked = true"
                + " WHERE token_hash = ? AND revoked = false AND expires_at > now()"
                + " RETURNING user_id",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "consume refresh token")
        .stream()
        .findFirst();
  }

  /** Owner of a token that {@link #consume} would still accept, without consuming it. */
  public Optional<UUID> ownerOfActive(String tokenHash) {
    return query(
            "SELECT user_id FROM refresh_tokens"
                + " WHERE token_hash = ? AND revoked = false AND expires_at > now()",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "find active token owner")
        .stream()
        .findFirst();
  }

  /**
   * Owner of an already-revoked (but known) token. A client presenting a revoked token is the
   * classic stolen-token signal — the caller revokes the whole session family in response.
   */
  public Optional<UUID> ownerOfRevoked(String tokenHash) {
    return query(
            "SELECT user_id FROM refresh_tokens WHERE token_hash = ? AND revoked = true",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "find revoked token owner")
        .stream()
        .findFirst();
  }

  /**
   * Revoke one token (logout) and return its owner, so the caller can act on whose session this
   * was.
   */
  public Optional<UUID> revoke(String tokenHash) {
    return query(
            "UPDATE refresh_tokens SET revoked = true WHERE token_hash = ? RETURNING user_id",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "revoke refresh token")
        .stream()
        .findFirst();
  }

  public void revokeAllForUser(UUID userId) {
    exec(
        "UPDATE refresh_tokens SET revoked = true WHERE user_id = ?",
        ps -> ps.setObject(1, userId),
        "revoke user tokens");
  }

  /**
   * Revoke every refresh token belonging to a tenant's users — used when a tenant is deactivated so
   * existing sessions can't mint new access tokens (login + refresh are blocked separately too).
   */
  public void revokeAllForTenant(UUID tenantId) {
    exec(
        "UPDATE refresh_tokens SET revoked = true"
            + " WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)",
        ps -> ps.setObject(1, tenantId),
        "revoke tenant tokens");
  }
}
