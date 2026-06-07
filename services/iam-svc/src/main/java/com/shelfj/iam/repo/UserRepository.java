package com.shelfj.iam.repo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import com.shelfj.iam.domain.User;
import com.shelfj.service.OutboxStore;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Persistence for users, roles, refresh tokens, audit, and the outbox.
 *
 * <p>JDBC (template baseline). Write paths that must be atomic with the outbox (e.g. register) use
 * {@link #createUserWithOutbox} so the user row and the {@code UserRegistered} outbox row commit together.
 * Implements {@link OutboxStore} so the shared OutboxPublisher can drain its outbox.</p>
 */
@ApplicationScoped
public class UserRepository implements OutboxStore {

    @Inject
    DataSource dataSource;

    // --- lookups ---

    /** Find a user by email within a tenant scope (tenantId null = global/customer scope). */
    public Optional<User> findByEmail(UUID tenantId, String email) {
        // Separate branches so a null tenant maps to "IS NULL" cleanly (JDBC can't infer the type of a
        // null UUID bind parameter inside "tenant_id = ?").
        String sql = tenantId == null
                ? "SELECT * FROM users WHERE lower(email) = lower(?) AND tenant_id IS NULL"
                : "SELECT * FROM users WHERE lower(email) = lower(?) AND tenant_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, email);
            if (tenantId != null) {
                ps.setObject(2, tenantId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw dbError("find user by email");
        }
    }

    public Optional<User> findById(UUID id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw dbError("find user by id");
        }
    }

    public Set<String> rolesOf(UUID userId) {
        String sql = "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?";
        Set<String> roles = new java.util.HashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
            return roles;
        } catch (SQLException e) {
            throw dbError("load roles");
        }
    }

    private UUID roleIdByName(Connection c, String roleName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM roles WHERE name = ?")) {
            ps.setString(1, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("id", UUID.class);
                }
                throw new SQLException("role not found: " + roleName);
            }
        }
    }

    // --- atomic write: create user + assign role + write outbox event in one transaction ---

    public record OutboxRow(String eventType, String topic, UUID tenantId, UUID aggregateId, String payload) {}

    /**
     * Insert a user, assign a role, and write an outbox event — atomically.
     * @return the created user
     */
    public User createUserWithOutbox(User user, String roleName, OutboxRow outbox) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertUser(c, user);
                assignRole(c, user.id(), roleName);
                insertOutbox(c, outbox);
                c.commit();
                return user;
            } catch (SQLException e) {
                c.rollback();
                if (isUniqueViolation(e)) {
                    throw ApiException.conflict("USER_ALREADY_EXISTS", "Email or phone already registered");
                }
                throw dbError("create user");
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw dbError("create user (connection)");
        }
    }

    private void insertUser(Connection c, User u) throws SQLException {
        String sql = "INSERT INTO users (id, tenant_id, type, email, phone, password_hash, status, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, u.id());
            ps.setObject(2, u.tenantId());
            ps.setString(3, u.type());
            ps.setString(4, u.email());
            ps.setString(5, u.phone());
            ps.setString(6, u.passwordHash());
            ps.setString(7, u.status());
            ps.setTimestamp(8, Timestamp.from(u.createdAt()));
            ps.executeUpdate();
        }
    }

    private void assignRole(Connection c, UUID userId, String roleName) throws SQLException {
        UUID roleId = roleIdByName(c, roleName);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO user_roles (id, user_id, role_id, store_id) VALUES (?,?,?,NULL)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.executeUpdate();
        }
    }

    private void insertOutbox(Connection c, OutboxRow o) throws SQLException {
        String sql = "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, o.eventType());
            ps.setString(3, o.topic());
            ps.setObject(4, o.tenantId());
            ps.setObject(5, o.aggregateId());
            ps.setString(6, o.payload());
            ps.executeUpdate();
        }
    }

    /**
     * Stamp a tenant onto a user and grant the OWNER role — idempotently. Re-delivering the same TenantCreated
     * event must not create a second OWNER role or overwrite a differing tenant (golden rule #7). Returns true if
     * anything changed.
     */
    public boolean bindOwner(UUID userId, UUID tenantId, String ownerRole) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean changed = false;
                // set tenant only if currently null (don't move a user between tenants)
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE users SET tenant_id = ?, type = 'STAFF' WHERE id = ? AND tenant_id IS NULL")) {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, userId);
                    changed |= ps.executeUpdate() > 0;
                }
                // grant OWNER if not already present (idempotent)
                UUID roleId = roleIdByName(c, ownerRole);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO user_roles (id, user_id, role_id, store_id) "
                        + "SELECT ?, ?, ?, NULL WHERE NOT EXISTS "
                        + "(SELECT 1 FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id IS NULL)")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, userId);
                    ps.setObject(3, roleId);
                    ps.setObject(4, userId);
                    ps.setObject(5, roleId);
                    changed |= ps.executeUpdate() > 0;
                }
                c.commit();
                return changed;
            } catch (SQLException e) {
                c.rollback();
                throw dbError("bind owner");
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw dbError("bind owner (connection)");
        }
    }

    /**
     * Record that an event was processed; returns false if it was already processed (dedupe). Used by consumers to
     * stay idempotent.
     */
    public boolean markProcessedIfNew(UUID eventId, String consumer) {
        String sql = "INSERT INTO processed_events (event_id, consumer) VALUES (?, ?) ON CONFLICT (event_id) DO NOTHING";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, eventId);
            ps.setString(2, consumer);
            return ps.executeUpdate() > 0;   // 0 rows = conflict = already processed
        } catch (SQLException e) {
            throw dbError("mark processed event");
        }
    }

    // --- audit ---

    public void audit(UUID tenantId, UUID userId, String action, String detail) {
        String sql = "INSERT INTO audit_log (id, tenant_id, user_id, action, detail) VALUES (?,?,?,?,?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
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

    private static User map(ResultSet rs) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("type"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState());
    }

    private static ApiException dbError(String what) {
        return new ApiException(500, "DB_ERROR", "Failed to " + what, List.of());
    }

    /** Pending outbox rows for the publisher (oldest first). */
    @Override
    public List<PendingOutbox> pendingOutbox(int limit) {
        String sql = "SELECT id, topic, payload FROM outbox WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?";
        List<PendingOutbox> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PendingOutbox(rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw dbError("read outbox");
        }
    }

    @Override
    public void markPublished(UUID outboxId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ?")) {
            ps.setObject(1, outboxId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw dbError("mark outbox published");
        }
    }
}
