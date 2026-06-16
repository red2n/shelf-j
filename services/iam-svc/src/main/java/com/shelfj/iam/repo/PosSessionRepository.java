package com.shelfj.iam.repo;

import com.shelfj.iam.domain.PosSession;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Gap #45 — POS session persistence. */
@ApplicationScoped
public class PosSessionRepository extends BaseJdbcRepository {

  public PosSession insert(PosSession s) {
    exec(
        "INSERT INTO pos_sessions (id,tenant_id,user_id,store_id,idle_timeout_seconds,status)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, s.id());
          ps.setObject(2, s.tenantId());
          ps.setObject(3, s.userId());
          ps.setObject(4, s.storeId());
          ps.setInt(5, s.idleTimeoutSeconds());
          ps.setString(6, s.status());
        },
        "insert pos session");
    return find(s.id()).orElseThrow();
  }

  public void touch(UUID id) {
    exec(
        "UPDATE pos_sessions SET last_activity_at = now() WHERE id = ? AND status = 'ACTIVE'",
        ps -> ps.setObject(1, id),
        "touch pos session");
  }

  public void end(UUID id) {
    exec(
        "UPDATE pos_sessions SET status = 'ENDED', ended_at = now() WHERE id = ? AND status = 'ACTIVE'",
        ps -> ps.setObject(1, id),
        "end pos session");
  }

  public Optional<PosSession> find(UUID id) {
    return query(
            "SELECT id, tenant_id, user_id, store_id, started_at, last_activity_at,"
                + " ended_at, idle_timeout_seconds, status"
                + " FROM pos_sessions WHERE id = ?",
            ps -> ps.setObject(1, id),
            this::map,
            "find pos session")
        .stream()
        .findFirst();
  }

  public List<PosSession> listActive(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, user_id, store_id, started_at, last_activity_at,"
            + " ended_at, idle_timeout_seconds, status"
            + " FROM pos_sessions WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY started_at DESC",
        ps -> ps.setObject(1, tenantId),
        this::map,
        "list active pos sessions");
  }

  /**
   * Expire sessions idle past their timeout. Returns the number expired and revokes their tokens
   * via the provided revokeCallback.
   */
  public int expireIdle() {
    return inTx(
        c -> {
          int count = 0;
          try (var ps =
              c.prepareStatement(
                  "UPDATE pos_sessions"
                      + " SET status = 'EXPIRED', ended_at = now()"
                      + " WHERE status = 'ACTIVE'"
                      + "   AND last_activity_at + (idle_timeout_seconds || ' seconds')::interval < now()"
                      + " RETURNING user_id")) {
            try (var rs = ps.executeQuery()) {
              while (rs.next()) {
                count++;
                UUID userId = rs.getObject("user_id", UUID.class);
                try (var rev =
                    c.prepareStatement(
                        "UPDATE refresh_tokens SET revoked = true WHERE user_id = ? AND revoked = false")) {
                  rev.setObject(1, userId);
                  rev.executeUpdate();
                }
              }
            }
          }
          return count;
        },
        "expire idle pos sessions");
  }

  private PosSession map(ResultSet rs) throws SQLException {
    OffsetDateTime endedOdt = rs.getObject("ended_at", OffsetDateTime.class);
    return new PosSession(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
        rs.getObject("last_activity_at", OffsetDateTime.class).toInstant(),
        endedOdt != null ? endedOdt.toInstant() : null,
        rs.getInt("idle_timeout_seconds"),
        rs.getString("status"));
  }
}
