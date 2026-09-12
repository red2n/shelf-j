package com.shelfj.inventory.repo;

import com.shelfj.ids.Ids;
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

  /**
   * Inserts a lot action.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param actionType the action type
   * @param sourceBatchId the source batch id
   * @param resultBatchId the result batch id
   * @param qty the quantity
   * @param notes free-text notes
   * @return the lot action as stored
   */
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
                      + " (id,tenant_id,action_type,source_batch_id,result_batch_id,qty,notes)"
                      + " VALUES (?,?,?,?,?,?,?)"
                      + " RETURNING id,tenant_id,action_type,source_batch_id,"
                      + "result_batch_id,qty,notes,created_at")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setString(3, actionType);
            ps.setObject(4, sourceBatchId);
            ps.setObject(5, resultBatchId);
            ps.setBigDecimal(6, qty);
            ps.setString(7, notes);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapLotAction(rs);
            }
          }
        },
        "insert lot action");
  }

  /**
   * Lists the tenant's lot actions.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param batchId the batch id
   * @return the matching rows
   */
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
