package com.storeql.iam.repo;

import com.storeql.iam.domain.Sso;
import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistence for single sign-on: a business's provider, which of the provider's people is which
 * login, and sign-ins in flight.
 *
 * <p>Two lookups are not by tenant, because they are how the tenant is found: a connection by the
 * sign-in name a person typed, and a sign-in in flight by the random state or ticket it was given.
 * Everything a business manages is read and written by its tenant first.
 */
@ApplicationScoped
public class SsoRepository extends BaseJdbcRepository {

  private static final String CONNECTION_COLS =
      "id, tenant_id, slug, issuer, client_id, client_secret_sealed, enabled, required_tiers,"
          + " require_verified_email, updated_at";

  private static final String IDENTITY_COLS =
      "id, tenant_id, user_id, issuer, subject, email, linked_at, last_login_at";

  private static final String FLOW_COLS =
      "id, tenant_id, connection_id, nonce, code_verifier_sealed, app_challenge, return_to,"
          + " user_id, amr, expires_at";

  // ── the connection ──────────────────────────────────────────────────────────

  public Optional<Sso.Connection> connection(UUID tenantId) {
    return query(
            "SELECT " + CONNECTION_COLS + " FROM sso_connections WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            SsoRepository::connectionOf,
            "load sso connection")
        .stream()
        .findFirst();
  }

  /** The connection a sign-in name belongs to, whichever business that is. */
  public Optional<Sso.Connection> connectionBySlug(String slug) {
    return query(
            "SELECT " + CONNECTION_COLS + " FROM sso_connections WHERE lower(slug) = lower(?)",
            ps -> ps.setString(1, slug),
            SsoRepository::connectionOf,
            "find sso connection")
        .stream()
        .findFirst();
  }

