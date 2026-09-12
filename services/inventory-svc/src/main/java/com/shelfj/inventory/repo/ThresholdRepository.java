package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Reorder thresholds (min-max planning trigger levels). Extracted from {@code InventoryRepository}:
 * self-contained, no outbox events, no coupling to any other aggregate, so it only needs the JDBC
 * infra inherited from {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class ThresholdRepository extends BaseJdbcRepository {

  /**
   * Creates or replaces a threshold.
   *
   * @param t the threshold to persist
   * @return the threshold as stored
   */
  public Threshold upsertThreshold(Threshold t) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO reorder_thresholds"
                      + " (id, tenant_id, store_id, variant_id, threshold, max_qty)"
                      + " VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " DO UPDATE SET threshold = EXCLUDED.threshold,"
                      + " max_qty = EXCLUDED.max_qty"
                      + " RETURNING id, tenant_id, store_id, variant_id, threshold, max_qty")) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setObject(3, t.storeId());
            ps.setObject(4, t.variantId());
            ps.setBigDecimal(5, t.threshold());
            ps.setBigDecimal(6, t.maxQty());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapThreshold(rs);
            }
          }
        },
        "upsert threshold");
  }

  /**
   * Lists the tenant's thresholds.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @return the matching rows
   */
  public List<Threshold> listThresholds(UUID tenantId, UUID storeId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, threshold, max_qty"
                + " FROM reorder_thresholds WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    sb.append(" ORDER BY store_id, variant_id");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        ThresholdRepository::mapThreshold,
        "list thresholds");
  }

  private static Threshold mapThreshold(ResultSet rs) throws SQLException {
    return new Threshold(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("threshold"),
        rs.getBigDecimal("max_qty"));
  }
}
