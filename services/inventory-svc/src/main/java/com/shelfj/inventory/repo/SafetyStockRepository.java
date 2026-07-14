package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.SafetyStockParams;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Safety stock parameters (Gap #8). Extracted from {@code InventoryRepository}: self-contained, no
 * outbox events. Its demand-bucket input comes from {@link DemandHistoryRepository}, called
 * directly by {@code InventoryService} rather than through this repo — see that class's javadoc.
 */
@ApplicationScoped
public class SafetyStockRepository extends BaseJdbcRepository {

  public SafetyStockParams upsertSafetyStockParams(SafetyStockParams p) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO safety_stock_params"
                    + " (id, tenant_id, store_id, variant_id, method, lead_time_days,"
                    + "  service_level_pct, user_defined_pct)"
                    + " VALUES (?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT (tenant_id, store_id, variant_id)"
                    + " DO UPDATE SET method = EXCLUDED.method,"
                    + "   lead_time_days = EXCLUDED.lead_time_days,"
                    + "   service_level_pct = EXCLUDED.service_level_pct,"
                    + "   user_defined_pct = EXCLUDED.user_defined_pct"
                    + " RETURNING id, tenant_id, store_id, variant_id, method, lead_time_days,"
                    + "   service_level_pct, user_defined_pct, safety_stock_qty,"
                    + "   computed_at, created_at")) {
      ps.setObject(1, p.id());
      ps.setObject(2, p.tenantId());
      ps.setObject(3, p.storeId());
      ps.setObject(4, p.variantId());
      ps.setString(5, p.method());
      ps.setInt(6, p.leadTimeDays());
      ps.setBigDecimal(7, p.serviceLevelPct());
      ps.setBigDecimal(8, p.userDefinedPct());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw dbError("upsert safety stock params", new SQLException("no row returned"));
        return mapSafetyStockParams(rs);
      }
    } catch (SQLException e) {
      throw dbError("upsert safety stock params", e);
    }
  }

  public Optional<SafetyStockParams> findSafetyStockParams(
      UUID tenantId, UUID storeId, UUID variantId) {
    List<SafetyStockParams> rows =
        query(
            "SELECT id, tenant_id, store_id, variant_id, method, lead_time_days,"
                + " service_level_pct, user_defined_pct, safety_stock_qty,"
                + " computed_at, created_at"
                + " FROM safety_stock_params WHERE tenant_id = ? AND store_id = ?"
                + " AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            SafetyStockRepository::mapSafetyStockParams,
            "find safety stock params");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<SafetyStockParams> listSafetyStockParams(UUID tenantId, UUID storeId, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, method, lead_time_days,"
                + " service_level_pct, user_defined_pct, safety_stock_qty,"
                + " computed_at, created_at"
                + " FROM safety_stock_params WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          ps.setInt(i, limit);
        },
        SafetyStockRepository::mapSafetyStockParams,
        "list safety stock params");
  }

  /**
   * Applies many safety-stock-qty updates in one connection via JDBC batching, instead of one
   * {@code query()} call (and connection checkout) per row. Keyed the same way as {@link
   * DemandHistoryRepository#demandBucketsBatch}: {@code storeId -> variantId -> newQty}.
   */
  public int updateSafetyStockQtyBatch(
      UUID tenantId, Map<UUID, Map<UUID, BigDecimal>> qtyByStoreThenVariant, Instant computedAt) {
    if (qtyByStoreThenVariant.isEmpty()) return 0;
    return inTx(
        c -> {
          int batched = 0;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE safety_stock_params SET safety_stock_qty = ?, computed_at = ?"
                      + " WHERE tenant_id = ? AND store_id = ? AND variant_id = ?")) {
            for (var storeEntry : qtyByStoreThenVariant.entrySet()) {
              for (var variantEntry : storeEntry.getValue().entrySet()) {
                ps.setBigDecimal(1, variantEntry.getValue());
                ps.setObject(2, computedAt.atOffset(ZoneOffset.UTC));
                ps.setObject(3, tenantId);
                ps.setObject(4, storeEntry.getKey());
                ps.setObject(5, variantEntry.getKey());
                ps.addBatch();
                batched++;
              }
            }
            ps.executeBatch();
          }
          return batched;
        },
        "update safety stock qty batch");
  }

  /** All (store, variant) pairs that have safety stock params for this tenant (optional store). */
  public List<SafetyStockParams> listSafetyStockParamsAll(UUID tenantId, UUID storeId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, method, lead_time_days,"
                + " service_level_pct, user_defined_pct, safety_stock_qty,"
                + " computed_at, created_at"
                + " FROM safety_stock_params WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        SafetyStockRepository::mapSafetyStockParams,
        "list all safety stock params for compute");
  }

  private static SafetyStockParams mapSafetyStockParams(ResultSet rs) throws SQLException {
    OffsetDateTime computedOdt = rs.getObject("computed_at", OffsetDateTime.class);
    return new SafetyStockParams(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("method"),
        rs.getInt("lead_time_days"),
        rs.getBigDecimal("service_level_pct"),
        rs.getBigDecimal("user_defined_pct"),
        rs.getBigDecimal("safety_stock_qty"),
        computedOdt == null ? null : computedOdt.toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
