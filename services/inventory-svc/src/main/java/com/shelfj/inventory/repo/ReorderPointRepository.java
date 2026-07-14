package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.ReorderPointPlan;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reorder point / EOQ plans (Gap #19), including the order-modifier fields (min/max qty, lot
 * multiplier) since those live on the same {@code reorder_point_plans} row. Extracted from {@code
 * InventoryRepository}: self-contained, no coupling to the receive/adjust/consume hot path.
 */
@ApplicationScoped
public class ReorderPointRepository extends BaseOutboxRepository {

  public ReorderPointPlan upsertRopPlan(ReorderPointPlan plan, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO reorder_point_plans"
                  + " (id, tenant_id, store_id, variant_id, lead_time_days, ordering_cost,"
                  + "  holding_cost_pct, unit_cost)"
                  + " VALUES (gen_random_uuid(),?,?,?,?,?,?,?)"
                  + " ON CONFLICT (tenant_id, store_id, variant_id) DO UPDATE SET"
                  + "  lead_time_days=EXCLUDED.lead_time_days,"
                  + "  ordering_cost=EXCLUDED.ordering_cost,"
                  + "  holding_cost_pct=EXCLUDED.holding_cost_pct,"
                  + "  unit_cost=EXCLUDED.unit_cost"
                  + " RETURNING id, tenant_id, store_id, variant_id, lead_time_days,"
                  + "  ordering_cost, holding_cost_pct, unit_cost,"
                  + "  avg_daily_demand, rop, eoq, min_order_qty, max_order_qty,"
                  + "  lot_multiplier, computed_at, created_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, plan.tenantId());
            ps.setObject(2, plan.storeId());
            ps.setObject(3, plan.variantId());
            ps.setInt(4, plan.leadTimeDays());
            ps.setBigDecimal(5, plan.orderingCost());
            ps.setBigDecimal(6, plan.holdingCostPct());
            ps.setBigDecimal(7, plan.unitCost());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.unprocessable("ROP_UPSERT_ERROR", "upsert ROP plan failed");
              ReorderPointPlan saved = mapRopPlan(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "upsert rop plan");
  }

  /**
   * Compute ROP + EOQ for all plans in a store that have demand bucket data. ROP = avg_daily *
   * lead_time + safety_stock (from safety_stock_params if present, else 0). EOQ = sqrt(2 * annual *
   * ordering_cost / (unit_cost * holding_cost_pct)).
   */
  public int computeRopPlans(UUID tenantId, UUID storeId) {
    return inTx(
        c -> {
          String sql =
              "UPDATE reorder_point_plans rp"
                  + " SET avg_daily_demand = sub.avg_daily,"
                  + "     rop = ROUND(sub.avg_daily * rp.lead_time_days"
                  + "           + COALESCE(sub.safety_stock_qty, 0), 3),"
                  + "     eoq = CASE WHEN rp.unit_cost > 0 AND rp.holding_cost_pct > 0"
                  + "               THEN ROUND(SQRT(2.0 * sub.avg_daily * 365"
                  + "                    * rp.ordering_cost"
                  + "                    / (rp.unit_cost * rp.holding_cost_pct)), 3)"
                  + "               ELSE NULL END,"
                  + "     computed_at = now()"
                  + " FROM ("
                  + "   SELECT d.variant_id,"
                  + "          COALESCE(AVG(d.demand_qty), 0) / 30.0 AS avg_daily,"
                  + "          MAX(ss.safety_stock_qty) AS safety_stock_qty"
                  + "   FROM demand_history d"
                  + "   LEFT JOIN safety_stock_params ss"
                  + "     ON ss.tenant_id=d.tenant_id AND ss.store_id=d.store_id"
                  + "     AND ss.variant_id=d.variant_id"
                  + "   WHERE d.tenant_id=? AND d.store_id=? AND d.bucket_type='MONTH'"
                  + "   GROUP BY d.variant_id"
                  + " ) sub"
                  + " WHERE rp.tenant_id=? AND rp.store_id=?"
                  + "   AND rp.variant_id = sub.variant_id";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, tenantId);
            ps.setObject(4, storeId);
            return ps.executeUpdate();
          }
        },
        "compute rop plans");
  }

  public Optional<ReorderPointPlan> findRopPlan(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, lead_time_days, ordering_cost,"
                + " holding_cost_pct, unit_cost, avg_daily_demand, rop, eoq, min_order_qty,"
                + " max_order_qty, lot_multiplier, computed_at, created_at"
                + " FROM reorder_point_plans WHERE tenant_id=? AND store_id=? AND variant_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            ReorderPointRepository::mapRopPlan,
            "find rop plan")
        .stream()
        .findFirst();
  }

  public List<ReorderPointPlan> listRopPlans(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, lead_time_days, ordering_cost,"
            + " holding_cost_pct, unit_cost, avg_daily_demand, rop, eoq, min_order_qty,"
            + " max_order_qty, lot_multiplier, computed_at, created_at"
            + " FROM reorder_point_plans WHERE tenant_id=? AND store_id=? ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        ReorderPointRepository::mapRopPlan,
        "list rop plans");
  }

  public ReorderPointPlan updateRopOrderModifiers(
      UUID tenantId,
      UUID id,
      java.math.BigDecimal minOrderQty,
      java.math.BigDecimal maxOrderQty,
      java.math.BigDecimal lotMultiplier) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE reorder_point_plans SET min_order_qty=?,max_order_qty=?,lot_multiplier=?"
                      + " WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,store_id,variant_id,lead_time_days,ordering_cost,"
                      + "holding_cost_pct,unit_cost,avg_daily_demand,rop,eoq,"
                      + "min_order_qty,max_order_qty,lot_multiplier,computed_at,created_at")) {
            ps.setBigDecimal(1, minOrderQty);
            ps.setBigDecimal(2, maxOrderQty);
            ps.setBigDecimal(3, lotMultiplier);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw ApiException.notFound("ROP_PLAN_NOT_FOUND", "No such ROP plan");
              return mapRopPlan(rs);
            }
          }
        },
        "update rop order modifiers");
  }

  private static ReorderPointPlan mapRopPlan(ResultSet rs) throws SQLException {
    OffsetDateTime computedAt = rs.getObject("computed_at", OffsetDateTime.class);
    return new ReorderPointPlan(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getInt("lead_time_days"),
        rs.getBigDecimal("ordering_cost"),
        rs.getBigDecimal("holding_cost_pct"),
        rs.getBigDecimal("unit_cost"),
        rs.getBigDecimal("avg_daily_demand"),
        rs.getBigDecimal("rop"),
        rs.getBigDecimal("eoq"),
        rs.getBigDecimal("min_order_qty"),
        rs.getBigDecimal("max_order_qty"),
        rs.getBigDecimal("lot_multiplier"),
        computedAt == null ? null : computedAt.toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
