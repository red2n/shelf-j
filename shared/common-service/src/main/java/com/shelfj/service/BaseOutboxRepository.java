package com.shelfj.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Extends {@link BaseJdbcRepository} with the three outbox operations every repo that emits domain
 * events needs: write a row inside a transaction, drain unpublished rows, and mark them published.
 * Repos without an outbox (e.g. RefreshTokenRepository) extend {@link BaseJdbcRepository} directly.
 */
public abstract class BaseOutboxRepository extends BaseJdbcRepository implements OutboxStore {

  /**
   * Insert one outbox row into the already-open connection {@code c}. No-op if {@code o} is null.
   */
  protected void insertOutbox(Connection c, OutboxRow o) throws SQLException {
    if (o == null) return;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO outbox (id, event_type, topic, tenant_id, aggregate_id, payload)"
                + " VALUES (?,?,?,?,?,?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, o.eventType());
      ps.setString(3, o.topic());
      ps.setObject(4, o.tenantId());
      ps.setObject(5, o.aggregateId());
      ps.setString(6, o.payload());
      ps.executeUpdate();
    }
  }

  @Override
  public List<PendingOutbox> pendingOutbox(int limit) {
    return query(
        "SELECT id, topic, payload FROM outbox"
            + " WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs ->
            new PendingOutbox(
                rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload")),
        "read outbox");
  }

  @Override
  public void markPublished(UUID id) {
    exec(
        "UPDATE outbox SET published_at = now() WHERE id = ?",
        ps -> ps.setObject(1, id),
        "mark outbox published");
  }

  @Override
  public void markPublished(List<UUID> ids) {
    if (ids.isEmpty()) return;
    exec(
        "UPDATE outbox SET published_at = now() WHERE id = ANY(?)",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
        "mark outbox batch published");
  }
}
