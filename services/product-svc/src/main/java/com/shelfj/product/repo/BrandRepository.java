package com.shelfj.product.repo;

import com.shelfj.ids.Ids;
import com.shelfj.product.domain.Domain.Brand;
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
 * Brand CRUD. Extracted from {@code ProductRepository}: self-contained, no outbox events, no
 * coupling to any other aggregate, so it only needs the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class BrandRepository extends BaseJdbcRepository {

  public Brand createBrand(UUID tenantId, String name) {
    Instant now = Instant.now();
    var b = new Brand(Ids.newId(), tenantId, name, Brand.STATUS_ACTIVE, now, now);
    exec(
        "INSERT INTO brands (id, tenant_id, name, status, created_at, updated_at)"
            + " VALUES (?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, b.id());
          ps.setObject(2, b.tenantId());
          ps.setString(3, b.name());
          ps.setString(4, b.status());
          ps.setObject(5, now.atOffset(ZoneOffset.UTC));
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
        },
        "create brand");
    return b;
  }

  public Optional<Brand> findBrandByName(UUID tenantId, String name) {
    return query(
            "SELECT id, tenant_id, name, status, created_at, updated_at"
                + " FROM brands WHERE tenant_id = ? AND name = ? AND status = 'ACTIVE'",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, name);
            },
            BrandRepository::mapBrand,
            "find brand by name")
        .stream()
        .findFirst();
  }

  public Optional<Brand> findBrand(UUID tenantId, UUID id) {
    return query(
            "SELECT id, tenant_id, name, status, created_at, updated_at"
                + " FROM brands WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            BrandRepository::mapBrand,
            "find brand")
        .stream()
        .findFirst();
  }

  public List<Brand> listBrands(UUID tenantId) {
    return query(
        "SELECT id, tenant_id, name, status, created_at, updated_at"
            + " FROM brands WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        BrandRepository::mapBrand,
        "list brands");
  }

  public Brand updateBrand(UUID tenantId, UUID id, String name) {
    Instant now = Instant.now();
    exec(
        "UPDATE brands SET name = ?, updated_at = ? WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'",
        ps -> {
          ps.setString(1, name);
          ps.setObject(2, now.atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, id);
        },
        "update brand");
    return findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  public Brand deactivateBrand(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE brands SET status = 'INACTIVE', updated_at = ? WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate brand");
    return findBrand(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("BRAND_NOT_FOUND", "Brand not found"));
  }

  private static Brand mapBrand(ResultSet rs) throws SQLException {
    return new Brand(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
