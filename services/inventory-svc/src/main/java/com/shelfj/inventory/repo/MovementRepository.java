package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only stock movement history. Extracted from {@code InventoryRepository}: the write side
 * (inserting a movement row) stays in the core repo since it's part of the same transaction as
 * every stock mutation (receive/adjust/consume/etc.); this is just the audit-trail read.
 */
@ApplicationScoped
public class MovementRepository extends BaseJdbcRepository {

  public List<Movement> listMovements(
      UUID tenantId, UUID storeId, UUID variantId, String type, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, type, qty, ref_type, ref_id,"
                + " reason_code, created_at FROM stock_movements WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (type != null) sb.append(" AND type = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    String sql = sb.toString();
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i, tenantId);
          i++;
          if (storeId != null) {
            ps.setObject(i, storeId);
            i++;
          }
          if (variantId != null) {
            ps.setObject(i, variantId);
            i++;
          }
          if (type != null) {
            ps.setString(i, type);
            i++;
          }
          ps.setInt(i, limit);
        },
        MovementRepository::mapMovement,
        "list movements");
  }

  private static Movement mapMovement(ResultSet rs) throws SQLException {
    return new Movement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("type"),
        rs.getBigDecimal("qty"),
        rs.getString("ref_type"),
        rs.getObject("ref_id", UUID.class),
        rs.getString("reason_code"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
