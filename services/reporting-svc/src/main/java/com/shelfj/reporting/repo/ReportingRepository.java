package com.shelfj.reporting.repo;

import com.shelfj.reporting.domain.Domain.InventoryProjection;
import com.shelfj.reporting.domain.Domain.MovementStat;
import com.shelfj.reporting.domain.Domain.OpenSupplyLine;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ReportingRepository extends BaseJdbcRepository {

  // ── Inventory projection upserts ─────────────────────────────────────────

  /**
   * Apply one signed stock delta to the projection + movement stats, deduped on eventId. The
   * processed_events mark and both (non-idempotent, additive) writes commit in ONE transaction so a
   * redelivered event is skipped and a crashed write is retried — never applied twice and never
   * lost. Returns false if the event was already processed.
   */
  public boolean applyStockDeltaOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String eventType) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO inventory_projection"
                      + " (tenant_id, store_id, variant_id, on_hand, updated_at)"
                      + " VALUES (?,?,?,?,now())"
                      + " ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " DO UPDATE SET on_hand = inventory_projection.on_hand + EXCLUDED.on_hand,"
                      + "               updated_at = now()")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, variantId);
            ps.setBigDecimal(4, delta);
            ps.executeUpdate();
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO movement_events"
                      + " (tenant_id, store_id, variant_id, event_type, qty_change)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, variantId);
            ps.setString(4, eventType);
            ps.setBigDecimal(5, delta);
            ps.executeUpdate();
          }
          return true;
        },
        "apply stock delta");
  }

  // ── Open supply lines (intransit transfers) ───────────────────────────────

  public void insertSupplyLine(OpenSupplyLine line) {
    exec(
        "INSERT INTO open_supply_lines"
            + " (id, tenant_id, from_store_id, to_store_id, variant_id, qty, event_id)"
            + " VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (id) DO NOTHING",
        ps -> {
          ps.setObject(1, line.id());
          ps.setObject(2, line.tenantId());
          ps.setObject(3, line.fromStoreId());
          ps.setObject(4, line.toStoreId());
          ps.setObject(5, line.variantId());
          ps.setBigDecimal(6, line.qty());
          ps.setObject(7, line.eventId());
        },
        "insert open supply line");
  }

  public void deleteSupplyLinesByEvent(UUID eventId) {
    exec(
        "DELETE FROM open_supply_lines WHERE event_id = ?",
        ps -> ps.setObject(1, eventId),
        "delete supply lines by event");
  }

  // ── Queries ───────────────────────────────────────────────────────────────

  /** Gap #47: cross-store on-hand. Optionally filtered by storeId or variantId. */
  public List<InventoryProjection> queryOnHand(UUID tenantId, UUID storeId, UUID variantId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT tenant_id, store_id, variant_id, on_hand, updated_at"
                + " FROM inventory_projection WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    sb.append(" ORDER BY store_id, variant_id");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i, variantId);
        },
        ReportingRepository::mapProjection,
        "query on-hand");
  }

  /** Gap #48: open supply in transit, optionally filtered by toStoreId or variantId. */
  public List<OpenSupplyLine> querySupplyLines(UUID tenantId, UUID toStoreId, UUID variantId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, from_store_id, to_store_id, variant_id, qty, event_id"
                + " FROM open_supply_lines WHERE tenant_id = ?");
    if (toStoreId != null) sb.append(" AND to_store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (toStoreId != null) ps.setObject(i++, toStoreId);
          if (variantId != null) ps.setObject(i, variantId);
        },
        ReportingRepository::mapSupplyLine,
        "query supply lines");
  }

  /**
   * Gap #49: movement stats aggregated by (store, variant, date-bucket). bucketDays controls the
   * truncation unit: 1=day, 7=week, 30=month (approximate, uses date_trunc).
   */
  public List<MovementStat> queryMovementStats(
      UUID tenantId, UUID storeId, UUID variantId, int bucketDays) {
    String trunc = bucketDays <= 1 ? "day" : bucketDays <= 7 ? "week" : "month";
    StringBuilder sb =
        new StringBuilder(
            "SELECT store_id, variant_id,"
                + "       date_trunc('"
                + trunc
                + "', occurred_at) AS bucket,"
                + "       COALESCE(SUM(CASE WHEN qty_change > 0 THEN qty_change ELSE 0 END),0) AS total_in,"
                + "       COALESCE(SUM(CASE WHEN qty_change < 0 THEN ABS(qty_change) ELSE 0 END),0) AS total_out"
                + " FROM movement_events WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    sb.append(" GROUP BY store_id, variant_id, bucket ORDER BY bucket DESC, store_id, variant_id");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          int i = 2;
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i, variantId);
        },
        ReportingRepository::mapMovementStat,
        "query movement stats");
  }

  // ── Mappers ───────────────────────────────────────────────────────────────

  private static InventoryProjection mapProjection(ResultSet rs) throws SQLException {
    return new InventoryProjection(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("on_hand"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OpenSupplyLine mapSupplyLine(ResultSet rs) throws SQLException {
    return new OpenSupplyLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getObject("event_id", UUID.class));
  }

  private static MovementStat mapMovementStat(ResultSet rs) throws SQLException {
    return new MovementStat(
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("bucket", OffsetDateTime.class).toLocalDate().toString(),
        rs.getBigDecimal("total_in"),
        rs.getBigDecimal("total_out"));
  }
}
