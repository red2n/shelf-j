package com.shelfj.product.repo;

import com.shelfj.product.domain.Domain.ContainerType;
import com.shelfj.product.domain.Domain.VariantContainerLink;
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
 * Container types (Gap #37): shipping/storage container definitions and their per-variant pack
 * links. Extracted from {@code ProductRepository}: self-contained, no outbox events, no coupling to
 * any other aggregate, so it only needs the JDBC infra inherited from {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class ContainerTypeRepository extends BaseJdbcRepository {

  public ContainerType createContainerType(
      UUID tenantId,
      String code,
      String name,
      String description,
      java.math.BigDecimal lengthMm,
      java.math.BigDecimal widthMm,
      java.math.BigDecimal heightMm,
      java.math.BigDecimal maxWeightKg,
      java.math.BigDecimal tareWeightKg,
      Integer maxUnits) {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO container_types"
            + " (id,tenant_id,code,name,description,length_mm,width_mm,height_mm,"
            + "max_weight_kg,tare_weight_kg,max_units,status,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setString(3, code.toUpperCase(java.util.Locale.ROOT));
          ps.setString(4, name);
          ps.setString(5, description);
          ps.setBigDecimal(6, lengthMm);
          ps.setBigDecimal(7, widthMm);
          ps.setBigDecimal(8, heightMm);
          ps.setBigDecimal(9, maxWeightKg);
          ps.setBigDecimal(10, tareWeightKg);
          if (maxUnits != null) ps.setInt(11, maxUnits);
          else ps.setNull(11, java.sql.Types.INTEGER);
          ps.setString(12, ContainerType.ACTIVE);
          ps.setObject(13, now.atOffset(ZoneOffset.UTC));
          ps.setObject(14, now.atOffset(ZoneOffset.UTC));
        },
        "create container type");
    return findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public Optional<ContainerType> findContainerType(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,code,name,description,length_mm,width_mm,height_mm,"
                + "max_weight_kg,tare_weight_kg,max_units,status,created_at,updated_at"
                + " FROM container_types WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ContainerTypeRepository::mapContainerType,
            "find container type")
        .stream()
        .findFirst();
  }

  public List<ContainerType> listContainerTypes(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,code,name,description,length_mm,width_mm,height_mm,"
            + "max_weight_kg,tare_weight_kg,max_units,status,created_at,updated_at"
            + " FROM container_types WHERE tenant_id=? AND status='ACTIVE' ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        ContainerTypeRepository::mapContainerType,
        "list container types");
  }

  public ContainerType updateContainerType(
      UUID tenantId,
      UUID id,
      String name,
      String description,
      java.math.BigDecimal lengthMm,
      java.math.BigDecimal widthMm,
      java.math.BigDecimal heightMm,
      java.math.BigDecimal maxWeightKg,
      java.math.BigDecimal tareWeightKg,
      Integer maxUnits) {
    Instant now = Instant.now();
    exec(
        "UPDATE container_types SET name=?,description=?,length_mm=?,width_mm=?,height_mm=?,"
            + "max_weight_kg=?,tare_weight_kg=?,max_units=?,updated_at=?"
            + " WHERE tenant_id=? AND id=? AND status='ACTIVE'",
        ps -> {
          ps.setString(1, name);
          ps.setString(2, description);
          ps.setBigDecimal(3, lengthMm);
          ps.setBigDecimal(4, widthMm);
          ps.setBigDecimal(5, heightMm);
          ps.setBigDecimal(6, maxWeightKg);
          ps.setBigDecimal(7, tareWeightKg);
          if (maxUnits != null) ps.setInt(8, maxUnits);
          else ps.setNull(8, java.sql.Types.INTEGER);
          ps.setObject(9, now.atOffset(ZoneOffset.UTC));
          ps.setObject(10, tenantId);
          ps.setObject(11, id);
        },
        "update container type");
    return findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public ContainerType deactivateContainerType(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE container_types SET status='INACTIVE', updated_at=? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate container type");
    return findContainerType(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_TYPE_NOT_FOUND", "Container type not found"));
  }

  public VariantContainerLink createVariantContainerLink(
      UUID tenantId, UUID variantId, UUID containerTypeId, int qtyPerContainer, boolean isPrimary) {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO variant_container_links"
            + " (id,tenant_id,variant_id,container_type_id,qty_per_container,is_primary,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, variantId);
          ps.setObject(4, containerTypeId);
          ps.setInt(5, qtyPerContainer);
          ps.setBoolean(6, isPrimary);
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
        },
        "create variant container link");
    return findVariantContainerLink(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("CONTAINER_LINK_NOT_FOUND", "Container link not found"));
  }

  public Optional<VariantContainerLink> findVariantContainerLink(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,variant_id,container_type_id,qty_per_container,is_primary,created_at"
                + " FROM variant_container_links WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            ContainerTypeRepository::mapVariantContainerLink,
            "find variant container link")
        .stream()
        .findFirst();
  }

  public List<VariantContainerLink> listVariantContainerLinks(UUID tenantId, UUID variantId) {
    return query(
        "SELECT id,tenant_id,variant_id,container_type_id,qty_per_container,is_primary,created_at"
            + " FROM variant_container_links WHERE tenant_id=? AND variant_id=? ORDER BY is_primary DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
        },
        ContainerTypeRepository::mapVariantContainerLink,
        "list variant container links");
  }

  public boolean deleteVariantContainerLink(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM variant_container_links WHERE tenant_id=? AND id=? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete variant container link");
    return found[0] != null;
  }

  private static ContainerType mapContainerType(ResultSet rs) throws SQLException {
    return new ContainerType(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getBigDecimal("length_mm"),
        rs.getBigDecimal("width_mm"),
        rs.getBigDecimal("height_mm"),
        rs.getBigDecimal("max_weight_kg"),
        rs.getBigDecimal("tare_weight_kg"),
        rs.getObject("max_units") != null ? rs.getInt("max_units") : null,
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static VariantContainerLink mapVariantContainerLink(ResultSet rs) throws SQLException {
    return new VariantContainerLink(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("container_type_id", UUID.class),
        rs.getInt("qty_per_container"),
        rs.getBoolean("is_primary"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
