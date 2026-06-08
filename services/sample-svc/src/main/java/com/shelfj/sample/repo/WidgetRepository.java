package com.shelfj.sample.repo;

import com.shelfj.sample.domain.Widget;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Widget} (plain JDBC for the Phase-0 template).
 *
 * <p><strong>Tenant rule (golden rule #3):</strong> every query filters {@code tenant_id} FIRST.
 * Never query by id alone.
 */
@ApplicationScoped
public class WidgetRepository extends BaseJdbcRepository {

  public Widget insert(UUID tenantId, String name) {
    var widget = new Widget(UUID.randomUUID(), tenantId, name, Instant.now());
    exec(
        "INSERT INTO widgets (id, tenant_id, name, created_at) VALUES (?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, widget.id());
          ps.setObject(2, widget.tenantId());
          ps.setString(3, widget.name());
          ps.setTimestamp(4, Timestamp.from(widget.createdAt()));
        },
        "insert widget");
    return widget;
  }

  /** Find by id, scoped to tenant. */
  public Optional<Widget> findById(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, created_at FROM widgets WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            WidgetRepository::map,
            "find widget")
        .stream()
        .findFirst();
  }

  /** List a tenant's widgets, newest first (cursor pagination would extend this). */
  public List<Widget> list(UUID tenantId, int limit) {
    return query(
        "SELECT id, tenant_id, name, created_at FROM widgets"
            + " WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        WidgetRepository::map,
        "list widgets");
  }

  private static Widget map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Widget(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getTimestamp("created_at").toInstant());
  }
}
