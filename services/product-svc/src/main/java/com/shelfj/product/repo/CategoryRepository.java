package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.Category;
import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Category CRUD. Extracted from {@code ProductRepository}: self-contained, no outbox events, no
 * coupling to any other aggregate, so it only needs the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class CategoryRepository extends BaseJdbcRepository {

  public Category createCategory(UUID tenantId, UUID parentId, String name) {
    Instant now = Instant.now();
    var c =
        new Category(UUID.randomUUID(), tenantId, parentId, name, Category.STATUS_ACTIVE, now, now);
    if (parentId != null && findCategory(tenantId, parentId).isEmpty()) {
      throw ApiException.badRequest("PARENT_NOT_FOUND", "parentId not found in this tenant");
    }
    exec(
        "INSERT INTO categories (id, tenant_id, parent_id, name, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, c.id());
          ps.setObject(2, c.tenantId());
          ps.setObject(3, c.parentId());
          ps.setString(4, c.name());
          ps.setString(5, c.status());
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create category");
    return c;
  }

  public Optional<Category> findCategoryByName(UUID tenantId, String name) {
    return query(
            "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
                + " FROM categories WHERE tenant_id = ? AND name = ? AND status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
            },
            CategoryRepository::mapCategory,
            "find category by name")
        .stream()
        .findFirst();
  }

  public Optional<Category> findCategory(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
                + " FROM categories WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            CategoryRepository::mapCategory,
            "find category")
        .stream()
        .findFirst();
  }

  public List<Category> listCategories(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, parent_id, name, status, created_at, updated_at"
            + " FROM categories WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        CategoryRepository::mapCategory,
        "list categories");
  }

  public Category updateCategory(UUID tenantId, UUID id, String name, UUID parentId) {
    Instant now = Instant.now();
    exec(
        "UPDATE categories SET name = ?, parent_id = ?, updated_at = ?"
            + " WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
        ps -> {
          ps.setString(1, name);
          ps.setObject(2, parentId);
          ps.setObject(3, now.atOffset(ZoneOffset.UTC));
          ps.setObject(4, tenantId);
          ps.setObject(5, id);
        },
        "update category");
    return findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  public Category deactivateCategory(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE categories SET status = 'INACTIVE', updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate category");
    return findCategory(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
  }

  private static Category mapCategory(ResultSet rs) throws SQLException {
    return new Category(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("parent_id", UUID.class),
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
