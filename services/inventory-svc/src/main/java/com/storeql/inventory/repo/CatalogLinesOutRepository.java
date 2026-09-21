package com.storeql.inventory.repo;

import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The variants the catalogue has taken out of replenishment (item lifecycle): a line discontinued
 * is sold while stock lasts and never reordered; a line delisted is gone. Kept as a projection of
 * product-svc's lifecycle events, keyed by variant so the low-stock report and the planning run can
 * leave them out without a read of the catalogue.
 */
@ApplicationScoped
public class CatalogLinesOutRepository extends BaseJdbcRepository {

  private static final String UPSERT =
      "INSERT INTO catalog_lines_out (tenant_id, variant_id, product_id, status, changed_at)"
          + " VALUES (?, ?, ?, ?, ?)"
          + " ON CONFLICT (tenant_id, variant_id) DO UPDATE SET"
          + " product_id = EXCLUDED.product_id, status = EXCLUDED.status,"
          + " changed_at = EXCLUDED.changed_at";

  private static final String DELETE_PRODUCT =
      "DELETE FROM catalog_lines_out WHERE tenant_id = ? AND product_id = ?";

  private static final String SELECT_PRODUCT =
      "SELECT variant_id, status FROM catalog_lines_out WHERE tenant_id = ? AND product_id = ?"
          + " ORDER BY variant_id";

  /** A line out of replenishment, as the projection holds it. */
  public record LineOut(UUID variantId, String status) {}

  /**
   * Marks a product's variants out. Idempotent: the same event twice leaves the same rows.
   *
   * @param status DISCONTINUED or DELISTED
   */
  public void markOut(
      UUID tenantId, UUID productId, List<UUID> variantIds, String status, Instant at) {
    inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
            for (UUID variantId : variantIds) {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setObject(3, productId);
              ps.setString(4, status);
              ps.setObject(5, at.atOffset(ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "mark lines out");
  }

  /** Brings a product's variants back into replenishment. Idempotent. */
  public void markIn(UUID tenantId, UUID productId) {
    inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(DELETE_PRODUCT)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, productId);
            ps.executeUpdate();
          }
          return null;
        },
        "mark lines in");
  }

  private static final String SELECT_TENANT =
      "SELECT variant_id FROM catalog_lines_out WHERE tenant_id = ?";

  /** Every variant of the business held out of replenishment. */
  public java.util.Set<UUID> variantsOut(UUID tenantId) {
    return new java.util.HashSet<>(
        query(
            SELECT_TENANT,
            ps -> ps.setObject(1, tenantId),
            rs -> (UUID) rs.getObject(1),
            "variants out"));
  }

  /** The variants of a product held out, if any. */
  public List<LineOut> linesOut(UUID tenantId, UUID productId) {
    return query(
        SELECT_PRODUCT,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        rs -> new LineOut((UUID) rs.getObject(1), rs.getString(2)),
        "lines out");
  }
}
