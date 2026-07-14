package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.ItemAttributeGroup;
import com.shelfj.product.domain.Domain.ItemAttributeGroupField;
import com.shelfj.product.domain.Domain.VariantAttributeGroupValues;
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
 * Item attribute groups (Gap #36): reference-data attribute schemas and the per-variant JSON values
 * recorded against them. Extracted from {@code ProductRepository}: self-contained, no outbox
 * events, no coupling to any other aggregate, so it only needs the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class ItemAttributeGroupRepository extends BaseJdbcRepository {

  public List<ItemAttributeGroup> listAttributeGroups() {
    return query(
        "SELECT group_code, name, description FROM item_attribute_groups ORDER BY group_code",
        ps -> {},
        ItemAttributeGroupRepository::mapAttributeGroup,
        "list attribute groups");
  }

  public Optional<ItemAttributeGroup> findAttributeGroup(String groupCode) {
    return query(
            "SELECT group_code, name, description FROM item_attribute_groups WHERE group_code = ?",
            ps -> ps.setString(1, groupCode),
            ItemAttributeGroupRepository::mapAttributeGroup,
            "find attribute group")
        .stream()
        .findFirst();
  }

  public List<ItemAttributeGroupField> listAttributeGroupFields(String groupCode) {
    return query(
        "SELECT group_code, field_code, label, data_type, required, sort_order"
            + " FROM item_attribute_group_fields WHERE group_code = ? ORDER BY sort_order",
        ps -> ps.setString(1, groupCode),
        ItemAttributeGroupRepository::mapAttributeGroupField,
        "list attribute group fields");
  }

  public VariantAttributeGroupValues upsertVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode, String values) {
    Instant now = Instant.now();
    exec(
        "INSERT INTO variant_attribute_group_values"
            + " (id, tenant_id, variant_id, group_code, values, created_at, updated_at)"
            + " VALUES (?,?,?,?,?::jsonb,?,?)"
            + " ON CONFLICT (tenant_id, variant_id, group_code)"
            + " DO UPDATE SET values = EXCLUDED.values, updated_at = EXCLUDED.updated_at",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, tenantId);
          ps.setObject(3, variantId);
          ps.setString(4, groupCode);
          ps.setString(5, values != null ? values : "{}");
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "upsert variant attribute group values");
    return findVariantAttributeGroupValues(tenantId, variantId, groupCode)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ATTRIBUTE_GROUP_VALUES_NOT_FOUND", "Attribute group values not found"));
  }

  public Optional<VariantAttributeGroupValues> findVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode) {
    return query(
            "SELECT id, tenant_id, variant_id, group_code, values::text, created_at, updated_at"
                + " FROM variant_attribute_group_values"
                + " WHERE tenant_id = ? AND variant_id = ? AND group_code = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setString(3, groupCode);
            },
            ItemAttributeGroupRepository::mapVariantAttributeGroupValues,
            "find variant attribute group values")
        .stream()
        .findFirst();
  }

  public List<VariantAttributeGroupValues> listVariantAttributeGroupValues(
      UUID tenantId, UUID variantId) {
    return query(
        "SELECT id, tenant_id, variant_id, group_code, values::text, created_at, updated_at"
            + " FROM variant_attribute_group_values"
            + " WHERE tenant_id = ? AND variant_id = ? ORDER BY group_code",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ItemAttributeGroupRepository::mapVariantAttributeGroupValues,
        "list variant attribute group values");
  }

  public boolean deleteVariantAttributeGroupValues(
      UUID tenantId, UUID variantId, String groupCode) {
    Instant[] found = {null};
    query(
        "DELETE FROM variant_attribute_group_values"
            + " WHERE tenant_id = ? AND variant_id = ? AND group_code = ? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
          ps.setString(3, groupCode);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete variant attribute group values");
    return found[0] != null;
  }

  private static ItemAttributeGroup mapAttributeGroup(ResultSet rs) throws SQLException {
    return new ItemAttributeGroup(
        rs.getString("group_code"), rs.getString("name"), rs.getString("description"));
  }

  private static ItemAttributeGroupField mapAttributeGroupField(ResultSet rs) throws SQLException {
    return new ItemAttributeGroupField(
        rs.getString("group_code"),
        rs.getString("field_code"),
        rs.getString("label"),
        rs.getString("data_type"),
        rs.getBoolean("required"),
        rs.getInt("sort_order"));
  }

  private static VariantAttributeGroupValues mapVariantAttributeGroupValues(ResultSet rs)
      throws SQLException {
    return new VariantAttributeGroupValues(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("group_code"),
        rs.getString("values"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
