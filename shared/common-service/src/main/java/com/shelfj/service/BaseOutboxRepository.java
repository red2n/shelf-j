package com.shelfj.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

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
  public List<UUID> drainAndPublish(int limit, Function<List<PendingOutbox>, List<UUID>> publish) {
    return inTx(
        c -> {
          List<PendingOutbox> rows = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, topic, payload FROM outbox"
                      + " WHERE published_at IS NULL ORDER BY created_at ASC LIMIT ?"
                      + " FOR UPDATE SKIP LOCKED")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                rows.add(
                    new PendingOutbox(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("payload")));
              }
            }
          }
          if (rows.isEmpty()) {
            return List.of();
          }

          List<UUID> published = publish.apply(rows);
          if (published == null || published.isEmpty()) {
            return List.of();
          }
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE outbox SET published_at = now() WHERE id = ANY(?)")) {
            ps.setArray(1, c.createArrayOf("uuid", published.toArray()));
            ps.executeUpdate();
          }
          return published;
        },
        "drain and publish outbox");
  }
}
