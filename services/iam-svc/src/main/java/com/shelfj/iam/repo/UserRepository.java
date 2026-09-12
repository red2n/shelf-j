package com.shelfj.iam.repo;

import com.shelfj.iam.domain.User;
import com.shelfj.ids.Ids;
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

  /**
   * Looks a user up by primary key.
   *
   * <p>Not tenant-scoped: the user id is globally unique and the row itself carries the tenant, so
   * callers acting on behalf of a tenant must check {@link User#tenantId()} before trusting it.
   *
   * @param id the user to fetch
   * @return the user, or empty when no such user exists
   */
  public Optional<User> findById(UUID id) {
    return query(
            "SELECT " + SELECT_COLS + " FROM users WHERE id = ?",
            ps -> ps.setObject(1, id),
            UserRepository::map,
            "find user by id")
        .stream()
        .findFirst();
  }

  /**
   * The role names granted to a user, for the JWT {@code roles} claim.
   *
   * @param userId the user whose roles to load
   * @return the distinct role names, empty when the user holds none
   */
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

  /**
   * Stores this user may operate in, for the JWT {@code storeIds} claim. A {@code NULL store_id}
   * row (a tenant-wide role like OWNER/PLATFORM_ADMIN) grants unrestricted access — signalled by
   * returning an <strong>empty set</strong> — because a tenant-wide grant must not be narrowed by
   * also holding a store-scoped role elsewhere. Otherwise the result is the distinct {@code
   * store_id} values the user is bound to, and callers must treat that as an allow-list.
   */
  public Set<UUID> storeScopeOf(UUID userId) {
    // Only staff roles carry a store scope. CUSTOMER is global and its row has no store — and
    // read as "a role with no store", it made every shopper-turned-cashier unrestricted across
    // the tenant, because one null store meant "unrestricted" (SJ-D48). PLATFORM_ADMIN has no
    // tenant, let alone a store. Both are skipped; among the staff roles that remain, a null
    // store is a tenant-wide role (OWNER, MANAGER) and means unrestricted, as before.
    String sql =
        "SELECT ur.store_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
            + " WHERE ur.user_id = ? AND r.name NOT IN ('CUSTOMER', 'PLATFORM_ADMIN')";
    Set<UUID> storeIds = new java.util.HashSet<>();
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID storeId = (UUID) rs.getObject(1);
          if (storeId == null) {
            return Set.of();
          }
          storeIds.add(storeId);
        }
      }
      return storeIds;
    } catch (SQLException e) {
      throw dbError("load store scope", e);
    }
  }

  // --- atomic write: create user + assign role + write outbox event in one transaction ---

  /**
   * Insert a user, optionally assign a role, and write an outbox event — atomically.
   *
   * @param roleName a real row in {@code roles} to grant immediately (e.g. {@code "CUSTOMER"} on
   *     self-signup), or {@code null} to skip role assignment — used for admin-driven staff
   *     provisioning, where the account is created tenant-less and the real store-scoped role is
   *     bound later when tenant-svc publishes {@code StaffAssigned} (see {@link
   *     com.shelfj.iam.service.AuthService#provisionStaff}). There is no generic "STAFF" row in
   *     {@code roles} — passing that name throws "role not found".
   * @return the created user
   */
  public User createUserWithOutbox(User user, String roleName, OutboxRow outbox) {
    return inTx(
        c -> {
          insertUser(c, user);
          if (roleName != null) assignRole(c, user.id(), roleName);
          insertOutbox(c, outbox);
          return user;
        },
        "create user");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Maps a unique-constraint violation to a {@code 409} rather than a generic database error, so
   * a duplicate email or phone reads as {@code USER_ALREADY_EXISTS} to the caller.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "USER_ALREADY_EXISTS", "Email or phone already registered", List.of(), e);
    return dbError(what, e);
  }

  /**
   * Stamp a tenant onto a user and grant the OWNER role — idempotently. The processed_events mark,
   * the bind, and the audit row commit in ONE transaction (golden rules #6/#7): marking first in a
   * separate transaction would swallow the event forever if the bind then failed. Returns false if
   * the event was already processed.
   */
  public boolean bindOwnerOnce(
      UUID eventId, String consumerName, UUID userId, UUID tenantId, String ownerRole) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE users SET tenant_id = ?, type = 'STAFF'"
                      + " WHERE id = ? AND tenant_id IS NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            ps.executeUpdate();
          }
          UUID roleId = roleIdByName(c, ownerRole);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                      + " SELECT ?, ?, ?, NULL WHERE NOT EXISTS"
                      + " (SELECT 1 FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id IS NULL)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setObject(4, userId);
            ps.setObject(5, roleId);
            ps.executeUpdate();
          }
          auditTx(c, tenantId, userId, "OWNER_BOUND", "via TenantCreated");
          return true;
        },
        "bind owner");
  }

  /**
   * Stamp a tenant onto a staff user and grant a store-scoped role — idempotently, with the
   * processed_events mark in the same transaction (see {@link #bindOwnerOnce}). Returns false if
   * the event was already processed.
   */
  public boolean bindStaffOnce(
      UUID eventId,
      String consumerName,
      UUID userId,
      UUID tenantId,
      String roleName,
      UUID storeId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE users SET tenant_id = ?, type = 'STAFF'"
                      + " WHERE id = ? AND tenant_id IS NULL")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, userId);
            ps.executeUpdate();
          }
          UUID roleId = roleIdByName(c, roleName);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                      + " SELECT ?, ?, ?, ? WHERE NOT EXISTS"
                      + " (SELECT 1 FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id = ?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setObject(4, storeId);
            ps.setObject(5, userId);
            ps.setObject(6, roleId);
            ps.setObject(7, storeId);
            ps.executeUpdate();
          }
          auditTx(c, tenantId, userId, "STAFF_BOUND", roleName + " @ store " + storeId);
          return true;
        },
        "bind staff");
  }

  // --- audit ---

  /**
   * Appends a row to the append-only audit log, outside any caller transaction.
   *
   * <p>Deliberately swallows its own failures with a warning: losing an audit row must not break
   * the flow being audited. Callers that need the audit row to be atomic with their write should
   * use the in-transaction paths instead.
   *
   * @param tenantId owning tenant, or {@code null} for a platform-scoped action
   * @param userId the user the action concerns
   * @param action the machine-readable action code, e.g. {@code OWNER_BOUND}
   * @param detail free-text context recorded alongside the action
   */
  public void audit(UUID tenantId, UUID userId, String action, String detail) {
    try (var c = dataSource.getConnection()) {
      auditTx(c, tenantId, userId, action, detail);
    } catch (SQLException e) {
      // audit failure must not break the main flow
      System.getLogger(UserRepository.class.getName())
          .log(System.Logger.Level.WARNING, "audit insert failed: " + e.getMessage());
    }
  }

  private static void auditTx(
      java.sql.Connection c, UUID tenantId, UUID userId, String action, String detail)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO audit_log (id, tenant_id, user_id, action, detail)"
                + " VALUES (?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, userId);
      ps.setString(4, action);
      ps.setString(5, detail);
      ps.executeUpdate();
    }
  }

  /**
   * Deletes a customer's login in one transaction with everything that would let it be used or
   * found again: its sessions, its one-time codes, and the event that tells other services.
   *
   * <p>The row stays, with status DELETED, so the user id other records carry still resolves to
   * something rather than nothing; what identifies the person does not. Email and phone become NULL
   * rather than a placeholder, so the same address can register a new account later — the unique
   * indexes ignore NULLs.
   */
  public void deleteCustomerAccount(User user, OutboxRow event) {
    inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE users SET email = NULL, phone = NULL, password_hash = NULL,"
                      + " status = 'DELETED', updated_at = now()"
                      + " WHERE id = ? AND type = 'CUSTOMER' AND status <> 'DELETED'")) {
            ps.setObject(1, user.id());
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            return null;
          }
          try (var ps =
              c.prepareStatement("UPDATE refresh_tokens SET revoked = true WHERE user_id = ?")) {
            ps.setObject(1, user.id());
            ps.executeUpdate();
          }
          for (String target : new String[] {user.email(), user.phone()}) {
            if (target == null || target.isBlank()) {
              continue;
            }
            try (var ps = c.prepareStatement("DELETE FROM otp_codes WHERE target = ?")) {
              ps.setString(1, target);
              ps.executeUpdate();
            }
          }
          // SJ-D45: the audit trail recorded the email on every registration and every sign-in,
          // successful or not, so a deleted account's address stayed legible in audit_log after
          // the users row had been scrubbed — which is the address the erasure existed to remove.
          //
          // audit_log is append-only (golden rule #8) and stays append-only: no row is deleted and
          // no action or timestamp is rewritten. Only the one field that names the person is
          // cleared, exactly as a settled order keeps its lines and loses its delivery address. The
          // history of who did what, and when, is intact; what is gone is the identifier.
          try (var ps =
              c.prepareStatement(
                  "UPDATE audit_log SET detail = NULL WHERE user_id = ? AND detail IS NOT NULL")) {
            ps.setObject(1, user.id());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return null;
        },
        "delete customer account");
  }

  // --- mapping / helpers ---

  /**
   * Replaces a user's password hash.
   *
   * <p>Does not revoke existing refresh tokens — a caller changing a password for security reasons
   * must revoke them separately.
   *
   * @param userId the user whose password to change
   * @param newHash the already-hashed new password; never a plaintext value
   */
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
      ps.setObject(1, Ids.newId());
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

  // --- bootstrap ---

  /**
   * Whether any platform administrator account exists yet.
   *
   * <p>Gates the one-shot bootstrap endpoint: once this is true, bootstrap must refuse to mint
   * another admin.
   *
   * @return {@code true} once at least one user holds {@code PLATFORM_ADMIN}
   */
  public boolean platformAdminExists() {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT 1 FROM users u"
                    + " JOIN user_roles ur ON ur.user_id = u.id"
                    + " JOIN roles r ON r.id = ur.role_id"
                    + " WHERE r.name = 'PLATFORM_ADMIN' LIMIT 1")) {
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw dbError("check platform admin", e);
    }
  }

  /**
   * Creates the first platform administrator, granting {@code PLATFORM_ADMIN} in the same
   * transaction as the user row.
   *
   * <p>Callers must check {@link #platformAdminExists()} first — this does not enforce the
   * one-admin bootstrap rule itself.
   *
   * @param user the tenant-less admin account to create
   */
  public void createPlatformAdmin(User user) {
    inTx(
        c -> {
          insertUser(c, user);
          assignRole(c, user.id(), "PLATFORM_ADMIN");
          return null;
        },
        "create platform admin");
  }
}
