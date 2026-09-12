package com.shelfj.product.repo;

import com.shelfj.ids.Ids;
import com.shelfj.product.domain.Domain.CatalogGroup;
import com.shelfj.product.domain.Domain.CatalogGroupElement;
import com.shelfj.product.domain.Domain.VariantCatalogAssignment;
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
 * Catalog groups (Gap #35): tenant-defined element sets assigned to variants (e.g. web-merch
 * attribute groups). Extracted from {@code ProductRepository}: self-contained, no outbox events, no
 * coupling to any other aggregate, so it only needs the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class CatalogGroupRepository extends BaseJdbcRepository {

  /**
   * Inserts a catalog group.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param name the name to set
   * @param description the free-text description
   * @return the catalog group as stored
   */
  public CatalogGroup createCatalogGroup(UUID tenantId, String name, String description) {
    Instant now = Instant.now();
    var g =
        new CatalogGroup(Ids.newId(), tenantId, name, description, CatalogGroup.ACTIVE, now, now);
    exec(
        "INSERT INTO catalog_groups"
            + " (id, tenant_id, name, description, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, g.id());
          ps.setObject(2, g.tenantId());
          ps.setString(3, g.name());
          ps.setString(4, g.description());
          ps.setString(5, g.status());
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create catalog group");
    return g;
  }

  /**
   * Looks a catalog group up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the catalog group to act on
   * @return the catalog group, or empty when it does not exist in this tenant
   */
  public Optional<CatalogGroup> findCatalogGroup(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, description, status, created_at, updated_at"
                + " FROM catalog_groups WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            CatalogGroupRepository::mapCatalogGroup,
            "find catalog group")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's catalog groups.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @return the matching rows
   */
  public List<CatalogGroup> listCatalogGroups(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, description, status, created_at, updated_at"
            + " FROM catalog_groups WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        CatalogGroupRepository::mapCatalogGroup,
        "list catalog groups");
  }

  /**
   * Soft-deletes a catalog group by marking it inactive.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the catalog group to act on
   * @return the catalog group in its deactivated state
   */
  public CatalogGroup deactivateCatalogGroup(UUID tenantId, UUID id) {
    exec(
        "UPDATE catalog_groups SET status = 'INACTIVE', updated_at = ?"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate catalog group");
    return findCatalogGroup(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CATALOG_GROUP_NOT_FOUND", "Catalog group not found"));
  }

  /**
   * Inserts a catalog group element.
   *
   * @param e the catalog to persist
   * @return the catalog group element as stored
   */
  public CatalogGroupElement createCatalogGroupElement(CatalogGroupElement e) {
    exec(
        "INSERT INTO catalog_group_elements"
            + " (id, tenant_id, group_id, element_name, data_type, required,"
            + " default_val, sort_order, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, e.id());
          ps.setObject(2, e.tenantId());
          ps.setObject(3, e.groupId());
          ps.setString(4, e.elementName());
          ps.setString(5, e.dataType());
          ps.setBoolean(6, e.required());
          ps.setString(7, e.defaultVal());
          ps.setInt(8, e.sortOrder());
          ps.setObject(9, e.createdAt().atOffset(ZoneOffset.UTC));
        },
        "create catalog group element");
    return e;
  }

  /**
   * Lists the tenant's catalog group elements.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param groupId the group id
   * @return the matching rows
   */
  public List<CatalogGroupElement> listCatalogGroupElements(UUID tenantId, UUID groupId) {
    return query(
        "SELECT id, tenant_id, group_id, element_name, data_type, required,"
            + " default_val, sort_order, created_at"
            + " FROM catalog_group_elements"
            + " WHERE tenant_id = ? AND group_id = ?"
            + " ORDER BY sort_order, element_name",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, groupId);
        },
        CatalogGroupRepository::mapCatalogGroupElement,
        "list catalog group elements");
  }

  /**
   * Deletes a catalog group element.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the catalog group element to act on
   * @return {@code true} when a row was removed, {@code false} when nothing matched
   */
  public boolean deleteCatalogGroupElement(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM catalog_group_elements WHERE tenant_id = ? AND id = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete catalog group element");
    return found[0] != null;
  }

  /**
   * Inserts a catalog assignment.
   *
   * @param a the catalog to persist
   * @return the catalog assignment as stored
   */
  public VariantCatalogAssignment createCatalogAssignment(VariantCatalogAssignment a) {
    Instant now = Instant.now();
    exec(
        "INSERT INTO variant_catalog_assignments"
            + " (id, tenant_id, variant_id, group_id, element_vals, created_at, updated_at)"
            + " VALUES (?,?,?,?,?::jsonb,?,?)",
        ps -> {
          ps.setObject(1, a.id());
          ps.setObject(2, a.tenantId());
          ps.setObject(3, a.variantId());
          ps.setObject(4, a.groupId());
          ps.setString(5, a.elementVals() != null ? a.elementVals() : "{}");
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create catalog assignment");
    return findCatalogAssignment(a.tenantId(), a.variantId())
        .orElseThrow(
            () ->
                ApiException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment not found after insert"));
  }

  /**
   * Looks a catalog assignment up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @return the catalog assignment, or empty when it does not exist in this tenant
   */
  public Optional<VariantCatalogAssignment> findCatalogAssignment(UUID tenantId, UUID variantId) {
    return query(
            "SELECT id, tenant_id, variant_id, group_id, element_vals, created_at, updated_at"
                + " FROM variant_catalog_assignments WHERE tenant_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            CatalogGroupRepository::mapCatalogAssignment,
            "find catalog assignment")
        .stream()
        .findFirst();
  }

  /**
   * Writes a catalog assignment back with its new values.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @param elementVals the element vals
   * @return the catalog assignment as stored
   */
  public VariantCatalogAssignment updateCatalogAssignment(
      UUID tenantId, UUID variantId, String elementVals) {
    exec(
        "UPDATE variant_catalog_assignments SET element_vals = ?::jsonb, updated_at = ?"
            + " WHERE tenant_id = ? AND variant_id = ?",
        ps -> {
          ps.setString(1, elementVals != null ? elementVals : "{}");
          ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, variantId);
        },
        "update catalog assignment");
    return findCatalogAssignment(tenantId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ASSIGNMENT_NOT_FOUND", "No catalog assignment for this variant"));
  }

  /**
   * Deletes a catalog assignment.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param variantId the product variant concerned
   * @return {@code true} when a row was removed, {@code false} when nothing matched
   */
  public boolean deleteCatalogAssignment(UUID tenantId, UUID variantId) {
    Instant[] found = {null};
    query(
        "DELETE FROM variant_catalog_assignments WHERE tenant_id = ? AND variant_id = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete catalog assignment");
    return found[0] != null;
  }

  private static CatalogGroup mapCatalogGroup(ResultSet rs) throws SQLException {
    return new CatalogGroup(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static CatalogGroupElement mapCatalogGroupElement(ResultSet rs) throws SQLException {
    return new CatalogGroupElement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getString("element_name"),
        rs.getString("data_type"),
        rs.getBoolean("required"),
        rs.getString("default_val"),
        rs.getInt("sort_order"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static VariantCatalogAssignment mapCatalogAssignment(ResultSet rs) throws SQLException {
    return new VariantCatalogAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getString("element_vals"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