  /**
   * Writes a business's connection. When the provider changes, the links to the old provider's
   * people go in the same transaction: a subject means nothing at another issuer, and the new
   * provider's people are matched afresh at their first sign-in.
   *
   * @param secretSealed the client secret sealed, or null to keep the one already held
   * @throws ApiException 409 {@code SSO_SLUG_TAKEN} when another business has the sign-in name
   */
  public Sso.Connection put(
      UUID tenantId,
      String slug,
      String issuer,
      String clientId,
      String secretSealed,
      boolean enabled,
      Set<String> requiredTiers,
      boolean requireVerifiedEmail,
      UUID by,
      Instant now) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO sso_connections (id, tenant_id, slug, issuer, client_id,"
                      + " client_secret_sealed, enabled, required_tiers, require_verified_email,"
                      + " created_at, updated_at, updated_by)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (tenant_id) DO UPDATE SET slug = EXCLUDED.slug,"
                      + " issuer = EXCLUDED.issuer, client_id = EXCLUDED.client_id,"
                      + " client_secret_sealed = COALESCE(EXCLUDED.client_secret_sealed,"
                      + " sso_connections.client_secret_sealed),"
                      + " enabled = EXCLUDED.enabled, required_tiers = EXCLUDED.required_tiers,"
                      + " require_verified_email = EXCLUDED.require_verified_email,"
                      + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setString(3, slug);
            ps.setString(4, issuer);
            ps.setString(5, clientId);
            ps.setString(6, secretSealed);
            ps.setBoolean(7, enabled);
            ps.setString(8, String.join(",", new TreeSet<>(requiredTiers)));
            ps.setBoolean(9, requireVerifiedEmail);
            ps.setTimestamp(10, Timestamp.from(now));
            ps.setTimestamp(11, Timestamp.from(now));
            ps.setObject(12, by);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM sso_identities WHERE tenant_id = ? AND issuer <> ?")) {
            ps.setObject(1, tenantId);
            ps.setString(2, issuer);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT " + CONNECTION_COLS + " FROM sso_connections WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return connectionOf(rs);
            }
          }
        },
        "put sso connection");
  }

  /** Removes a business's connection, the links to its people, and its sign-ins in flight. */
  public boolean delete(UUID tenantId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM sso_identities WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM sso_flows WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM sso_connections WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete sso connection");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_sso_connections_slug")) {
      return new ApiException(
          409,
          "SSO_SLUG_TAKEN",
          "Another business signs in with that name: choose another",
          List.of(),
          e);
    }
    return super.handleTxSqlException(what, e);
  }

  // ── the provider's people ───────────────────────────────────────────────────

  public Optional<Sso.Identity> identity(UUID tenantId, String issuer, String subject) {
    return query(
            "SELECT "
                + IDENTITY_COLS
                + " FROM sso_identities WHERE tenant_id = ? AND issuer = ? AND subject = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, issuer);
              ps.setString(3, subject);
            },
            SsoRepository::identityOf,
            "load sso identity")
        .stream()
        .findFirst();
  }

  /** The link a login already has at this provider, if any. */
  public Optional<Sso.Identity> identityOfUser(UUID tenantId, UUID userId, String issuer) {
    return query(
            "SELECT "
                + IDENTITY_COLS
                + " FROM sso_identities WHERE tenant_id = ? AND user_id = ? AND issuer = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
              ps.setString(3, issuer);
            },
            SsoRepository::identityOf,
            "load sso identity of user")
        .stream()
        .findFirst();
  }

  /**
   * Links a provider's person to a login, once: two first sign-ins racing each other link it one
   * time, and the loser is told so rather than failing.
   *
   * @return whether this call made the link
   */
  public boolean link(
      UUID tenantId, UUID userId, String issuer, String subject, String email, Instant now) {
    return !query(
            "INSERT INTO sso_identities (id, tenant_id, user_id, issuer, subject, email,"
                + " linked_at, last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING RETURNING id",
            ps -> {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, userId);
              ps.setString(4, issuer);
              ps.setString(5, subject);
              ps.setString(6, email);
              ps.setTimestamp(7, Timestamp.from(now));
              ps.setTimestamp(8, Timestamp.from(now));
            },
            rs -> rs.getObject(1, UUID.class),
            "link sso identity")
        .isEmpty();
  }

  /** Records a sign-in through a link, and the address the provider gave this time. */
  public void touch(UUID tenantId, UUID id, String email, Instant now) {
    exec(
        "UPDATE sso_identities SET last_login_at = ?, email = COALESCE(?, email)"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(now));
          ps.setString(2, email);
          ps.setObject(3, tenantId);
          ps.setObject(4, id);
        },
        "touch sso identity");
  }

  /** A link, with the login's own email beside what the provider asserted. */
  public record LinkedLogin(Sso.Identity identity, String loginEmail) {}

  /** A page of a business's links, oldest first, after a cursor. */
  public List<LinkedLogin> identities(UUID tenantId, UUID after, int limit) {
    String cols =
        Arrays.stream(IDENTITY_COLS.split(", "))
            .map(c -> "i." + c)
            .collect(Collectors.joining(", "));
    return query(
        "SELECT "
            + cols
            + ", u.email AS login_email FROM sso_identities i JOIN users u ON u.id = i.user_id"
            + " WHERE i.tenant_id = ? AND (?::uuid IS NULL OR i.id > ?::uuid)"
            + " ORDER BY i.id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, after);
          ps.setObject(3, after);
          ps.setInt(4, limit);
        },
        rs -> new LinkedLogin(identityOf(rs), rs.getString("login_email")),
        "list sso identities");
  }

  /** Removes one link: the person is matched by email again at their next sign-in. */
  public boolean unlink(UUID tenantId, UUID id) {
    return !query(
            "DELETE FROM sso_identities WHERE tenant_id = ? AND id = ? RETURNING id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            rs -> rs.getObject(1, UUID.class),
            "unlink sso identity")
        .isEmpty();
  }

  // ── sign-ins in flight ──────────────────────────────────────────────────────

  /** Opens a sign-in; the table is swept of old ones here, since it grows only when used. */
  public void openFlow(
      UUID tenantId,
      UUID connectionId,
      String stateHash,
      String nonce,
      String codeVerifierSealed,
      String appChallenge,
      String returnTo,
      Instant now,
      Instant expiresAt) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM sso_flows WHERE expires_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(now.minusSeconds(3600)));
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO sso_flows (id, tenant_id, connection_id, state_hash, nonce,"
                      + " code_verifier_sealed, app_challenge, return_to, expires_at, created_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, connectionId);
            ps.setString(4, stateHash);
            ps.setString(5, nonce);
            ps.setString(6, codeVerifierSealed);
            ps.setString(7, appChallenge);
            ps.setString(8, returnTo);
            ps.setTimestamp(9, Timestamp.from(expiresAt));
            ps.setTimestamp(10, Timestamp.from(now));
            ps.executeUpdate();
          }
          return null;
        },
        "open sso flow");
  }

  /**
   * The sign-in a state names, if it is still waiting for the provider — and from this moment no
   * longer: a state is spent the first time the browser comes back with it.
   */
  public Optional<Sso.Flow> returned(String stateHash, Instant now) {
    return query(
            "UPDATE sso_flows SET returned_at = ?"
                + " WHERE state_hash = ? AND returned_at IS NULL AND expires_at > ?"
                + " RETURNING "
                + FLOW_COLS,
            ps -> {
              ps.setTimestamp(1, Timestamp.from(now));
              ps.setString(2, stateHash);
              ps.setTimestamp(3, Timestamp.from(now));
            },
            SsoRepository::flowOf,
            "return sso flow")
        .stream()
        .findFirst();
  }

  /** What the provider proved, left for the app under a ticket good for a short while. */
  public void leaveTicket(
      UUID tenantId, UUID flowId, String ticketHash, UUID userId, String amr, Instant expiresAt) {
    exec(
        "UPDATE sso_flows SET ticket_hash = ?, user_id = ?, amr = ?, expires_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, ticketHash);
          ps.setObject(2, userId);
          ps.setString(3, amr);
          ps.setTimestamp(4, Timestamp.from(expiresAt));
          ps.setObject(5, tenantId);
          ps.setObject(6, flowId);
        },
        "leave sso ticket");
  }

  /** The sign-in a ticket names, spent by this call whether or not what comes with it is right. */
  public Optional<Sso.Flow> redeem(String ticketHash, Instant now) {
    return query(
            "UPDATE sso_flows SET redeemed_at = ?"
                + " WHERE ticket_hash = ? AND redeemed_at IS NULL AND expires_at > ?"
                + " AND user_id IS NOT NULL RETURNING "
                + FLOW_COLS,
            ps -> {
              ps.setTimestamp(1, Timestamp.from(now));
              ps.setString(2, ticketHash);
              ps.setTimestamp(3, Timestamp.from(now));
            },
            SsoRepository::flowOf,
            "redeem sso ticket")
        .stream()
        .findFirst();
  }

  // ── mapping ─────────────────────────────────────────────────────────────────

  private static Sso.Connection connectionOf(ResultSet rs) throws SQLException {
    String tiers = rs.getString("required_tiers");
    return new Sso.Connection(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("slug"),
        rs.getString("issuer"),
        rs.getString("client_id"),
        rs.getString("client_secret_sealed"),
        rs.getBoolean("enabled"),
        tiers == null || tiers.isBlank() ? Set.of() : Set.of(tiers.split(",")),
        rs.getBoolean("require_verified_email"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static Sso.Identity identityOf(ResultSet rs) throws SQLException {
    return new Sso.Identity(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("issuer"),
        rs.getString("subject"),
        rs.getString("email"),
        rs.getObject("linked_at", OffsetDateTime.class).toInstant(),
        rs.getObject("last_login_at", OffsetDateTime.class).toInstant());
  }

  private static Sso.Flow flowOf(ResultSet rs) throws SQLException {
    return new Sso.Flow(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("connection_id", UUID.class),
        rs.getString("nonce"),
        rs.getString("code_verifier_sealed"),
        rs.getString("app_challenge"),
        rs.getString("return_to"),
        rs.getObject("user_id", UUID.class),
        rs.getString("amr"),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant());
  }
}
