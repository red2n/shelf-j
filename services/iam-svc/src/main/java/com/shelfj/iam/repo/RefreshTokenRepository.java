package com.shelfj.iam.repo;

import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Persistence for refresh tokens. Only a HASH of each token is stored, never the raw value. */
@ApplicationScoped
public class RefreshTokenRepository {

  @Inject DataSource dataSource;

  public void store(UUID userId, String tokenHash, Instant expiresAt) {
    String sql =
        "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked) VALUES (?,?,?,?,false)";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, userId);
      ps.setString(3, tokenHash);
      ps.setTimestamp(4, Timestamp.from(expiresAt));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("store refresh token", e);
    }
  }

  /** Returns the owning userId if the token hash is valid (not revoked, not expired). */
  public Optional<UUID> validate(String tokenHash) {
    String sql =
        "SELECT user_id FROM refresh_tokens WHERE token_hash = ? AND revoked = false AND expires_at > now()";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, tokenHash);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getObject("user_id", UUID.class)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw dbError("validate refresh token", e);
    }
  }

  public void revoke(String tokenHash) {
    update("UPDATE refresh_tokens SET revoked = true WHERE token_hash = ?", tokenHash);
  }

  public void revokeAllForUser(UUID userId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE refresh_tokens SET revoked = true WHERE user_id = ?")) {
      ps.setObject(1, userId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("revoke user tokens", e);
    }
  }

  private void update(String sql, String tokenHash) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, tokenHash);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("update refresh token", e);
    }
  }

  private static ApiException dbError(String what, Throwable cause) {
    return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of(), cause);
  }
}
