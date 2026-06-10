package com.shelfj.iam.repo;

import com.shelfj.iam.domain.User;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for users, roles, refresh tokens, audit, and the outbox.
 *
 * <p>JDBC (template baseline). Write paths that must be atomic with the outbox (e.g. register) use
 * {@link #createUserWithOutbox} so the user row and the {@code UserRegistered} outbox row commit
 * together. Implements {@link com.shelfj.service.OutboxStore} so the shared OutboxPublisher can
 * drain its outbox.
 */
@ApplicationScoped
public class UserRepository extends BaseOutboxRepository {

  // --- lookups ---

  private static final String SELECT_COLS =
      "id, tenant_id, type, email, phone, password_hash, status, created_at, updated_at";

  /** Find a user by email within a tenant scope (tenantId null = global/customer scope). */
  public Optional<User> findByEmail(UUID tenantId, String email) {
    // Separate branches so a null tenant maps to "IS NULL" cleanly (JDBC can't infer the type of a
    // null UUID bind parameter inside "tenant_id = ?").
    String sql =
        tenantId == null
            ? "SELECT "
                + SELECT_COLS
                + " FROM users WHERE lower(email) = lower(?) AND tenant_id IS NULL"
            : "SELECT "
                + SELECT_COLS
                + " FROM users WHERE lower(email) = lower(?) AND tenant_id = ?";
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setString(1, email);
      if (tenantId != null) ps.setObject(2, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw dbError("find user by email", e);
    }
  }

  /**
   * All users with this email across every tenant scope. Email is unique only per scope
   * (uq_users_tenant_email), so after a user is bound to a tenant their row leaves the NULL scope —
   * login must search all scopes and disambiguate by password.
   */
  public List<User> findAllByEmail(String email) {
    return query(
        "SELECT " + SELECT_COLS + " FROM users WHERE lower(email) = lower(?)",
        ps -> ps.setString(1, email),
        UserRepository::map,
        "find users by email");
  }

  public Optional<User> findById(UUID id) {
    return query(
            "SELECT " + SELECT_COLS + " FROM users WHERE id = ?",
            ps -> ps.setObject(1, id),
            UserRepository::map,
            "find user by id")
        .stream()
        .findFirst();
  }

  public Set<String> rolesOf(UUID userId) {
    String sql =
        "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?";
    Set<String> roles = new java.util.HashSet<>();
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) roles.add(rs.getString(1));
      }
      return roles;
    } catch (SQLException e) {
      throw dbError("load roles", e);
    }
  }

  // --- atomic write: create user + assign role + write outbox event in one transaction ---

  /**
   * Insert a user, assign a role, and write an outbox event — atomically.
   *
   * @return the created user
   */
  public User createUserWithOutbox(User user, String roleName, OutboxRow outbox) {
    return inTx(
        c -> {
          insertUser(c, user);
          assignRole(c, user.id(), roleName);
          insertOutbox(c, outbox);
          return user;
        },
        "create user");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "USER_ALREADY_EXISTS", "Email or phone already registered", List.of(), e);
    return dbError(what, e);
  }

  /**
   * Stamp a tenant onto a user and grant the OWNER role — idempotently. Re-delivering the same
   * TenantCreated event must not create a second OWNER role or overwrite a differing tenant (golden
   * rule #7). Returns true if anything changed.
   */
  public boolean bindOwner(UUID userId, UUID tenantId, String ownerRole) {
    return inTx(
        c -> {
          boolean changed = false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE users SET tenant_id = ?, type = 'STAFF'"
                      + " WHERE id = ? AND tenant_id IS NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            changed |= ps.executeUpdate() > 0;
          }
          UUID roleId = roleIdByName(c, ownerRole);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                      + " SELECT ?, ?, ?, NULL WHERE NOT EXISTS"
                      + " (SELECT 1 FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id IS NULL)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setObject(4, userId);
            ps.setObject(5, roleId);
            changed |= ps.executeUpdate() > 0;
          }
          return changed;
        },
        "bind owner");
  }

  /**
   * Stamp a tenant onto a staff user and grant a store-scoped role — idempotently. Mirrors {@link
   * #bindOwner} but the role is bound to a specific store. Re-delivering the same StaffAssigned
   * event must not duplicate the role or overwrite a differing tenant (golden rule #7). Returns
   * true if anything changed.
   */
  public boolean bindStaff(UUID userId, UUID tenantId, String roleName, UUID storeId) {
    return inTx(
        c -> {
          boolean changed = false;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE users SET tenant_id = ?, type = 'STAFF'"
                      + " WHERE id = ? AND tenant_id IS NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            changed |= ps.executeUpdate() > 0;
          }
          UUID roleId = roleIdByName(c, roleName);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                      + " SELECT ?, ?, ?, ? WHERE NOT EXISTS"
                      + " (SELECT 1 FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id = ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setObject(4, storeId);
            ps.setObject(5, userId);
            ps.setObject(6, roleId);
            ps.setObject(7, storeId);
            changed |= ps.executeUpdate() > 0;
          }
          return changed;
        },
        "bind staff");
  }

  /**
   * Record that an event was processed; returns false if it was already processed (dedupe). Used by
   * consumers to stay idempotent.
   */
  public boolean markProcessedIfNew(UUID eventId, String consumer) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO processed_events (event_id, consumer) VALUES (?, ?)"
                    + " ON CONFLICT (event_id) DO NOTHING")) {
      ps.setObject(1, eventId);
      ps.setString(2, consumer);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("mark processed event", e);
    }
  }

  // --- audit ---

  public void audit(UUID tenantId, UUID userId, String action, String detail) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO audit_log (id, tenant_id, user_id, action, detail)"
                    + " VALUES (?,?,?,?,?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenantId);
      ps.setObject(3, userId);
      ps.setString(4, action);
      ps.setString(5, detail);
      ps.executeUpdate();
    } catch (SQLException e) {
      // audit failure must not break the main flow
      System.getLogger(UserRepository.class.getName())
          .log(System.Logger.Level.WARNING, "audit insert failed: " + e.getMessage());
    }
  }

  // --- mapping / helpers ---

  public void updatePassword(UUID userId, String newHash) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?")) {
      ps.setString(1, newHash);
      ps.setObject(2, userId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("update password", e);
    }
  }

  private static User map(ResultSet rs) throws SQLException {
    OffsetDateTime updOdt = rs.getObject("updated_at", OffsetDateTime.class);
    return new User(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("type"),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("password_hash"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        updOdt == null ? null : updOdt.toInstant());
  }

  private void insertUser(Connection c, User u) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO users"
                + " (id, tenant_id, type, email, phone, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, u.id());
      ps.setObject(2, u.tenantId());
      ps.setString(3, u.type());
      ps.setString(4, u.email());
      ps.setString(5, u.phone());
      ps.setString(6, u.passwordHash());
      ps.setString(7, u.status());
      ps.setObject(8, u.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(
          9,
          u.updatedAt() != null
              ? u.updatedAt().atOffset(ZoneOffset.UTC)
              : u.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void assignRole(Connection c, UUID userId, String roleName) throws SQLException {
    UUID roleId = roleIdByName(c, roleName);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO user_roles (id, user_id, role_id, store_id) VALUES (?,?,?,NULL)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, userId);
      ps.setObject(3, roleId);
      ps.executeUpdate();
    }
  }

  private UUID roleIdByName(Connection c, String roleName) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT id FROM roles WHERE name = ?")) {
      ps.setString(1, roleName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getObject("id", UUID.class);
        throw new SQLException("role not found: " + roleName);
      }
    }
  }

  /** Pending outbox rows for the publisher (oldest first). */
  @Override
  public List<PendingOutbox> pendingOutbox(int limit) {
    String sql =
        "SELECT id, topic, payload FROM outbox"
            + " WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?";
    List<PendingOutbox> out = new ArrayList<>();
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setInt(1, limit);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next())
          out.add(
              new PendingOutbox(
                  rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload")));
      }
      return out;
    } catch (SQLException e) {
      throw dbError("read outbox", e);
    }
  }
}
