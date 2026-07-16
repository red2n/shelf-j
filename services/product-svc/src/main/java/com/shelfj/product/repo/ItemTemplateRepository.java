package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.ItemTemplate;
import com.shelfj.product.domain.Domain.ItemTemplateApplication;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Item templates (Gap #13): reusable attribute bundles applied to variants. Extracted from {@code
 * ProductRepository}: has its own outbox events on create/apply. Keeps the same {@link
 * #handleTxSqlException} override the monolith had — a duplicate {@code (tenant_id, name)} insert
 * must still surface as {@code 409 DUPLICATE}, not the generic 500 a plain {@code
 * BaseJdbcRepository} would give.
 */
@ApplicationScoped
public class ItemTemplateRepository extends BaseOutboxRepository {

  public ItemTemplate createTemplate(ItemTemplate t, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO item_templates (id, tenant_id, name, description, attributes, status)"
                  + " VALUES (?,?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setString(3, t.name());
            ps.setString(4, t.description());
            ps.setString(5, t.attributes());
            ps.setString(6, t.status());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409, "TEMPLATE_EXISTS", "Template name already exists", List.of(), null);
              ItemTemplate saved = mapTemplate(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create item template");
  }

  public Optional<ItemTemplate> findTemplate(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id, tenant_id, name, description, attributes, status, created_at"
                + " FROM item_templates WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ItemTemplateRepository::mapTemplate,
            "find item template");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<ItemTemplate> listTemplates(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, description, attributes, status, created_at"
            + " FROM item_templates WHERE tenant_id=? AND status='ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        ItemTemplateRepository::mapTemplate,
        "list item templates");
  }

  public ItemTemplate deactivateTemplate(UUID tenantId, UUID id) {
    exec(
        "UPDATE item_templates SET status='INACTIVE' WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        "deactivate item template");
    return findTemplate(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
  }

  public ItemTemplateApplication applyTemplate(
      UUID tenantId, UUID variantId, UUID templateId, OutboxRow event) {
    return inTx(
        c -> {
          ItemTemplate tpl =
              findTemplate(tenantId, templateId)
                  .orElseThrow(
                      () -> ApiException.notFound("TEMPLATE_NOT_FOUND", "Template not found"));
          // Copy attributes onto the variant (only when template has attributes)
          if (tpl.attributes() != null && !tpl.attributes().isBlank()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE product_variants SET attributes=? WHERE tenant_id=? AND id=?")) {
              ps.setString(1, tpl.attributes());
              ps.setObject(2, tenantId);
              ps.setObject(3, variantId);
              ps.executeUpdate();
            }
          }
          UUID appId = UUID.randomUUID();
          String insertSql =
              "INSERT INTO item_template_applications"
                  + " (id, tenant_id, variant_id, template_id) VALUES (?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(insertSql)) {
            ps.setObject(1, appId);
            ps.setObject(2, tenantId);
            ps.setObject(3, variantId);
            ps.setObject(4, templateId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(500, "DB_ERROR", "apply template failed", List.of(), null);
              ItemTemplateApplication app = mapApplication(rs);
              insertOutbox(c, event);
              return app;
            }
          }
        },
        "apply item template");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "DUPLICATE", "A record with that unique value already exists", List.of(), e);
    return dbError(what, e);
  }

  private static ItemTemplate mapTemplate(ResultSet rs) throws SQLException {
    return new ItemTemplate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("attributes"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ItemTemplateApplication mapApplication(ResultSet rs) throws SQLException {
    return new ItemTemplateApplication(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("template_id", UUID.class),
        rs.getObject("applied_at", OffsetDateTime.class).toInstant());
  }
}
