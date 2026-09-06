package com.shelfj.inventory.repo;

import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Stock movement archival (Tier-1 Gap #30). Extracted from {@code InventoryRepository}:
 * self-contained.
 *
 * <p>Golden rule #8: {@code stock_movements} stays append-only. "Purge" relocates matching rows
 * into {@code stock_movements_archive} (insert + delete in one transaction) instead of destroying
 * them — the hot table shrinks, history is never lost.
 */
@ApplicationScoped
public class MovementArchiveRepository extends BaseJdbcRepository {

  public int purgeMovementsBefore(UUID tenantId, Instant before) {
    OffsetDateTime cutoff = OffsetDateTime.ofInstant(before, ZoneOffset.UTC);
    return inTx(
        c -> {
          try (var insert =
              c.prepareStatement(
                  "INSERT INTO stock_movements_archive"
                      + " (id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, actor_id, created_at)"
                      + " SELECT id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, actor_id, created_at FROM stock_movements"
                      + " WHERE tenant_id=? AND created_at < ?")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, cutoff);
            insert.executeUpdate();
          }
          try (var delete =
              c.prepareStatement(
                  "DELETE FROM stock_movements WHERE tenant_id=? AND created_at < ?")) {
            delete.setObject(1, tenantId);
            delete.setObject(2, cutoff);
            return delete.executeUpdate();
          }
        },
        "archive movements");
  }
}
