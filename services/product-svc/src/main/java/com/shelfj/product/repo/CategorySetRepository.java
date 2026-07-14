package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.CategorySet;
import com.shelfj.product.domain.Domain.CategorySetMember;
import com.shelfj.product.domain.Domain.VariantCategorySetAssignment;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Category sets (Gap #39): controlled, purpose-scoped groupings of categories, and the per-variant
 * assignment into a set. Extracted from {@code ProductRepository}: self-contained, no outbox
 * events, no coupling to any other aggregate, so it only needs the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class CategorySetRepository extends BaseJdbcRepository {

  public CategorySet createCategorySet(CategorySet s) {
    exec(
        "INSERT INTO category_sets"
            + " (id, tenant_id, name, description, purpose, default_cat_id, controlled, status)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, s.id());
          ps.setObject(2, s.tenantId());
          ps.setString(3, s.name());
          ps.setString(4, s.description());
          ps.setString(5, s.purpose());
          ps.setObject(6, s.defaultCatId());
          ps.setBoolean(7, s.controlled());
          ps.setString(8, s.status());
        },
        "create category set");
    return findCategorySet(s.tenantId(), s.id()).orElseThrow();
  }

  public Optional<CategorySet> findCategorySet(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, description, purpose, default_cat_id, controlled,"
                + " status, created_at, updated_at"
                + " FROM category_sets WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            CategorySetRepository::mapCategorySet,
            "find category set")
        .stream()
        .findFirst();
  }

  public List<CategorySet> listCategorySets(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, description, purpose, default_cat_id, controlled,"
            + " status, created_at, updated_at"
            + " FROM category_sets WHERE tenant_id = ? ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        CategorySetRepository::mapCategorySet,
        "list category sets");
  }

  public CategorySet updateCategorySet(
      UUID tenantId,
      UUID id,
      String name,
      String description,
      String purpose,
      UUID defaultCatId,
      boolean controlled,
      String status) {
    exec(
        "UPDATE category_sets"
            + " SET name = COALESCE(?, name), description = COALESCE(?, description),"
            + " purpose = COALESCE(?, purpose), default_cat_id = ?, controlled = ?,"
            + " status = COALESCE(?, status), updated_at = now()"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, description);
          ps.setString(3, purpose);
          ps.setObject(4, defaultCatId);
          ps.setBoolean(5, controlled);
          ps.setString(6, status);
          ps.setObject(7, tenantId);
          ps.setObject(8, id);
        },
        "update category set");
    return findCategorySet(tenantId, id).orElseThrow();
  }

  public boolean deleteCategorySet(UUID tenantId, UUID id) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM category_sets WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            return ps.executeUpdate() > 0;
          }
        },
        "delete category set");
  }

  public CategorySetMember addCategorySetMember(CategorySetMember m) {
    exec(
        "INSERT INTO category_set_members (id, tenant_id, set_id, category_id)"
            + " VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (tenant_id, set_id, category_id) DO NOTHING",
        ps -> {
          ps.setObject(1, m.id());
          ps.setObject(2, m.tenantId());
          ps.setObject(3, m.setId());
          ps.setObject(4, m.categoryId());
        },
        "add category set member");
    return query(
            "SELECT id, tenant_id, set_id, category_id, created_at"
                + " FROM category_set_members"
                + " WHERE tenant_id = ? AND set_id = ? AND category_id = ?",
            ps -> {
              ps.setObject(1, m.tenantId());
              ps.setObject(2, m.setId());
              ps.setObject(3, m.categoryId());
            },
            CategorySetRepository::mapCategorySetMember,
            "find category set member")
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public List<CategorySetMember> listCategorySetMembers(UUID tenantId, UUID setId) {
    return query(
        "SELECT id, tenant_id, set_id, category_id, created_at"
            + " FROM category_set_members WHERE tenant_id = ? AND set_id = ? ORDER BY created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, setId);
        },
        CategorySetRepository::mapCategorySetMember,
        "list category set members");
  }

  public boolean deleteCategorySetMember(UUID tenantId, UUID setId, UUID categoryId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM category_set_members"
                      + " WHERE tenant_id = ? AND set_id = ? AND category_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, setId);
            ps.setObject(3, categoryId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete category set member");
  }

  public VariantCategorySetAssignment upsertVariantCategorySetAssignment(
      VariantCategorySetAssignment a) {
    exec(
        "INSERT INTO variant_category_set_assignments"
            + " (id, tenant_id, variant_id, set_id, category_id) VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (tenant_id, variant_id, set_id)"
            + " DO UPDATE SET category_id = EXCLUDED.category_id, updated_at = now()",
        ps -> {
          ps.setObject(1, a.id());
          ps.setObject(2, a.tenantId());
          ps.setObject(3, a.variantId());
          ps.setObject(4, a.setId());
          ps.setObject(5, a.categoryId());
        },
        "upsert variant category set assignment");
    return query(
            "SELECT id, tenant_id, variant_id, set_id, category_id, created_at, updated_at"
                + " FROM variant_category_set_assignments"
                + " WHERE tenant_id = ? AND variant_id = ? AND set_id = ?",
            ps -> {
              ps.setObject(1, a.tenantId());
              ps.setObject(2, a.variantId());
              ps.setObject(3, a.setId());
            },
            CategorySetRepository::mapVariantCategorySetAssignment,
            "find variant category set assignment")
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public List<VariantCategorySetAssignment> listVariantCategorySetAssignments(
      UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, set_id, category_id, created_at, updated_at"
            + " FROM variant_category_set_assignments"
            + " WHERE tenant_id = ? AND variant_id = ? ORDER BY set_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        CategorySetRepository::mapVariantCategorySetAssignment,
        "list variant category set assignments");
  }

  public boolean deleteVariantCategorySetAssignment(UUID tenantId, UUID variantId, UUID setId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM variant_category_set_assignments"
                      + " WHERE tenant_id = ? AND variant_id = ? AND set_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, variantId);
            ps.setObject(3, setId);
            return ps.executeUpdate() > 0;
          }
        },
        "delete variant category set assignment");
  }

  private static CategorySet mapCategorySet(ResultSet rs) throws SQLException {
    UUID defCat = (UUID) rs.getObject("default_cat_id");
    return new CategorySet(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("purpose"),
        defCat,
        rs.getBoolean("controlled"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static CategorySetMember mapCategorySetMember(ResultSet rs) throws SQLException {
    return new CategorySetMember(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("set_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static VariantCategorySetAssignment mapVariantCategorySetAssignment(ResultSet rs)
      throws SQLException {
    return new VariantCategorySetAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("set_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
