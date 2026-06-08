package com.shelfj.sample.repo;

import com.shelfj.sample.domain.Widget;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Persistence for {@link Widget} (plain JDBC for the Phase-0 template).
 *
 * <p><strong>Tenant rule (golden rule #3):</strong> every query filters {@code tenant_id} FIRST.
 * Never query by id alone.
 */
@ApplicationScoped
public class WidgetRepository {

  @Inject DataSource dataSource;

  public Widget insert(UUID tenantId, String name) {
    var widget = new Widget(UUID.randomUUID(), tenantId, name, Instant.now());
    String sql = "INSERT INTO widgets (id, tenant_id, name, created_at) VALUES (?, ?, ?, ?)";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, widget.id());
      ps.setObject(2, widget.tenantId());
      ps.setString(3, widget.name());
      ps.setTimestamp(4, Timestamp.from(widget.createdAt()));
      ps.executeUpdate();
      return widget;
    } catch (SQLException e) {
      throw new ApiException(500, "DB_ERROR", "Failed to insert widget", List.of(), e);
    }
  }

  /** Find by id, scoped to tenant. */
  public Optional<Widget> findById(UUID tenantId, UUID id) {
    String sql =
        "SELECT id, tenant_id, name, created_at FROM widgets WHERE tenant_id = ? AND id = ?";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new ApiException(500, "DB_ERROR", "Failed to query widget", List.of(), e);
    }
  }

  /** List a tenant's widgets, newest first (cursor pagination would extend this). */
  public List<Widget> list(UUID tenantId, int limit) {
    String sql =
        "SELECT id, tenant_id, name, created_at FROM widgets "
            + "WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setInt(2, limit);
      try (ResultSet rs = ps.executeQuery()) {
        List<Widget> out = new ArrayList<>();
        while (rs.next()) {
          out.add(map(rs));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new ApiException(500, "DB_ERROR", "Failed to list widgets", List.of(), e);
    }
  }

  private static Widget map(ResultSet rs) throws SQLException {
    return new Widget(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getTimestamp("created_at").toInstant());
  }
}
