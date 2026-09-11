package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.SafetyStockParams;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demand history buckets, aggregated from SALE stock movements and consumed by the min-max and
 * safety-stock planning engines. Extracted from {@code InventoryRepository}: self-contained, no
 * outbox events. {@link #demandBucketsBatch} lived physically in the old "safety stock" section but
 * only ever queried {@code demand_history} and returned {@link DemandBucket} rows — it belongs here
 * structurally even though its caller is safety-stock planning.
 */
@ApplicationScoped
public class DemandHistoryRepository extends BaseJdbcRepository {

  /**
   * UPSERT demand buckets by aggregating SALE movements. bucketType is caller-validated
   * (DAY|WEEK|MONTH) and embedded as a literal for use in date_trunc — safe after validation.
   * Returns rows affected.
   */
  public int aggregateDemand(UUID tenantId, UUID storeId, String bucketType, LocalDate since) {
    String trunc =
        switch (bucketType) {
          case "DAY" -> "day";
          case "MONTH" -> "month";
          default -> "week";
        };
    StringBuilder sql =
        new StringBuilder(
            "INSERT INTO demand_history"
                + " (id, tenant_id, store_id, variant_id, bucket_date, bucket_type,"
                + "  demand_qty, movement_count, computed_at)"
                + " SELECT uuid_v7(), sm.tenant_id, sm.store_id, sm.variant_id,"
                + "        date_trunc('"
                + trunc
                + "', sm.created_at)::DATE,"
                + "        '"
                + bucketType
                + "',"
                + "        SUM(ABS(sm.qty)),"
                + "        CAST(COUNT(*) AS INT),"
                + "        now()"
                + " FROM stock_movements sm"
                + " WHERE sm.tenant_id = ? AND sm.type = 'SALE'"
                // A voided till sale is not demand. Excluded rather than netted, because this sums
                // ABS(qty) and the void's RECEIVE would add to it (SJ-D40).
                + " AND NOT EXISTS (SELECT 1 FROM stock_movements v"
                + "                  WHERE v.tenant_id = sm.tenant_id AND v.type = 'RECEIVE' AND v.ref_type = 'VOID'"
                + "                    AND v.ref_id = sm.ref_id AND v.variant_id = sm.variant_id)");
    if (storeId != null) sql.append(" AND sm.store_id = ?");
    if (since != null) sql.append(" AND sm.created_at >= ?");
    sql.append(
        " GROUP BY sm.tenant_id, sm.store_id, sm.variant_id,"
            + " date_trunc('"
            + trunc
            + "', sm.created_at)::DATE"
            + " ON CONFLICT (tenant_id, store_id, variant_id, bucket_date, bucket_type)"
            + " DO UPDATE SET demand_qty = EXCLUDED.demand_qty,"
            + "               movement_count = EXCLUDED.movement_count,"
            + "               computed_at = EXCLUDED.computed_at");
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql.toString())) {
      int i = 1;
      ps.setObject(i++, tenantId);
      if (storeId != null) ps.setObject(i++, storeId);
      if (since != null) ps.setObject(i, since.atStartOfDay().atOffset(ZoneOffset.UTC));
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("aggregate demand", e);
    }
  }

  public List<DemandBucket> listDemandHistory(
      UUID tenantId, UUID storeId, UUID variantId, String bucketType, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, bucket_date, bucket_type,"
                + " demand_qty, movement_count, computed_at"
                + " FROM demand_history WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (bucketType != null) sb.append(" AND bucket_type = ?");
    sb.append(" ORDER BY bucket_date DESC, store_id, variant_id LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (bucketType != null) ps.setString(i++, bucketType);
          ps.setInt(i, limit);
        },
        DemandHistoryRepository::mapDemandBucket,
        "list demand history");
  }

  /**
   * Bulk fetch of the last {@code maxBuckets} daily demand buckets for many (store, variant) pairs
   * in one round trip — avoids an N+1 query per row when {@link
   * com.shelfj.inventory.service.InventoryService#computeSafetyStock} recomputes every row for a
   * tenant. Keyed by {@code storeId} then {@code variantId}; each list is oldest-first like the
   * single-pair method.
   */
  public Map<UUID, Map<UUID, List<DemandBucket>>> demandBucketsBatch(
      UUID tenantId, List<SafetyStockParams> targets, int maxBuckets) {
    Map<UUID, Map<UUID, List<DemandBucket>>> result = new HashMap<>();
    if (targets.isEmpty()) return result;
    UUID[] storeIds = new UUID[targets.size()];
    UUID[] variantIds = new UUID[targets.size()];
    for (int i = 0; i < targets.size(); i++) {
      storeIds[i] = targets.get(i).storeId();
      variantIds[i] = targets.get(i).variantId();
    }
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT store_id, variant_id, id, tenant_id, bucket_date, bucket_type,"
                    + " demand_qty, movement_count, computed_at FROM ("
                    + "  SELECT dh.*, ROW_NUMBER() OVER ("
                    + "    PARTITION BY store_id, variant_id ORDER BY bucket_date DESC) AS rn"
                    + "  FROM demand_history dh"
                    + "  JOIN unnest(?::uuid[], ?::uuid[]) AS pairs(store_id, variant_id)"
                    + "    USING (store_id, variant_id)"
                    + "  WHERE dh.tenant_id = ? AND dh.bucket_type = 'DAY'"
                    + ") x WHERE rn <= ?")) {
      ps.setArray(1, c.createArrayOf("uuid", storeIds));
      ps.setArray(2, c.createArrayOf("uuid", variantIds));
      ps.setObject(3, tenantId);
      ps.setInt(4, maxBuckets);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID storeId = rs.getObject("store_id", UUID.class);
          UUID variantId = rs.getObject("variant_id", UUID.class);
          result
              .computeIfAbsent(storeId, k -> new HashMap<>())
              .computeIfAbsent(variantId, k -> new java.util.ArrayList<>())
              .add(mapDemandBucket(rs));
        }
      }
    } catch (SQLException e) {
      throw dbError("demand buckets batch", e);
    }
    return result;
  }

  private static DemandBucket mapDemandBucket(ResultSet rs) throws SQLException {
    return new DemandBucket(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("bucket_date", LocalDate.class),
        rs.getString("bucket_type"),
        rs.getBigDecimal("demand_qty"),
        rs.getInt("movement_count"),
        rs.getObject("computed_at", OffsetDateTime.class).toInstant());
  }
}
