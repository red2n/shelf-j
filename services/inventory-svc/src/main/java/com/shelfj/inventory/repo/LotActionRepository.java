package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.LotAction;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lot split/merge audit trail (Tier-1 Gap #23). Extracted from {@code InventoryRepository}: this
 * only records that a split/merge happened — the actual batch quantity mutation runs through the
 * core repo's batch methods separately, called by {@code InventoryService} alongside this.
 */
@ApplicationScoped
public class LotActionRepository extends BaseJdbcRepository {

  public LotAction insertLotAction(
      UUID tenantId,
      String actionType,
      UUID sourceBatchId,
      UUID resultBatchId,
      BigDecimal qty,
      String notes) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO lot_actions"
                      + " (tenant_id,action_type,source_batch_id,result_batch_id,qty,notes)"
                      + " VALUES (?,?,?,?,?,?)"
                      + " RETURNING id,tenant_id,action_type,source_batch_id,"
                      + "result_batch_id,qty,notes,created_at")) {
            ps.setObject(1, tenantId);
            ps.setString(2, actionType);
            ps.setObject(3, sourceBatchId);
            ps.setObject(4, resultBatchId);
            ps.setBigDecimal(5, qty);
            ps.setString(6, notes);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapLotAction(rs);
            }
          }
        },
        "insert lot action");
  }

  public List<LotAction> listLotActions(UUID tenantId, UUID batchId) {
    return query(
        "SELECT id,tenant_id,action_type,source_batch_id,result_batch_id,qty,notes,created_at"
            + " FROM lot_actions WHERE tenant_id=?"
            + " AND (source_batch_id=? OR result_batch_id=?)"
            + " ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
          ps.setObject(3, batchId);
        },
        LotActionRepository::mapLotAction,
        "list lot actions");
  }

  private static LotAction mapLotAction(ResultSet rs) throws SQLException {
    return new LotAction(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("action_type"),
        rs.getObject("source_batch_id", UUID.class),
        rs.getObject("result_batch_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
