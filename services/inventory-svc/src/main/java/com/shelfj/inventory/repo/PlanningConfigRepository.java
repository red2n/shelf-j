package com.shelfj.inventory.repo;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.Domain.LotUomConversion;
import com.shelfj.inventory.domain.Domain.ParLevelConfig;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tenant inventory configuration that isn't part of the live stock ledger: lot-specific UOM
 * conversions and replenishment PAR-level configs. Extracted from {@code InventoryRepository}: both
 * are self-contained upsert/read CRUD with their own row-mappers and no coupling to the FIFO /
 * reservation / outbox internals, so they only need the JDBC infra inherited from {@link
 * BaseJdbcRepository}.
 */
@ApplicationScoped
public class PlanningConfigRepository extends BaseJdbcRepository {

  // ── Lot-specific UOM conversions ──────────────────────────────────────────

  public LotUomConversion upsertLotUomConversion(
      UUID tenantId,
      UUID batchId,
      String fromUom,
      String toUom,
      java.math.BigDecimal factor,
      String notes) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO lot_uom_conversions"
                      + " (id,tenant_id,batch_id,from_uom,to_uom,factor,notes)"
                      + " VALUES (?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,batch_id,from_uom,to_uom)"
                      + " DO UPDATE SET factor=EXCLUDED.factor, notes=EXCLUDED.notes"
                      + " RETURNING id,tenant_id,batch_id,from_uom,to_uom,factor,notes,created_at")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, batchId);
            ps.setString(4, fromUom);
            ps.setString(5, toUom);
            ps.setBigDecimal(6, factor);
            ps.setString(7, notes);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapLotUomConversion(rs);
            }
          }
        },
        "upsert lot uom conversion");
  }

  public List<LotUomConversion> listLotUomConversions(UUID tenantId, UUID batchId) {
    return query(
        "SELECT id,tenant_id,batch_id,from_uom,to_uom,factor,notes,created_at"
            + " FROM lot_uom_conversions WHERE tenant_id=? AND batch_id=?"
            + " ORDER BY from_uom,to_uom",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
        },
        PlanningConfigRepository::mapLotUomConversion,
        "list lot uom conversions");
  }

  private static LotUomConversion mapLotUomConversion(ResultSet rs) throws SQLException {
    return new LotUomConversion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("from_uom"),
        rs.getString("to_uom"),
        rs.getBigDecimal("factor"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Replenishment PAR-level configs ───────────────────────────────────────

  public ParLevelConfig upsertParLevel(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      java.math.BigDecimal parQty,
      String uom,
      String reviewCycle) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO par_level_configs"
                      + " (id,tenant_id,store_id,variant_id,par_qty,uom,review_cycle)"
                      + " VALUES (?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,store_id,variant_id)"
                      + " DO UPDATE SET par_qty=EXCLUDED.par_qty, uom=EXCLUDED.uom,"
                      + " review_cycle=EXCLUDED.review_cycle, updated_at=now()"
                      + " RETURNING id,tenant_id,store_id,variant_id,par_qty,uom,"
                      + "review_cycle,created_at,updated_at")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, variantId);
            ps.setBigDecimal(5, parQty);
            ps.setString(6, uom);
            ps.setString(7, reviewCycle == null ? ParLevelConfig.DAILY : reviewCycle);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapParLevel(rs);
            }
          }
        },
        "upsert par level");
  }

  public List<ParLevelConfig> listParLevels(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id,tenant_id,store_id,variant_id,par_qty,uom,review_cycle,created_at,updated_at"
            + " FROM par_level_configs WHERE tenant_id=? AND store_id=? ORDER BY variant_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        PlanningConfigRepository::mapParLevel,
        "list par levels");
  }

  public Optional<ParLevelConfig> findParLevel(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT id,tenant_id,store_id,variant_id,par_qty,uom,review_cycle,created_at,updated_at"
                + " FROM par_level_configs WHERE tenant_id=? AND store_id=? AND variant_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            PlanningConfigRepository::mapParLevel,
            "find par level")
        .stream()
        .findFirst();
  }

  private static ParLevelConfig mapParLevel(ResultSet rs) throws SQLException {
    return new ParLevelConfig(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("par_qty"),
        rs.getString("uom"),
        rs.getString("review_cycle"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
