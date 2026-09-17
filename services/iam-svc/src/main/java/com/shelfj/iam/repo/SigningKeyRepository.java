package com.shelfj.iam.repo;

import com.shelfj.iam.domain.SigningKey;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** The token signing keys (20.15). A platform table: keys belong to the deployment. */
@ApplicationScoped
public class SigningKeyRepository extends BaseJdbcRepository {

  private static final String UNIQUE_VIOLATION = "23505";

  private static final String COLUMNS =
      "SELECT kid, algorithm, public_key, private_key_sealed, status, created_at, retiring_at,"
          + " retired_at FROM signing_keys";

  private static final String SELECT_ACTIVE = COLUMNS + " WHERE status = 'ACTIVE'";

  private static final String SELECT_PUBLISHED =
      COLUMNS + " WHERE status IN ('ACTIVE', 'RETIRING') ORDER BY created_at DESC";

  private static final String SELECT_ALL = COLUMNS + " ORDER BY created_at DESC LIMIT 50";

  private static final String INSERT =
      "INSERT INTO signing_keys (kid, algorithm, public_key, private_key_sealed, status,"
          + " created_at) VALUES (?, ?, ?, ?, 'ACTIVE', ?)";

  private static final String RETIRE_ACTIVE =
      "UPDATE signing_keys SET status = 'RETIRING', retiring_at = ? WHERE status = 'ACTIVE'";

  private static final String RETIRE_OLD =
      "UPDATE signing_keys SET status = 'RETIRED', retired_at = ?, private_key_sealed = ''"
          + " WHERE status = 'RETIRING' AND retiring_at < ?";

  /** The key that signs now, if there is one. */
  public Optional<SigningKey> active() {
    return query(SELECT_ACTIVE, ps -> {}, SigningKeyRepository::map, "active signing key").stream()
        .findFirst();
  }

  /** The keys a verifier must know: the one that signs and those still retiring. */
  public List<SigningKey> published() {
    return query(SELECT_PUBLISHED, ps -> {}, SigningKeyRepository::map, "published signing keys");
  }

  /** The most recent keys of every status, without a use for their private halves. */
  public List<SigningKey> recent() {
    return query(SELECT_ALL, ps -> {}, SigningKeyRepository::map, "signing keys");
  }

  /**
   * Makes {@code key} the one that signs; the key that signed until now, if any, starts retiring.
   * One transaction, and the partial unique index lets only one caller win a race.
   *
   * @return false when another replica got there first
   */
  public boolean activate(SigningKey key) {
    try {
      inTx(
          c -> {
            try (PreparedStatement ps = c.prepareStatement(RETIRE_ACTIVE)) {
              ps.setObject(1, key.createdAt().atOffset(ZoneOffset.UTC));
              ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
              ps.setString(1, key.kid());
              ps.setString(2, key.algorithm());
              ps.setString(3, key.publicKey());
              ps.setString(4, key.privateKeySealed());
              ps.setObject(5, key.createdAt().atOffset(ZoneOffset.UTC));
              ps.executeUpdate();
            }
            return null;
          },
          "activate signing key");
      return true;
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
          return false;
        }
      }
      throw e;
    }
  }

  /**
   * Retires the keys that started retiring before {@code before}, wiping their private halves.
   *
   * @return how many were retired
   */
  public int retireBefore(Instant before, Instant now) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(RETIRE_OLD)) {
            ps.setObject(1, now.atOffset(ZoneOffset.UTC));
            ps.setObject(2, before.atOffset(ZoneOffset.UTC));
            return ps.executeUpdate();
          }
        },
        "retire signing keys");
  }

  private static SigningKey map(ResultSet rs) throws SQLException {
    return new SigningKey(
        rs.getString(1),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        instant(rs, 6),
        instant(rs, 7),
        instant(rs, 8));
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }
}
