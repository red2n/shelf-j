package com.shelfj.iam.repo;

import com.shelfj.iam.domain.Mfa;
import com.shelfj.ids.Ids;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Second factors (20.12): authenticator apps, recovery codes, passkeys, the sign-ins waiting on
 * one, and each business's rule about who must have one. The checks that must not race — a code
 * used once, a recovery code spent once, a passkey's counter, a challenge consumed once — are each
 * one statement that says whether it won.
 */
@ApplicationScoped
public class MfaRepository extends BaseJdbcRepository {

  // ── authenticator app ───────────────────────────────────────────────────────

  public Optional<Mfa.Totp> totp(UUID userId) {
    return query(
            "SELECT user_id, secret_sealed, status, last_used_step FROM mfa_totp WHERE user_id = ?",
            ps -> ps.setObject(1, userId),
            rs ->
                new Mfa.Totp(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4)),
            "load authenticator")
        .stream()
        .findFirst();
  }

  /** A new secret waiting to be confirmed; replaces one that never was, never an active one. */
  public void putPendingTotp(UUID userId, String secretSealed, Instant now) {
    exec(
        "INSERT INTO mfa_totp (user_id, secret_sealed, status, last_used_step, created_at)"
            + " VALUES (?, ?, 'PENDING', -1, ?)"
            + " ON CONFLICT (user_id) DO UPDATE SET secret_sealed = EXCLUDED.secret_sealed,"
            + " created_at = EXCLUDED.created_at WHERE mfa_totp.status = 'PENDING'",
        ps -> {
          ps.setObject(1, userId);
          ps.setString(2, secretSealed);
          ps.setTimestamp(3, Timestamp.from(now));
        },
        "store pending authenticator");
  }

  /** An authenticator that is active from the start: the bootstrap administrator's. */
  public void putActiveTotp(UUID userId, String secretSealed, Instant now) {
    exec(
        "INSERT INTO mfa_totp (user_id, secret_sealed, status, last_used_step, created_at,"
            + " confirmed_at) VALUES (?, ?, 'ACTIVE', -1, ?, ?)",
        ps -> {
          ps.setObject(1, userId);
          ps.setString(2, secretSealed);
          ps.setTimestamp(3, Timestamp.from(now));
          ps.setTimestamp(4, Timestamp.from(now));
        },
        "store authenticator");
  }

  /**
   * Accepts a code's time step, once: the step must be later than any accepted before. Also what
   * turns a pending authenticator active.
   *
   * @return false when that step, or a later one, was already used
   */
  public boolean useTotpStep(UUID userId, long step, Instant now) {
    return !query(
            "UPDATE mfa_totp SET last_used_step = ?, status = 'ACTIVE',"
                + " confirmed_at = COALESCE(confirmed_at, ?)"
                + " WHERE user_id = ? AND last_used_step < ? RETURNING user_id",
            ps -> {
              ps.setLong(1, step);
              ps.setTimestamp(2, Timestamp.from(now));
              ps.setObject(3, userId);
              ps.setLong(4, step);
            },
            rs -> rs.getObject(1, UUID.class),
            "use authenticator code")
        .isEmpty();
  }

  public void deleteTotp(UUID userId) {
    exec(
        "DELETE FROM mfa_totp WHERE user_id = ?",
        ps -> ps.setObject(1, userId),
        "remove authenticator");
  }

  // ── recovery codes ──────────────────────────────────────────────────────────

  /** Replaces a login's recovery codes with a new set, in one transaction. */
  public void replaceRecoveryCodes(UUID userId, List<String> hashes, Instant now) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM mfa_recovery_codes WHERE user_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO mfa_recovery_codes (id, user_id, code_hash, created_at)"
                      + " VALUES (?, ?, ?, ?)")) {
            for (String hash : hashes) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, userId);
              ps.setString(3, hash);
              ps.setTimestamp(4, Timestamp.from(now));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "replace recovery codes");
  }

  /**
   * Spends a recovery code.
   *
   * @return false when it is not one of theirs or was spent before
   */
  public boolean useRecoveryCode(UUID userId, String hash, Instant now) {
    return !query(
            "UPDATE mfa_recovery_codes SET used_at = ?"
                + " WHERE user_id = ? AND code_hash = ? AND used_at IS NULL RETURNING id",
            ps -> {
              ps.setTimestamp(1, Timestamp.from(now));
              ps.setObject(2, userId);
              ps.setString(3, hash);
            },
            rs -> rs.getObject(1, UUID.class),
            "use recovery code")
        .isEmpty();
  }

  public int recoveryCodesLeft(UUID userId) {
    return query(
            "SELECT count(*) FROM mfa_recovery_codes WHERE user_id = ? AND used_at IS NULL",
            ps -> ps.setObject(1, userId),
            rs -> rs.getInt(1),
            "count recovery codes")
        .get(0);
  }

  // ── passkeys ────────────────────────────────────────────────────────────────

  private static final String PASSKEY_COLUMNS =
      "SELECT id, user_id, credential_id, public_key, sign_count, name, created_at, last_used_at"
          + " FROM mfa_passkeys";

  private static Mfa.Passkey passkey(ResultSet rs) throws SQLException {
    java.time.OffsetDateTime used = rs.getObject(8, java.time.OffsetDateTime.class);
    return new Mfa.Passkey(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getString(3),
        rs.getString(4),
        rs.getLong(5),
        rs.getString(6),
        rs.getObject(7, java.time.OffsetDateTime.class).toInstant(),
        used == null ? null : used.toInstant());
  }

  public List<Mfa.Passkey> passkeys(UUID userId) {
    return query(
        PASSKEY_COLUMNS + " WHERE user_id = ? ORDER BY created_at",
        ps -> ps.setObject(1, userId),
        MfaRepository::passkey,
        "list passkeys");
  }

  public Optional<Mfa.Passkey> passkey(UUID userId, String credentialId) {
    return query(
            PASSKEY_COLUMNS + " WHERE user_id = ? AND credential_id = ?",
            ps -> {
              ps.setObject(1, userId);
              ps.setString(2, credentialId);
            },
            MfaRepository::passkey,
            "load passkey")
        .stream()
        .findFirst();
  }

  /**
   * Keeps a new passkey.
   *
   * @return empty when that credential is already registered, to anybody
   */
  public Optional<UUID> insertPasskey(
      UUID userId,
      String credentialId,
      String publicKey,
      long signCount,
      String name,
      boolean userVerified,
      Instant now) {
    UUID id = Ids.newId();
    return query(
            "INSERT INTO mfa_passkeys (id, user_id, credential_id, public_key, sign_count, name,"
                + " user_verified, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (credential_id) DO NOTHING RETURNING id",
            ps -> {
              ps.setObject(1, id);
              ps.setObject(2, userId);
              ps.setString(3, credentialId);
              ps.setString(4, publicKey);
              ps.setLong(5, signCount);
              ps.setString(6, name);
              ps.setBoolean(7, userVerified);
              ps.setTimestamp(8, Timestamp.from(now));
            },
            rs -> rs.getObject(1, UUID.class),
            "store passkey")
        .stream()
        .findFirst();
  }

  /**
   * Moves a passkey's counter on after a sign-in, if nobody moved it first.
   *
   * @return false when the counter kept is no longer the one the signature was judged against
   */
  public boolean advancePasskey(UUID id, long from, long to, Instant now) {
    return !query(
            "UPDATE mfa_passkeys SET sign_count = ?, last_used_at = ?"
                + " WHERE id = ? AND sign_count = ? RETURNING id",
            ps -> {
              ps.setLong(1, to);
              ps.setTimestamp(2, Timestamp.from(now));
              ps.setObject(3, id);
              ps.setLong(4, from);
            },
            rs -> rs.getObject(1, UUID.class),
            "advance passkey counter")
        .isEmpty();
  }

  /**
   * @return false when it is not one of theirs
   */
  public boolean deletePasskey(UUID userId, UUID id) {
    return !query(
            "DELETE FROM mfa_passkeys WHERE user_id = ? AND id = ? RETURNING id",
            ps -> {
              ps.setObject(1, userId);
              ps.setObject(2, id);
            },
            rs -> rs.getObject(1, UUID.class),
            "remove passkey")
        .isEmpty();
  }

  /** Every second factor a login holds, gone: the lost-phone reset. */
  public void clearFactors(UUID userId) {
    inTx(
        c -> {
          for (String sql :
              List.of(
                  "DELETE FROM mfa_totp WHERE user_id = ?",
                  "DELETE FROM mfa_recovery_codes WHERE user_id = ?",
                  "DELETE FROM mfa_passkeys WHERE user_id = ?",
                  "DELETE FROM mfa_challenges WHERE user_id = ?")) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
              ps.setObject(1, userId);
              ps.executeUpdate();
            }
          }
          return null;
        },
        "clear second factors");
  }

  // ── challenges ──────────────────────────────────────────────────────────────

  public void createChallenge(
      UUID userId,
      String kind,
      String tokenHash,
      String webauthnChallenge,
      Instant now,
      Instant expiresAt) {
    inTx(
        c -> {
          // Swept here rather than by a timer: the table only grows when somebody signs in.
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM mfa_challenges WHERE expires_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(now.minusSeconds(3600)));
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO mfa_challenges (id, user_id, kind, token_hash, webauthn_challenge,"
                      + " attempts, expires_at, created_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, userId);
            ps.setString(3, kind);
            ps.setString(4, tokenHash);
            ps.setString(5, webauthnChallenge);
            ps.setTimestamp(6, Timestamp.from(expiresAt));
            ps.setTimestamp(7, Timestamp.from(now));
            ps.executeUpdate();
          }
          return null;
        },
        "open challenge");
  }

  /** A challenge still open: not expired, not consumed. */
  public Optional<Mfa.Challenge> openChallenge(String tokenHash, String kind, Instant now) {
    return query(
            "SELECT id, user_id, kind, webauthn_challenge, attempts, expires_at FROM mfa_challenges"
                + " WHERE token_hash = ? AND kind = ? AND consumed_at IS NULL AND expires_at > ?",
            ps -> {
              ps.setString(1, tokenHash);
              ps.setString(2, kind);
              ps.setTimestamp(3, Timestamp.from(now));
            },
            rs ->
                new Mfa.Challenge(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getInt(5),
                    rs.getObject(6, java.time.OffsetDateTime.class).toInstant()),
            "load challenge")
        .stream()
        .findFirst();
  }

  /** Counts a wrong answer; returns how many there have been. */
  public int countAttempt(UUID id) {
    return query(
            "UPDATE mfa_challenges SET attempts = attempts + 1 WHERE id = ? RETURNING attempts",
            ps -> ps.setObject(1, id),
            rs -> rs.getInt(1),
            "count second-factor attempt")
        .stream()
        .findFirst()
        .orElse(Integer.MAX_VALUE);
  }

  /** Wrong second-factor answers against this login since {@code since}, over every sign-in. */
  public int recentFailures(UUID userId, Instant since) {
    return query(
            "SELECT COALESCE(SUM(attempts), 0) FROM mfa_challenges"
                + " WHERE user_id = ? AND kind = 'LOGIN' AND created_at > ?",
            ps -> {
              ps.setObject(1, userId);
              ps.setTimestamp(2, Timestamp.from(since));
            },
            rs -> rs.getInt(1),
            "count second-factor failures")
        .get(0);
  }

  public void setWebauthnChallenge(UUID id, String challenge) {
    exec(
        "UPDATE mfa_challenges SET webauthn_challenge = ? WHERE id = ?",
        ps -> {
          ps.setString(1, challenge);
          ps.setObject(2, id);
        },
        "open passkey ceremony");
  }

  /**
   * Closes a challenge, once.
   *
   * @return false when somebody else closed it first
   */
  public boolean consume(UUID id, Instant now) {
    return !query(
            "UPDATE mfa_challenges SET consumed_at = ? WHERE id = ? AND consumed_at IS NULL"
                + " RETURNING id",
            ps -> {
              ps.setTimestamp(1, Timestamp.from(now));
              ps.setObject(2, id);
            },
            rs -> rs.getObject(1, UUID.class),
            "close challenge")
        .isEmpty();
  }

  // ── the business's rule ─────────────────────────────────────────────────────

  /** The tiers of this business's staff who must have a second factor; empty when nobody must. */
  public Set<String> requiredTiers(UUID tenantId) {
    return query(
            "SELECT required_tiers FROM mfa_policies WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getString(1),
            "load second-factor policy")
        .stream()
        .findFirst()
        .map(
            tiers ->
                Arrays.stream(tiers.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .collect(Collectors.toUnmodifiableSet()))
        .orElse(Set.of());
  }

  public void putPolicy(UUID tenantId, Set<String> tiers, UUID by, Instant now) {
    exec(
        "INSERT INTO mfa_policies (tenant_id, required_tiers, updated_at, updated_by)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET"
            + " required_tiers = EXCLUDED.required_tiers, updated_at = EXCLUDED.updated_at,"
            + " updated_by = EXCLUDED.updated_by",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, String.join(",", new java.util.TreeSet<>(tiers)));
          ps.setTimestamp(3, Timestamp.from(now));
          ps.setObject(4, by);
        },
        "store second-factor policy");
  }
}
