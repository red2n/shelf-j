package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.AbcAssignment;
import com.shelfj.inventory.domain.Domain.AbcCompileRun;
import com.shelfj.inventory.domain.Domain.AccountingPeriod;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.CostingMethod;
import com.shelfj.inventory.domain.Domain.CycleCountHeader;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.KanbanCard;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.LotAction;
import com.shelfj.inventory.domain.Domain.LotGenealogyLink;
import com.shelfj.inventory.domain.Domain.MoveOrder;
import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.MoveType;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.PhysicalInventory;
import com.shelfj.inventory.domain.Domain.PhysicalInventoryTag;
import com.shelfj.inventory.domain.Domain.PickingRule;
import com.shelfj.inventory.domain.Domain.PickingRuleAssignment;
import com.shelfj.inventory.domain.Domain.PickingRuleZonePriority;
import com.shelfj.inventory.domain.Domain.ReorderPointPlan;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.SafetyStockParams;
import com.shelfj.inventory.domain.Domain.Suggestion;
import com.shelfj.inventory.domain.Domain.Threshold;
import com.shelfj.inventory.domain.Domain.TransferOrder;
import com.shelfj.inventory.domain.Domain.TransferOrderLine;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stock persistence (JDBC). All mutations are transactional and append a {@code stock_movements}
 * row (append-only ledger). FIFO deduction locks batch rows with {@code FOR UPDATE}. Every query
 * filters tenant_id first.
 */
@ApplicationScoped
public class InventoryRepository extends BaseOutboxRepository {

  // ---------------------------------------------------------------- receive
  /**
   * Create a batch + RECEIVE movement + outbox event, atomically. {@code idempotencyKey} may be
   * null (event-driven receives dedupe via {@link #receiveOnce} instead); when present, a retried
   * call with the same key throws {@code BATCH_DUPLICATE_KEY} (409) instead of double-counting
   * stock — the caller looks the original batch up via {@link #findBatchByIdempotencyKey}.
   */
  public Batch receive(
      Batch batch, String refType, UUID refId, OutboxRow event, String idempotencyKey) {
    return inTx(
        c -> {
          try {
            insertBatch(c, batch, idempotencyKey);
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409, "BATCH_DUPLICATE_KEY", "duplicate idempotency key", List.of(), sqle);
            throw sqle;
          }
          insertMovement(
              c,
              batch.tenantId(),
              batch.storeId(),
              batch.variantId(),
              batch.id(),
              MoveType.RECEIVE,
              batch.receivedQty(),
              refType,
              refId);
          insertOutbox(c, event);
          return batch;
        },
        "receive stock");
  }

  /** Look up a previously-received batch by its idempotency key — used to replay a retry. */
  public Optional<Batch> findBatchByIdempotencyKey(UUID tenantId, String idempotencyKey) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty, remaining_qty,"
                + " cost_price, expiry_date, created_at, status, material_status,"
                + " material_status_reason, grade, zone_id"
                + " FROM inventory_batches WHERE tenant_id=? AND idempotency_key=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            InventoryRepository::mapBatch,
            "find batch by idempotency key")
        .stream()
        .findFirst();
  }

  /**
   * {@link #receive} deduped on {@code dedupeId}: the processed_events mark and the batch creation
   * commit in ONE transaction, so a redelivered event is skipped and a crashed write is retried —
   * never applied twice and never lost. Used by event consumers (a new random batch id per attempt
   * makes plain {@link #receive} non-idempotent under redelivery). Returns false if already
   * processed.
   */
  public boolean receiveOnce(
      UUID dedupeId,
      String consumerName,
      Batch batch,
      String refType,
      UUID refId,
      OutboxRow event) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          insertBatch(c, batch);
          insertMovement(
              c,
              batch.tenantId(),
              batch.storeId(),
              batch.variantId(),
              batch.id(),
              MoveType.RECEIVE,
              batch.receivedQty(),
              refType,
              refId);
          insertOutbox(c, event);
          return true;
        },
        "receive stock (deduped)");
  }

  // ---------------------------------------------------------------- adjust
  /**
   * Adjust on-hand by a signed delta against a chosen batch (or create an adjustment batch if
   * none).
   */
  public void adjust(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event) {
    adjust(tenantId, storeId, variantId, delta, reason, event, null);
  }

  /**
   * As {@link #adjust(UUID, UUID, UUID, BigDecimal, String, OutboxRow)}, but a retried call with
   * the same {@code idempotencyKey} is a no-op instead of double-applying the delta — checked
   * before the deduction is attempted, since a negative delta's FIFO deduction could otherwise fail
   * with INSUFFICIENT_STOCK on retry (the original call already consumed that stock).
   */
  public void adjust(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event,
      String idempotencyKey) {
    inTx(
        c -> {
          if (idempotencyKey != null) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO inventory_adjustment_events (tenant_id, idempotency_key)"
                        + " VALUES (?,?)")) {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
              ps.executeUpdate();
            } catch (SQLException sqle) {
              if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
                return null; // already applied — retry, no-op
              }
              throw sqle;
            }
          }
          adjustTx(c, tenantId, storeId, variantId, delta, reason, event);
          return null;
        },
        "adjust stock");
  }

  private void adjustTx(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event)
      throws SQLException {
    if (delta.signum() >= 0) {
      Batch b =
          new Batch(
              UUID.randomUUID(),
              tenantId,
              storeId,
              variantId,
              "ADJ",
              delta,
              delta,
              null,
              null,
              Instant.now(),
              Batch.STATUS_ACTIVE,
              Batch.MATERIAL_AVAILABLE,
              null,
              null,
              null);
      insertBatch(c, b);
    } else {
      deductFifo(
          c, tenantId, storeId, variantId, delta.negate(), MoveType.ADJUST, "ADJUSTMENT", null);
      checkThresholdTx(c, tenantId, storeId, variantId);
    }
    insertMovement(
        c, tenantId, storeId, variantId, null, MoveType.ADJUST, delta, "ADJUSTMENT", null);
    insertOutbox(c, event);
  }

  /**
   * Lot merge: deduct {@code qty} from the source batch's (store, variant) and add it to the
   * target's, in one transaction. The two legs used to be separate {@link #adjust} calls in
   * separate transactions — a crash between them could silently lose stock with no compensating
   * event. Doing both within a single {@code inTx} makes the merge all-or-nothing.
   */
  public void mergeLotAdjust(
      UUID tenantId,
      UUID sourceStoreId,
      UUID sourceVariantId,
      OutboxRow outEvent,
      UUID targetStoreId,
      UUID targetVariantId,
      OutboxRow inEvent,
      BigDecimal qty) {
    inTx(
        c -> {
          adjustTx(
              c, tenantId, sourceStoreId, sourceVariantId, qty.negate(), "LOT_MERGE_OUT", outEvent);
          adjustTx(c, tenantId, targetStoreId, targetVariantId, qty, "LOT_MERGE_IN", inEvent);
          return null;
        },
        "merge lot");
  }

  // ---------------------------------------------------------------- reserve
  /**
   * Hold stock if available. Inserts a HELD reservation + RESERVE movement + outbox. Throws 409 if
   * short. If {@code idempotencyKey} matches an already-held reservation, that reservation is
   * returned unchanged (replay) — checked *before* the availability check, since the original
   * hold's own qty is already counted against availability and would otherwise make a retry of a
   * fully-successful reservation look like it's short on stock.
   */
  public Reservation reserve(Reservation r, OutboxRow event, String idempotencyKey) {
    return inTx(
        c -> {
          if (idempotencyKey != null) {
            Reservation existing =
                findReservationByIdempotencyKeyTx(c, r.tenantId(), idempotencyKey);
            if (existing != null) {
              return existing;
            }
          }
          BigDecimal available = availableForUpdate(c, r.tenantId(), r.storeId(), r.variantId());
          if (available.compareTo(r.qty()) < 0) {
            throw ApiException.unprocessable(
                "INSUFFICIENT_STOCK",
                "Only "
                    + available.toPlainString()
                    + " available, requested "
                    + r.qty().toPlainString());
          }
          try {
            insertReservation(c, r, idempotencyKey);
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409, "RESERVATION_DUPLICATE_KEY", "duplicate idempotency key", List.of(), sqle);
            throw sqle;
          }
          insertMovement(
              c,
              r.tenantId(),
              r.storeId(),
              r.variantId(),
              null,
              MoveType.RESERVE,
              r.qty().negate(),
              "RESERVATION",
              r.id());
          insertOutbox(c, event);
          return r;
        },
        "reserve stock");
  }

  // ---------------------------------------------------------------- consume (FIFO deduct)
  /**
   * Consume a HELD reservation: FIFO-deduct from batches, mark CONSUMED, SALE movements + outbox.
   */
  public void consume(UUID tenantId, UUID reservationId) {
    inTx(
        c -> {
          Reservation r = loadReservationForUpdate(c, tenantId, reservationId);
          if (!Reservation.HELD.equals(r.status())) {
            throw ApiException.unprocessable(
                "RESERVATION_NOT_HELD", "Reservation is " + r.status());
          }
          Optional<PickingRule> rule = resolvePickingRule(tenantId, r.storeId(), r.variantId());
          List<UUID> zonePriorities =
              rule.filter(rr -> PickingRule.ZONE_PRIORITY.equals(rr.strategy()))
                  .map(
                      rr ->
                          listZonePriorities(tenantId, rr.id()).stream()
                              .map(PickingRuleZonePriority::zoneId)
                              .toList())
                  .orElse(null);
          deductBatches(
              c,
              tenantId,
              r.storeId(),
              r.variantId(),
              r.qty(),
              MoveType.SALE,
              "ORDER",
              r.orderId(),
              rule.map(PickingRule::strategy).orElse(null),
              rule.map(PickingRule::gradePreference).orElse(null),
              zonePriorities);
          checkThresholdTx(c, tenantId, r.storeId(), r.variantId());
          setReservationStatus(c, reservationId, Reservation.CONSUMED);
          // Event built here (not in service layer) because storeId/variantId/qty are only
          // known after loading the reservation inside this transaction.
          insertOutbox(
              c,
              new OutboxRow(
                  "StockDeducted",
                  "shelfj.inventory.stock-deducted",
                  tenantId,
                  reservationId,
                  com.shelfj.inventory.service.Events.stockDeducted(
                      tenantId, r.storeId(), r.variantId(), reservationId, r.qty())));
          return null;
        },
        "consume reservation");
  }

  // ---------------------------------------------------------------- deductSale (Gap #50 POS→SIM)
  /**
   * FIFO-deduct for a POS sale driven by an OrderFulfilled event (no prior reservation). Creates
   * SALE movements, checks thresholds, and publishes the StockDeducted outbox event — all in one
   * transaction.
   */
  public void deductSale(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, UUID orderId, OutboxRow event) {
    inTx(
        c -> {
          deductFifo(c, tenantId, storeId, variantId, qty, MoveType.SALE, "ORDER", orderId);
          checkThresholdTx(c, tenantId, storeId, variantId);
          insertOutbox(c, event);
          return null;
        },
        "deduct sale from order");
  }

  /**
   * {@link #deductSale} deduped on {@code dedupeId}: mark + FIFO deduction commit in ONE
   * transaction (see {@link #receiveOnce}). Returns false if already processed.
   */
  public boolean deductSaleOnce(
      UUID dedupeId,
      String consumerName,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      UUID orderId,
      OutboxRow event) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          deductFifo(c, tenantId, storeId, variantId, qty, MoveType.SALE, "ORDER", orderId);
          checkThresholdTx(c, tenantId, storeId, variantId);
          insertOutbox(c, event);
          return true;
        },
        "deduct sale from order (deduped)");
  }

  // ---------------------------------------------------------------- release
  /**
   * Release a HELD reservation (returns the held qty to availability): RELEASE movement + outbox.
   */
  public boolean release(UUID tenantId, UUID reservationId, OutboxRow event) {
    return inTx(
        c -> {
          Reservation r = loadReservationForUpdate(c, tenantId, reservationId);
          if (!Reservation.HELD.equals(r.status())) {
            return false; // already consumed/released — idempotent no-op
          }
          insertMovement(
              c,
              tenantId,
              r.storeId(),
              r.variantId(),
              null,
              MoveType.RELEASE,
              r.qty(),
              "RESERVATION",
              reservationId);
          setReservationStatus(c, reservationId, Reservation.RELEASED);
          insertOutbox(c, event);
          return true;
        },
        "release reservation");
  }

  /** Find HELD reservations that have expired (for the sweeper). */
  public List<UUID> expiredHeldReservations(int limit) {
    return query(
        "SELECT id FROM reservations WHERE status = 'HELD'"
            + " AND expires_at IS NOT NULL AND expires_at < now() LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs -> rs.getObject("id", UUID.class),
        "find expired reservations");
  }

  public record ReservationRef(UUID id, UUID tenantId) {}

  /** Fetch expired reservations with their tenant in one query — avoids N+1 in the sweeper. */
  public List<ReservationRef> expiredHeldReservationsWithTenant(int limit) {
    return query(
        "SELECT id, tenant_id FROM reservations WHERE status = 'HELD'"
            + " AND expires_at IS NOT NULL AND expires_at < now() LIMIT ?",
        ps -> ps.setInt(1, limit),
        rs ->
            new ReservationRef(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
        "find expired reservations with tenant");
  }

  public UUID tenantOfReservation(UUID reservationId) {
    var list =
        query(
            "SELECT tenant_id FROM reservations WHERE id = ?",
            ps -> ps.setObject(1, reservationId),
            rs -> rs.getObject("tenant_id", UUID.class),
            "tenant of reservation");
    return list.isEmpty() ? null : list.get(0);
  }

  // ---------------------------------------------------------------- levels
  /**
   * On-hand (sum remaining batches), reserved (sum HELD), available = on-hand − reserved, per
   * (store,variant).
   */
  public List<Level> levels(UUID tenantId, UUID storeId) {
    String sql =
        """
                SELECT b.store_id, b.variant_id,
                       COALESCE(SUM(b.remaining_qty),0) AS on_hand,
                       COALESCE(MAX(res.reserved),0) AS reserved
                FROM inventory_batches b
                LEFT JOIN (
                    SELECT store_id, variant_id, SUM(qty) AS reserved
                    FROM reservations WHERE tenant_id = ? AND status = 'HELD'
                    GROUP BY store_id, variant_id
                ) res ON res.store_id = b.store_id AND res.variant_id = b.variant_id
                WHERE b.tenant_id = ? AND b.material_status = 'AVAILABLE'"""
            + (storeId != null ? " AND b.store_id = ?" : "")
            + """

                GROUP BY b.store_id, b.variant_id
                ORDER BY b.store_id, b.variant_id""";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, tenantId);
          if (storeId != null) ps.setObject(3, storeId);
        },
        rs -> {
          BigDecimal onHand = rs.getBigDecimal("on_hand");
          BigDecimal reserved = rs.getBigDecimal("reserved");
          return new Level(
              rs.getObject("store_id", UUID.class),
              rs.getObject("variant_id", UUID.class),
              onHand,
              reserved,
              onHand.subtract(reserved));
        },
        "load levels");
  }

  // ---------------------------------------------------------------- batches (read)

  public List<Batch> listBatches(
      UUID tenantId, UUID storeId, UUID variantId, String materialStatus, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status,"
                + " material_status, material_status_reason, grade, zone_id"
                + " FROM inventory_batches WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (materialStatus != null) sb.append(" AND material_status = ?");
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
          if (materialStatus != null) {
            ps.setString(i, materialStatus);
            i++;
          }
          ps.setInt(i, limit);
        },
        InventoryRepository::mapBatch,
        "list batches");
  }

  public Optional<Batch> getBatch(UUID tenantId, UUID batchId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status,"
                + " material_status, material_status_reason, grade, zone_id"
                + " FROM inventory_batches WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, batchId);
            },
            InventoryRepository::mapBatch,
            "get batch");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  // ---------------------------------------------------------------- movements (read)

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
        InventoryRepository::mapMovement,
        "list movements");
  }

  // ---------------------------------------------------------------- reservations (read)

  public List<Reservation> listReservations(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at FROM reservations WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (status != null) sb.append(" AND status = ?");
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
          if (status != null) {
            ps.setString(i, status);
            i++;
          }
          ps.setInt(i, limit);
        },
        InventoryRepository::mapReservation,
        "list reservations");
  }

  public Optional<Reservation> findReservation(UUID tenantId, UUID reservationId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at FROM reservations WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, reservationId);
            },
            InventoryRepository::mapReservation,
            "get reservation");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** Look up a previously-held reservation by its idempotency key — used to replay a retry. */
  public Optional<Reservation> findReservationByIdempotencyKey(
      UUID tenantId, String idempotencyKey) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at FROM reservations WHERE tenant_id = ? AND idempotency_key = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, idempotencyKey);
            },
            InventoryRepository::mapReservation,
            "find reservation by idempotency key");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  private static Reservation findReservationByIdempotencyKeyTx(
      Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at FROM reservations WHERE tenant_id = ? AND idempotency_key = ?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapReservation(rs) : null;
      }
    }
  }

  // ---------------------------------------------------------------- thresholds

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
        InventoryRepository::mapThreshold,
        "list thresholds");
  }

  // ---------------------------------------------------------------- material status

  /** Change the physical condition of a batch; emits MaterialStatusChanged outbox event. */
  public Batch updateMaterialStatus(
      UUID tenantId, UUID batchId, String materialStatus, String reason, OutboxRow event) {
    return inTx(
        c -> {
          Batch updated;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE inventory_batches"
                      + " SET material_status=?, material_status_reason=?,"
                      + " material_status_changed_at=now()"
                      + " WHERE tenant_id=? AND id=?"
                      + " RETURNING id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                      + " remaining_qty, cost_price, expiry_date, created_at, status,"
                      + " material_status, material_status_reason, grade, zone_id")) {
            ps.setString(1, materialStatus);
            ps.setString(2, reason);
            ps.setObject(3, tenantId);
            ps.setObject(4, batchId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw ApiException.notFound("BATCH_NOT_FOUND", "No such batch");
              }
              updated = mapBatch(rs);
            }
          }
          insertOutbox(c, event);
          return updated;
        },
        "update material status");
  }

  // ---------------------------------------------------------------- suggestions

  /**
   * Insert a replenishment suggestion. Returns the suggestion if inserted; empty if an OPEN
   * suggestion already exists for the same (tenant, store, variant) — idempotent via unique partial
   * index.
   */
  public Optional<Suggestion> insertSuggestionIfAbsent(Suggestion s, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO replenishment_suggestions"
                      + " (id, tenant_id, store_id, variant_id, available_qty,"
                      + " min_qty, max_qty, suggested_qty, status, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " WHERE status = 'OPEN' DO NOTHING")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.storeId());
            ps.setObject(4, s.variantId());
            ps.setBigDecimal(5, s.availableQty());
            ps.setBigDecimal(6, s.minQty());
            ps.setBigDecimal(7, s.maxQty());
            ps.setBigDecimal(8, s.suggestedQty());
            ps.setString(9, s.status());
            ps.setObject(10, s.createdAt().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() == 0) return Optional.<Suggestion>empty();
          }
          insertOutbox(c, event);
          return Optional.of(s);
        },
        "insert suggestion");
  }

  public List<Suggestion> listSuggestions(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, available_qty, min_qty, max_qty,"
                + " suggested_qty, status, created_at, resolved_at"
                + " FROM replenishment_suggestions WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapSuggestion,
        "list suggestions");
  }

  /** Transition an OPEN suggestion → ORDERED or CANCELLED; emits outbox event. */
  public Optional<Suggestion> resolveSuggestion(
      UUID tenantId, UUID suggId, String newStatus, OutboxRow event) {
    return inTx(
        c -> {
          Suggestion updated;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE replenishment_suggestions"
                      + " SET status = ?, resolved_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'"
                      + " RETURNING id, tenant_id, store_id, variant_id, available_qty,"
                      + " min_qty, max_qty, suggested_qty, status, created_at, resolved_at")) {
            ps.setString(1, newStatus);
            ps.setObject(2, tenantId);
            ps.setObject(3, suggId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<Suggestion>empty();
              updated = mapSuggestion(rs);
            }
          }
          insertOutbox(c, event);
          return Optional.of(updated);
        },
        "resolve suggestion");
  }

  // ---------------------------------------------------------------- demand history

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
                + " SELECT gen_random_uuid(), sm.tenant_id, sm.store_id, sm.variant_id,"
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
                + " WHERE sm.tenant_id = ? AND sm.type = 'SALE'");
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
        InventoryRepository::mapDemandBucket,
        "list demand history");
  }

  // ---------------------------------------------------------------- cycle counting (Gap #10)

  public CycleCountHeader createCycleCountHeader(
      CycleCountHeader header, List<CycleCountLine> lines) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO cycle_count_headers"
                      + " (id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                      + "  status, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, header.id());
            ps.setObject(2, header.tenantId());
            ps.setObject(3, header.storeId());
            ps.setString(4, header.name());
            ps.setString(5, header.abcClasses());
            ps.setBigDecimal(6, header.tolerancePct());
            ps.setString(7, header.status());
            ps.setObject(8, header.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          if (!lines.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO cycle_count_lines"
                        + " (id, tenant_id, header_id, store_id, variant_id, system_qty)"
                        + " VALUES (?,?,?,?,?,?)")) {
              for (CycleCountLine l : lines) {
                ps.setObject(1, l.id());
                ps.setObject(2, l.tenantId());
                ps.setObject(3, l.headerId());
                ps.setObject(4, l.storeId());
                ps.setObject(5, l.variantId());
                ps.setBigDecimal(6, l.systemQty());
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return header;
        },
        "create cycle count");
  }

  public List<CycleCountHeader> listCycleCountHeaders(
      UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                + " status, created_at, completed_at"
                + " FROM cycle_count_headers WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapCycleCountHeader,
        "list cycle count headers");
  }

  public Optional<CycleCountHeader> findCycleCountHeader(UUID tenantId, UUID headerId) {
    List<CycleCountHeader> rows =
        query(
            "SELECT id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                + " status, created_at, completed_at"
                + " FROM cycle_count_headers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, headerId);
            },
            InventoryRepository::mapCycleCountHeader,
            "find cycle count header");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<CycleCountLine> listCycleCountLines(UUID headerId) {
    return query(
        "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
            + " counted_qty, variance, variance_pct, status, counted_at"
            + " FROM cycle_count_lines WHERE header_id = ? ORDER BY variant_id",
        ps -> ps.setObject(1, headerId),
        InventoryRepository::mapCycleCountLine,
        "list cycle count lines");
  }

  public Optional<CycleCountLine> findCycleCountLine(UUID tenantId, UUID lineId) {
    List<CycleCountLine> rows =
        query(
            "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
                + " counted_qty, variance, variance_pct, status, counted_at"
                + " FROM cycle_count_lines WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, lineId);
            },
            InventoryRepository::mapCycleCountLine,
            "find cycle count line");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Record counted_qty + computed variance on one line; set status COUNTED. */
  public Optional<CycleCountLine> enterCount(
      UUID tenantId,
      UUID lineId,
      BigDecimal countedQty,
      BigDecimal variance,
      BigDecimal variancePct) {
    List<CycleCountLine> rows =
        query(
            "UPDATE cycle_count_lines"
                + " SET counted_qty = ?, variance = ?, variance_pct = ?,"
                + "     status = 'COUNTED', counted_at = now()"
                + " WHERE tenant_id = ? AND id = ? AND status IN ('OPEN','COUNTED')"
                + " RETURNING id, tenant_id, header_id, store_id, variant_id, system_qty,"
                + "   counted_qty, variance, variance_pct, status, counted_at",
            ps -> {
              ps.setBigDecimal(1, countedQty);
              ps.setBigDecimal(2, variance);
              ps.setBigDecimal(3, variancePct);
              ps.setObject(4, tenantId);
              ps.setObject(5, lineId);
            },
            InventoryRepository::mapCycleCountLine,
            "enter count");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Bulk-set status on lines; returns count updated. */
  public int bulkUpdateLineStatus(UUID headerId, List<UUID> lineIds, String newStatus) {
    if (lineIds.isEmpty()) return 0;
    try (var c = dataSource.getConnection()) {
      StringBuilder sb =
          new StringBuilder(
              "UPDATE cycle_count_lines SET status = ? WHERE header_id = ? AND id = ANY(?)");
      try (var ps = c.prepareStatement(sb.toString())) {
        ps.setString(1, newStatus);
        ps.setObject(2, headerId);
        ps.setArray(3, c.createArrayOf("uuid", lineIds.toArray()));
        return ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw dbError("bulk update line status", e);
    }
  }

  /** Update header status. Returns updated header or empty if not found. */
  public Optional<CycleCountHeader> updateHeaderStatus(
      UUID tenantId, UUID headerId, String newStatus) {
    List<CycleCountHeader> rows =
        query(
            "UPDATE cycle_count_headers SET status = ?,"
                + " completed_at = CASE WHEN ? IN ('ADJUSTED','CLOSED') THEN now()"
                + "                     ELSE completed_at END"
                + " WHERE tenant_id = ? AND id = ?"
                + " RETURNING id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                + "   status, created_at, completed_at",
            ps -> {
              ps.setString(1, newStatus);
              ps.setString(2, newStatus);
              ps.setObject(3, tenantId);
              ps.setObject(4, headerId);
            },
            InventoryRepository::mapCycleCountHeader,
            "update header status");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Apply stock adjustments for all APPROVED lines and mark them ADJUSTED in one transaction.
   * Returns the number of lines adjusted.
   */
  public int applyAdjustments(UUID tenantId, UUID headerId, OutboxRow event) {
    return inTx(
        c -> {
          List<CycleCountLine> approved =
              query(
                  "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
                      + " counted_qty, variance, variance_pct, status, counted_at"
                      + " FROM cycle_count_lines"
                      + " WHERE header_id = ? AND status = 'APPROVED'",
                  ps -> ps.setObject(1, headerId),
                  InventoryRepository::mapCycleCountLine,
                  "list approved lines for adjustment");
          for (CycleCountLine line : approved) {
            if (line.variance() == null || line.variance().signum() == 0) continue;
            if (line.variance().signum() > 0) {
              // positive variance: system under-counted — add stock
              Batch adj =
                  new Batch(
                      UUID.randomUUID(),
                      tenantId,
                      line.storeId(),
                      line.variantId(),
                      "CC-" + headerId.toString().substring(0, 8),
                      line.variance(),
                      line.variance(),
                      null,
                      null,
                      Instant.now(),
                      Batch.STATUS_ACTIVE,
                      Batch.MATERIAL_AVAILABLE,
                      null,
                      null,
                      null);
              insertBatch(c, adj);
              insertMovement(
                  c,
                  tenantId,
                  line.storeId(),
                  line.variantId(),
                  adj.id(),
                  MoveType.ADJUST,
                  line.variance(),
                  "CYCLE_COUNT",
                  headerId);
            } else {
              // negative variance: system over-counted — deduct stock
              deductFifo(
                  c,
                  tenantId,
                  line.storeId(),
                  line.variantId(),
                  line.variance().negate(),
                  MoveType.ADJUST,
                  "CYCLE_COUNT",
                  headerId);
            }
          }
          if (!approved.isEmpty()) {
            List<UUID> approvedIds = approved.stream().map(CycleCountLine::id).toList();
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE cycle_count_lines SET status = 'ADJUSTED'"
                        + " WHERE header_id = ? AND id = ANY(?)")) {
              ps.setObject(1, headerId);
              ps.setArray(2, c.createArrayOf("uuid", approvedIds.toArray()));
              ps.executeUpdate();
            }
          }
          insertOutbox(c, event);
          // Mark header ADJUSTED in the same transaction so a crash cannot leave stock adjusted
          // with an open header (which would allow a second adjustment on re-run).
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE cycle_count_headers SET status='ADJUSTED', completed_at=now()"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, headerId);
            ps.executeUpdate();
          }
          return approved.size();
        },
        "apply cycle count adjustments");
  }

  /** Returns on-hand available qty for a (store, variant). Used when generating lines. */
  public BigDecimal onHandQty(UUID tenantId, UUID storeId, UUID variantId) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT COALESCE(SUM(remaining_qty),0) AS q"
                    + " FROM inventory_batches"
                    + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                    + " AND material_status='AVAILABLE'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("q") : BigDecimal.ZERO;
      }
    } catch (SQLException e) {
      throw dbError("on-hand qty", e);
    }
  }

  /**
   * Bulk on-hand query for a set of variants in one store — avoids N+1 when building cycle count
   * lines.
   */
  public java.util.Map<UUID, BigDecimal> onHandQtyBatch(
      UUID tenantId, UUID storeId, java.util.Collection<UUID> variantIds) {
    if (variantIds.isEmpty()) return java.util.Map.of();
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT variant_id, COALESCE(SUM(remaining_qty),0) AS q"
                    + " FROM inventory_batches"
                    + " WHERE tenant_id=? AND store_id=? AND variant_id=ANY(?)"
                    + " AND material_status='AVAILABLE'"
                    + " GROUP BY variant_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setArray(3, c.createArrayOf("uuid", variantIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        java.util.Map<UUID, BigDecimal> result = new java.util.HashMap<>();
        while (rs.next()) {
          result.put(rs.getObject("variant_id", UUID.class), rs.getBigDecimal("q"));
        }
        return result;
      }
    } catch (SQLException e) {
      throw dbError("on-hand qty batch", e);
    }
  }

  /** Bulk fetch of cycle count lines for multiple headers — avoids N+1 in listCycleCounts. */
  public java.util.Map<UUID, List<CycleCountLine>> listCycleCountLinesByHeaders(
      java.util.Collection<UUID> headerIds) {
    if (headerIds.isEmpty()) return java.util.Map.of();
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
                    + " counted_qty, variance, variance_pct, status, counted_at"
                    + " FROM cycle_count_lines WHERE header_id=ANY(?) ORDER BY variant_id")) {
      ps.setArray(1, c.createArrayOf("uuid", headerIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        java.util.Map<UUID, List<CycleCountLine>> result = new java.util.HashMap<>();
        while (rs.next()) {
          CycleCountLine line = mapCycleCountLine(rs);
          result.computeIfAbsent(line.headerId(), k -> new java.util.ArrayList<>()).add(line);
        }
        return result;
      }
    } catch (SQLException e) {
      throw dbError("list cycle count lines by headers", e);
    }
  }

  private static CycleCountHeader mapCycleCountHeader(ResultSet rs) throws SQLException {
    OffsetDateTime completedOdt = rs.getObject("completed_at", OffsetDateTime.class);
    return new CycleCountHeader(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("name"),
        rs.getString("abc_classes"),
        rs.getBigDecimal("tolerance_pct"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        completedOdt == null ? null : completedOdt.toInstant());
  }

  private static CycleCountLine mapCycleCountLine(ResultSet rs) throws SQLException {
    OffsetDateTime countedOdt = rs.getObject("counted_at", OffsetDateTime.class);
    return new CycleCountLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("header_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("system_qty"),
        rs.getBigDecimal("counted_qty"),
        rs.getBigDecimal("variance"),
        rs.getBigDecimal("variance_pct"),
        rs.getString("status"),
        countedOdt == null ? null : countedOdt.toInstant());
  }

  // ---------------------------------------------------------------- ABC analysis (Gap #9)

  /** Insert a compile run header and bulk-upsert all assignments in one transaction. */
  public AbcCompileRun persistAbcRun(AbcCompileRun run, List<AbcAssignment> assignments) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO abc_compile_runs"
                      + " (id, tenant_id, store_id, criteria, threshold_a, threshold_ab,"
                      + "  items_compiled, compiled_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, run.id());
            ps.setObject(2, run.tenantId());
            ps.setObject(3, run.storeId());
            ps.setString(4, run.criteria());
            ps.setBigDecimal(5, run.thresholdA());
            ps.setBigDecimal(6, run.thresholdAB());
            ps.setInt(7, run.itemsCompiled());
            ps.setObject(8, run.compiledAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          if (!assignments.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO abc_assignments"
                        + " (id, tenant_id, store_id, variant_id, run_id, class, score, rank)"
                        + " VALUES (?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (tenant_id, store_id, variant_id)"
                        + " DO UPDATE SET run_id = EXCLUDED.run_id,"
                        + "   class = EXCLUDED.class,"
                        + "   score = EXCLUDED.score,"
                        + "   rank  = EXCLUDED.rank,"
                        + "   assigned_at = now()")) {
              for (AbcAssignment a : assignments) {
                ps.setObject(1, a.id());
                ps.setObject(2, a.tenantId());
                ps.setObject(3, a.storeId());
                ps.setObject(4, a.variantId());
                ps.setObject(5, a.runId());
                ps.setString(6, a.abcClass());
                ps.setBigDecimal(7, a.score());
                ps.setInt(8, a.rank());
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return run;
        },
        "persist abc run");
  }

  public List<AbcAssignment> listAbcAssignments(
      UUID tenantId, UUID storeId, String abcClass, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, run_id, class, score, rank, assigned_at"
                + " FROM abc_assignments WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (abcClass != null) sb.append(" AND class = ?");
    sb.append(" ORDER BY store_id, rank ASC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (abcClass != null) ps.setString(i++, abcClass);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapAbcAssignment,
        "list abc assignments");
  }

  public Optional<AbcAssignment> findAbcAssignment(UUID tenantId, UUID storeId, UUID variantId) {
    List<AbcAssignment> rows =
        query(
            "SELECT id, tenant_id, store_id, variant_id, run_id, class, score, rank, assigned_at"
                + " FROM abc_assignments WHERE tenant_id = ? AND store_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            InventoryRepository::mapAbcAssignment,
            "find abc assignment");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Fetch the data needed for ABC scoring: variant_id, total demand qty, and avg cost price. Joins
   * demand_history (DAY buckets, last 365 days) with the latest cost_price from batches. Returns
   * one row per (store, variant) pair.
   */
  public List<Object[]> abcScoringData(UUID tenantId, UUID storeId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT dh.store_id, dh.variant_id,"
                + " COALESCE(SUM(dh.demand_qty), 0) AS total_demand,"
                + " COALESCE(AVG(b.cost_price), 1)  AS avg_cost"
                + " FROM demand_history dh"
                + " LEFT JOIN inventory_batches b"
                + "   ON b.tenant_id = dh.tenant_id AND b.store_id = dh.store_id"
                + "   AND b.variant_id = dh.variant_id AND b.cost_price IS NOT NULL"
                + " WHERE dh.tenant_id = ? AND dh.bucket_type = 'DAY'"
                + "   AND dh.bucket_date >= CURRENT_DATE - INTERVAL '365 days'");
    if (storeId != null) sb.append(" AND dh.store_id = ?");
    sb.append(" GROUP BY dh.store_id, dh.variant_id");
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sb.toString())) {
      ps.setObject(1, tenantId);
      if (storeId != null) ps.setObject(2, storeId);
      List<Object[]> rows = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new Object[] {
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("total_demand"),
                rs.getBigDecimal("avg_cost")
              });
        }
      }
      return rows;
    } catch (SQLException e) {
      throw dbError("abc scoring data", e);
    }
  }

  private static AbcAssignment mapAbcAssignment(ResultSet rs) throws SQLException {
    return new AbcAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("class"),
        rs.getBigDecimal("score"),
        rs.getInt("rank"),
        rs.getObject("assigned_at", OffsetDateTime.class).toInstant());
  }

  // ---------------------------------------------------------------- safety stock (Gap #8)

  public SafetyStockParams upsertSafetyStockParams(
      com.shelfj.inventory.domain.Domain.SafetyStockParams p) {
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
            InventoryRepository::mapSafetyStockParams,
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
        InventoryRepository::mapSafetyStockParams,
        "list safety stock params");
  }

  public Optional<SafetyStockParams> updateSafetyStockQty(
      UUID tenantId, UUID storeId, UUID variantId, BigDecimal qty, Instant computedAt) {
    List<SafetyStockParams> rows =
        query(
            "UPDATE safety_stock_params"
                + " SET safety_stock_qty = ?, computed_at = ?"
                + " WHERE tenant_id = ? AND store_id = ? AND variant_id = ?"
                + " RETURNING id, tenant_id, store_id, variant_id, method, lead_time_days,"
                + "   service_level_pct, user_defined_pct, safety_stock_qty,"
                + "   computed_at, created_at",
            ps -> {
              ps.setBigDecimal(1, qty);
              ps.setObject(2, computedAt.atOffset(ZoneOffset.UTC));
              ps.setObject(3, tenantId);
              ps.setObject(4, storeId);
              ps.setObject(5, variantId);
            },
            InventoryRepository::mapSafetyStockParams,
            "update safety stock qty");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Returns the last N daily demand buckets for a specific store + variant, oldest-first. */
  public List<DemandBucket> demandBucketsForCompute(
      UUID tenantId, UUID storeId, UUID variantId, int maxBuckets) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, bucket_date, bucket_type,"
            + " demand_qty, movement_count, computed_at"
            + " FROM demand_history"
            + " WHERE tenant_id = ? AND store_id = ? AND variant_id = ?"
            + " AND bucket_type = 'DAY'"
            + " ORDER BY bucket_date DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setObject(3, variantId);
          ps.setInt(4, maxBuckets);
        },
        InventoryRepository::mapDemandBucket,
        "demand buckets for safety stock");
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
        InventoryRepository::mapSafetyStockParams,
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

  // ---------------------------------------------------------------- lot genealogy (Gap #11)

  public LotGenealogyLink createLotLink(LotGenealogyLink link) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO lot_genealogy"
                  + " (id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes)"
                  + " VALUES (?,?,?,?,?,?,?)"
                  + " ON CONFLICT (tenant_id, parent_batch_id, child_batch_id) DO NOTHING"
                  + " RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, link.id());
            ps.setObject(2, link.tenantId());
            ps.setObject(3, link.parentBatchId());
            ps.setObject(4, link.childBatchId());
            ps.setBigDecimal(5, link.qty());
            ps.setString(6, link.relationType());
            ps.setString(7, link.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409, "LOT_LINK_EXISTS", "Genealogy link already exists", List.of(), null);
              return mapLotLink(rs);
            }
          }
        },
        "create lot link");
  }

  public List<LotGenealogyLink> findAncestors(UUID tenantId, UUID batchId) {
    String sql =
        "WITH RECURSIVE anc(id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + " relation_type, notes, created_at) AS ("
            + "  SELECT id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes, created_at"
            + "  FROM lot_genealogy WHERE tenant_id=? AND child_batch_id=?"
            + "  UNION ALL"
            + "  SELECT g.* FROM lot_genealogy g JOIN anc ON g.tenant_id=anc.tenant_id"
            + "   AND g.child_batch_id=anc.parent_batch_id"
            + ") CYCLE parent_batch_id SET is_cycle USING path"
            + " SELECT id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + "  relation_type, notes, created_at FROM anc WHERE NOT is_cycle";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
        },
        InventoryRepository::mapLotLink,
        "find ancestors");
  }

  public List<LotGenealogyLink> findDescendants(UUID tenantId, UUID batchId) {
    String sql =
        "WITH RECURSIVE des(id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + " relation_type, notes, created_at) AS ("
            + "  SELECT id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes, created_at"
            + "  FROM lot_genealogy WHERE tenant_id=? AND parent_batch_id=?"
            + "  UNION ALL"
            + "  SELECT g.* FROM lot_genealogy g JOIN des ON g.tenant_id=des.tenant_id"
            + "   AND g.parent_batch_id=des.child_batch_id"
            + ") CYCLE child_batch_id SET is_cycle USING path"
            + " SELECT id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + "  relation_type, notes, created_at FROM des WHERE NOT is_cycle";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
        },
        InventoryRepository::mapLotLink,
        "find descendants");
  }

  public List<LotGenealogyLink> findDirectLinks(UUID tenantId, UUID batchId) {
    String sql =
        "SELECT id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes, created_at"
            + " FROM lot_genealogy"
            + " WHERE tenant_id=? AND (parent_batch_id=? OR child_batch_id=?)"
            + " ORDER BY created_at";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
          ps.setObject(3, batchId);
        },
        InventoryRepository::mapLotLink,
        "find direct links");
  }

  private static LotGenealogyLink mapLotLink(ResultSet rs) throws SQLException {
    return new LotGenealogyLink(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("parent_batch_id", UUID.class),
        rs.getObject("child_batch_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getString("relation_type"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ---------------------------------------------------------------- internals

  /** Available = sum(AVAILABLE remaining batches) − sum(HELD reservations), rows locked. */
  private BigDecimal availableForUpdate(Connection c, UUID tenantId, UUID storeId, UUID variantId)
      throws SQLException {
    BigDecimal onHand = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT remaining_qty FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                + " AND material_status='AVAILABLE' FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) onHand = onHand.add(rs.getBigDecimal("remaining_qty"));
      }
    }
    BigDecimal reserved = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(qty),0) AS q FROM reservations"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND status='HELD'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) reserved = rs.getBigDecimal("q");
      }
    }
    return onHand.subtract(reserved);
  }

  /**
   * Batch deduction: walk batches in strategy-defined order WITH FOR UPDATE, decrement remaining,
   * log movement per batch. Strategy defaults to FEFO when null.
   */
  private void deductFifo(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String moveType,
      String refType,
      UUID refId)
      throws SQLException {
    deductBatches(c, tenantId, storeId, variantId, qty, moveType, refType, refId, null, null, null);
  }

  void deductBatches(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      String moveType,
      String refType,
      UUID refId,
      String strategy,
      String gradePreference,
      List<UUID> zonePriorityOrder)
      throws SQLException {
    String orderBy = pickOrderClause(strategy, gradePreference, zonePriorityOrder);
    BigDecimal toDeduct = qty;
    List<Object[]> batches = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, remaining_qty FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND remaining_qty > 0"
                + " AND material_status='AVAILABLE'"
                + " ORDER BY "
                + orderBy
                + " FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next())
          batches.add(
              new Object[] {rs.getObject("id", UUID.class), rs.getBigDecimal("remaining_qty")});
      }
    }
    for (Object[] row : batches) {
      if (toDeduct.signum() <= 0) break;
      UUID batchId = (UUID) row[0];
      BigDecimal remaining = (BigDecimal) row[1];
      BigDecimal take = remaining.min(toDeduct);
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE inventory_batches SET remaining_qty = remaining_qty - ? WHERE id = ?")) {
        ps.setBigDecimal(1, take);
        ps.setObject(2, batchId);
        ps.executeUpdate();
      }
      insertMovement(
          c, tenantId, storeId, variantId, batchId, moveType, take.negate(), refType, refId);
      toDeduct = toDeduct.subtract(take);
    }
    if (toDeduct.signum() > 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_STOCK", "Short by " + toDeduct.toPlainString() + " during deduction");
    }
  }

  private Reservation loadReservationForUpdate(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at FROM reservations WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("RESERVATION_NOT_FOUND", "No such reservation");
        return mapReservation(rs);
      }
    }
  }

  private void setReservationStatus(Connection c, UUID id, String status) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE reservations SET status=? WHERE id=?")) {
      ps.setString(1, status);
      ps.setObject(2, id);
      ps.executeUpdate();
    }
  }

  private void insertBatch(Connection c, Batch b) throws SQLException {
    insertBatch(c, b, null);
  }

  private void insertBatch(Connection c, Batch b, String idempotencyKey) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO inventory_batches"
                + " (id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status, material_status,"
                + " grade, zone_id, idempotency_key)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, b.id());
      ps.setObject(2, b.tenantId());
      ps.setObject(3, b.storeId());
      ps.setObject(4, b.variantId());
      ps.setString(5, b.batchNo());
      ps.setBigDecimal(6, b.receivedQty());
      ps.setBigDecimal(7, b.remainingQty());
      ps.setBigDecimal(8, b.costPrice());
      ps.setObject(9, b.expiryDate());
      ps.setObject(10, b.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(11, b.status() == null ? Batch.STATUS_ACTIVE : b.status());
      ps.setString(12, b.materialStatus() == null ? Batch.MATERIAL_AVAILABLE : b.materialStatus());
      ps.setString(13, b.grade());
      ps.setObject(14, b.zoneId());
      ps.setString(15, idempotencyKey);
      ps.executeUpdate();
    }
  }

  private void insertReservation(Connection c, Reservation r, String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO reservations"
                + " (id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
                + " created_at, idempotency_key)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, r.id());
      ps.setObject(2, r.tenantId());
      ps.setObject(3, r.storeId());
      ps.setObject(4, r.variantId());
      ps.setBigDecimal(5, r.qty());
      ps.setObject(6, r.orderId());
      ps.setString(7, r.status());
      ps.setObject(8, r.expiresAt() == null ? null : r.expiresAt().atOffset(ZoneOffset.UTC));
      ps.setObject(9, r.createdAt().atOffset(ZoneOffset.UTC));
      ps.setString(10, idempotencyKey);
      ps.executeUpdate();
    }
  }

  private void insertMovement(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String type,
      BigDecimal qty,
      String refType,
      UUID refId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO stock_movements"
                + " (id, tenant_id, store_id, variant_id, batch_id, type, qty, ref_type, ref_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenantId);
      ps.setObject(3, storeId);
      ps.setObject(4, variantId);
      ps.setObject(5, batchId);
      ps.setString(6, type);
      ps.setBigDecimal(7, qty);
      ps.setString(8, refType);
      ps.setObject(9, refId);
      ps.executeUpdate();
    }
  }

  private static Batch mapBatch(ResultSet rs) throws SQLException {
    return new Batch(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("batch_no"),
        rs.getBigDecimal("received_qty"),
        rs.getBigDecimal("remaining_qty"),
        rs.getBigDecimal("cost_price"),
        rs.getObject("expiry_date", java.time.LocalDate.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getString("status"),
        rs.getString("material_status"),
        rs.getString("material_status_reason"),
        rs.getString("grade"),
        rs.getObject("zone_id", UUID.class));
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

  private static Reservation mapReservation(ResultSet rs) throws SQLException {
    OffsetDateTime expOdt = rs.getObject("expires_at", OffsetDateTime.class);
    Instant exp = expOdt == null ? null : expOdt.toInstant();
    return new Reservation(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getObject("order_id", UUID.class),
        rs.getString("status"),
        exp,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
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

  private static Suggestion mapSuggestion(ResultSet rs) throws SQLException {
    OffsetDateTime resolvedOdt = rs.getObject("resolved_at", OffsetDateTime.class);
    return new Suggestion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("available_qty"),
        rs.getBigDecimal("min_qty"),
        rs.getBigDecimal("max_qty"),
        rs.getBigDecimal("suggested_qty"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        resolvedOdt == null ? null : resolvedOdt.toInstant());
  }

  // ---------------------------------------------------------------- move orders

  public MoveOrder createMoveOrder(MoveOrder order, List<MoveOrderLine> lines) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO move_orders"
                      + " (id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                      + "  notes, status, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, order.id());
            ps.setObject(2, order.tenantId());
            ps.setObject(3, order.fromStoreId());
            ps.setObject(4, order.toStoreId());
            ps.setString(5, order.fromZone());
            ps.setString(6, order.toZone());
            ps.setString(7, order.notes());
            ps.setString(8, order.status());
            ps.setObject(9, order.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertLines(c, lines);
          return order;
        },
        "create move order");
  }

  public List<MoveOrder> listMoveOrders(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                + " notes, status, created_at, picked_at"
                + " FROM move_orders WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND (from_store_id = ? OR to_store_id = ?)");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) {
            ps.setObject(i++, storeId);
            ps.setObject(i++, storeId);
          }
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapMoveOrder,
        "list move orders");
  }

  public Optional<MoveOrder> findMoveOrder(UUID tenantId, UUID id) {
    List<MoveOrder> rows =
        query(
            "SELECT id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                + " notes, status, created_at, picked_at"
                + " FROM move_orders WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            InventoryRepository::mapMoveOrder,
            "find move order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<MoveOrderLine> listMoveOrderLines(UUID moveOrderId) {
    return query(
        "SELECT id, tenant_id, move_order_id, variant_id, requested_qty, picked_qty"
            + " FROM move_order_lines WHERE move_order_id = ? ORDER BY id",
        ps -> ps.setObject(1, moveOrderId),
        InventoryRepository::mapMoveOrderLine,
        "list move order lines");
  }

  /**
   * Execute pick: FIFO-deduct from source store, create receiving batch in destination store,
   * record TRANSFER movements on both sides, mark order COMPLETED.
   */
  public MoveOrder pickMoveOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          MoveOrder order = loadMoveOrderForUpdate(c, tenantId, orderId);
          if (MoveOrder.COMPLETED.equals(order.status())
              || MoveOrder.CANCELLED.equals(order.status())) {
            throw ApiException.unprocessable(
                "MOVE_ORDER_NOT_PICKABLE", "Move order is " + order.status());
          }
          List<MoveOrderLine> lines = listMoveOrderLines(orderId);
          for (MoveOrderLine line : lines) {
            deductFifo(
                c,
                tenantId,
                order.fromStoreId(),
                line.variantId(),
                line.requestedQty(),
                MoveType.TRANSFER,
                "MOVE_ORDER",
                orderId);
            Batch dest =
                new Batch(
                    UUID.randomUUID(),
                    tenantId,
                    order.toStoreId(),
                    line.variantId(),
                    "MO-" + orderId.toString().substring(0, 8),
                    line.requestedQty(),
                    line.requestedQty(),
                    null,
                    null,
                    Instant.now(),
                    Batch.STATUS_ACTIVE,
                    Batch.MATERIAL_AVAILABLE,
                    null,
                    null,
                    null);
            insertBatch(c, dest);
            insertMovement(
                c,
                tenantId,
                order.toStoreId(),
                line.variantId(),
                dest.id(),
                MoveType.TRANSFER,
                line.requestedQty(),
                "MOVE_ORDER",
                orderId);
          }
          MoveOrder completed;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE move_orders SET status = 'COMPLETED', picked_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                      + " notes, status, created_at, picked_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              completed = mapMoveOrder(rs);
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE move_order_lines SET picked_qty = requested_qty"
                      + " WHERE move_order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return completed;
        },
        "pick move order");
  }

  public Optional<MoveOrder> cancelMoveOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          MoveOrder order = loadMoveOrderForUpdate(c, tenantId, orderId);
          if (MoveOrder.COMPLETED.equals(order.status())
              || MoveOrder.CANCELLED.equals(order.status())) {
            return Optional.<MoveOrder>empty();
          }
          MoveOrder cancelled;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE move_orders SET status = 'CANCELLED'"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                      + " notes, status, created_at, picked_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              cancelled = mapMoveOrder(rs);
            }
          }
          insertOutbox(c, event);
          return Optional.of(cancelled);
        },
        "cancel move order");
  }

  private MoveOrder loadMoveOrderForUpdate(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, from_store_id, to_store_id, from_zone, to_zone,"
                + " notes, status, created_at, picked_at"
                + " FROM move_orders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw ApiException.notFound("MOVE_ORDER_NOT_FOUND", "No such move order");
        return mapMoveOrder(rs);
      }
    }
  }

  private void insertLines(Connection c, List<MoveOrderLine> lines) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO move_order_lines"
                + " (id, tenant_id, move_order_id, variant_id, requested_qty)"
                + " VALUES (?,?,?,?,?)")) {
      for (MoveOrderLine l : lines) {
        ps.setObject(1, l.id());
        ps.setObject(2, l.tenantId());
        ps.setObject(3, l.moveOrderId());
        ps.setObject(4, l.variantId());
        ps.setBigDecimal(5, l.requestedQty());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static MoveOrder mapMoveOrder(ResultSet rs) throws SQLException {
    OffsetDateTime pickedOdt = rs.getObject("picked_at", OffsetDateTime.class);
    return new MoveOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        rs.getString("from_zone"),
        rs.getString("to_zone"),
        rs.getString("notes"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        pickedOdt == null ? null : pickedOdt.toInstant());
  }

  private static MoveOrderLine mapMoveOrderLine(ResultSet rs) throws SQLException {
    return new MoveOrderLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("move_order_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("requested_qty"),
        rs.getBigDecimal("picked_qty"));
  }

  // ---------------------------------------------------------------- transfer orders

  public TransferOrder createTransferOrder(TransferOrder order, List<TransferOrderLine> lines) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO transfer_orders"
                      + " (id, tenant_id, from_store_id, to_store_id, transfer_type,"
                      + "  status, notes, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, order.id());
            ps.setObject(2, order.tenantId());
            ps.setObject(3, order.fromStoreId());
            ps.setObject(4, order.toStoreId());
            ps.setString(5, order.transferType());
            ps.setString(6, order.status());
            ps.setString(7, order.notes());
            ps.setObject(8, order.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertTransferLines(c, lines);
          return order;
        },
        "create transfer order");
  }

  public List<TransferOrder> listTransferOrders(
      UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, from_store_id, to_store_id, transfer_type,"
                + " status, notes, created_at, shipped_at, received_at"
                + " FROM transfer_orders WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND (from_store_id = ? OR to_store_id = ?)");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) {
            ps.setObject(i++, storeId);
            ps.setObject(i++, storeId);
          }
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapTransferOrder,
        "list transfer orders");
  }

  public Optional<TransferOrder> findTransferOrder(UUID tenantId, UUID id) {
    List<TransferOrder> rows =
        query(
            "SELECT id, tenant_id, from_store_id, to_store_id, transfer_type,"
                + " status, notes, created_at, shipped_at, received_at"
                + " FROM transfer_orders WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            InventoryRepository::mapTransferOrder,
            "find transfer order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<TransferOrderLine> listTransferOrderLines(UUID transferOrderId) {
    return query(
        "SELECT id, tenant_id, transfer_order_id, variant_id,"
            + " requested_qty, shipped_qty, received_qty"
            + " FROM transfer_order_lines WHERE transfer_order_id = ? ORDER BY id",
        ps -> ps.setObject(1, transferOrderId),
        InventoryRepository::mapTransferOrderLine,
        "list transfer order lines");
  }

  /**
   * Ship a PENDING transfer: deducts source store stock via FIFO. For DIRECT type: also creates
   * destination batch and marks RECEIVED immediately. For INTRANSIT type: only deducts source;
   * marks SHIPPED (awaiting receive call).
   */
  public TransferOrder shipTransferOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          TransferOrder order = loadTransferOrderForUpdate(c, tenantId, orderId);
          if (!TransferOrder.PENDING.equals(order.status())) {
            throw ApiException.unprocessable(
                "TRANSFER_ORDER_NOT_SHIPPABLE", "Transfer order is " + order.status());
          }
          List<TransferOrderLine> lines = listTransferOrderLines(orderId);
          boolean isDirect = TransferOrder.TYPE_DIRECT.equals(order.transferType());

          for (TransferOrderLine line : lines) {
            deductFifo(
                c,
                tenantId,
                order.fromStoreId(),
                line.variantId(),
                line.requestedQty(),
                MoveType.TRANSFER,
                "TRANSFER_ORDER",
                orderId);
            if (isDirect) {
              Batch dest =
                  new Batch(
                      UUID.randomUUID(),
                      tenantId,
                      order.toStoreId(),
                      line.variantId(),
                      "TO-" + orderId.toString().substring(0, 8),
                      line.requestedQty(),
                      line.requestedQty(),
                      null,
                      null,
                      Instant.now(),
                      Batch.STATUS_ACTIVE,
                      Batch.MATERIAL_AVAILABLE,
                      null,
                      null,
                      null);
              insertBatch(c, dest);
              insertMovement(
                  c,
                  tenantId,
                  order.toStoreId(),
                  line.variantId(),
                  dest.id(),
                  MoveType.TRANSFER,
                  line.requestedQty(),
                  "TRANSFER_ORDER",
                  orderId);
            }
          }

          // Update lines: shipped_qty = requested_qty (and received_qty for DIRECT)
          try (PreparedStatement ps =
              c.prepareStatement(
                  isDirect
                      ? "UPDATE transfer_order_lines"
                          + " SET shipped_qty = requested_qty, received_qty = requested_qty"
                          + " WHERE transfer_order_id = ?"
                      : "UPDATE transfer_order_lines SET shipped_qty = requested_qty"
                          + " WHERE transfer_order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
          }

          TransferOrder updated;
          String sql =
              isDirect
                  ? "UPDATE transfer_orders SET status = 'RECEIVED',"
                      + " shipped_at = now(), received_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, transfer_type,"
                      + " status, notes, created_at, shipped_at, received_at"
                  : "UPDATE transfer_orders SET status = 'SHIPPED', shipped_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, transfer_type,"
                      + " status, notes, created_at, shipped_at, received_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              updated = mapTransferOrder(rs);
            }
          }
          insertOutbox(c, event);
          return updated;
        },
        "ship transfer order");
  }

  /**
   * Receive a SHIPPED INTRANSIT transfer: creates destination batches for each line. Only valid for
   * INTRANSIT type in SHIPPED status.
   */
  public TransferOrder receiveTransferOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          TransferOrder order = loadTransferOrderForUpdate(c, tenantId, orderId);
          if (!TransferOrder.SHIPPED.equals(order.status())) {
            throw ApiException.unprocessable(
                "TRANSFER_ORDER_NOT_RECEIVABLE", "Transfer order is " + order.status());
          }
          if (TransferOrder.TYPE_DIRECT.equals(order.transferType())) {
            throw ApiException.unprocessable(
                "TRANSFER_ORDER_DIRECT_AUTO_RECEIVED",
                "DIRECT transfers are auto-received on ship");
          }
          List<TransferOrderLine> lines = listTransferOrderLines(orderId);
          for (TransferOrderLine line : lines) {
            BigDecimal qty = line.shippedQty() == null ? line.requestedQty() : line.shippedQty();
            Batch dest =
                new Batch(
                    UUID.randomUUID(),
                    tenantId,
                    order.toStoreId(),
                    line.variantId(),
                    "TO-" + orderId.toString().substring(0, 8),
                    qty,
                    qty,
                    null,
                    null,
                    Instant.now(),
                    Batch.STATUS_ACTIVE,
                    Batch.MATERIAL_AVAILABLE,
                    null,
                    null,
                    null);
            insertBatch(c, dest);
            insertMovement(
                c,
                tenantId,
                order.toStoreId(),
                line.variantId(),
                dest.id(),
                MoveType.TRANSFER,
                qty,
                "TRANSFER_ORDER",
                orderId);
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_order_lines SET received_qty = shipped_qty"
                      + " WHERE transfer_order_id = ?")) {
            ps.setObject(1, orderId);
            ps.executeUpdate();
          }
          TransferOrder received;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_orders SET status = 'RECEIVED', received_at = now()"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, transfer_type,"
                      + " status, notes, created_at, shipped_at, received_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              received = mapTransferOrder(rs);
            }
          }
          insertOutbox(c, event);
          return received;
        },
        "receive transfer order");
  }

  public Optional<TransferOrder> cancelTransferOrder(UUID tenantId, UUID orderId, OutboxRow event) {
    return inTx(
        c -> {
          TransferOrder order = loadTransferOrderForUpdate(c, tenantId, orderId);
          if (!TransferOrder.PENDING.equals(order.status())) {
            return Optional.<TransferOrder>empty();
          }
          TransferOrder cancelled;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transfer_orders SET status = 'CANCELLED'"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, from_store_id, to_store_id, transfer_type,"
                      + " status, notes, created_at, shipped_at, received_at")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              cancelled = mapTransferOrder(rs);
            }
          }
          insertOutbox(c, event);
          return Optional.of(cancelled);
        },
        "cancel transfer order");
  }

  private TransferOrder loadTransferOrderForUpdate(Connection c, UUID tenantId, UUID id)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, from_store_id, to_store_id, transfer_type,"
                + " status, notes, created_at, shipped_at, received_at"
                + " FROM transfer_orders WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next())
          throw ApiException.notFound("TRANSFER_ORDER_NOT_FOUND", "No such transfer order");
        return mapTransferOrder(rs);
      }
    }
  }

  private void insertTransferLines(Connection c, List<TransferOrderLine> lines)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO transfer_order_lines"
                + " (id, tenant_id, transfer_order_id, variant_id, requested_qty)"
                + " VALUES (?,?,?,?,?)")) {
      for (TransferOrderLine l : lines) {
        ps.setObject(1, l.id());
        ps.setObject(2, l.tenantId());
        ps.setObject(3, l.transferOrderId());
        ps.setObject(4, l.variantId());
        ps.setBigDecimal(5, l.requestedQty());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static TransferOrder mapTransferOrder(ResultSet rs) throws SQLException {
    OffsetDateTime shippedOdt = rs.getObject("shipped_at", OffsetDateTime.class);
    OffsetDateTime receivedOdt = rs.getObject("received_at", OffsetDateTime.class);
    return new TransferOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        rs.getString("transfer_type"),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        shippedOdt == null ? null : shippedOdt.toInstant(),
        receivedOdt == null ? null : receivedOdt.toInstant());
  }

  private static TransferOrderLine mapTransferOrderLine(ResultSet rs) throws SQLException {
    return new TransferOrderLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("transfer_order_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("requested_qty"),
        rs.getBigDecimal("shipped_qty"),
        rs.getBigDecimal("received_qty"));
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

  // ── Gap #16: Physical Inventory ──────────────────────────────────────────

  public PhysicalInventory createPhysicalInventory(PhysicalInventory pi, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO physical_inventories (id, tenant_id, store_id, status, notes)"
                  + " VALUES (?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, pi.id());
            ps.setObject(2, pi.tenantId());
            ps.setObject(3, pi.storeId());
            ps.setString(4, pi.status());
            ps.setString(5, pi.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw dbError("create physical inventory", new java.sql.SQLException());
              PhysicalInventory saved = mapPhysicalInventory(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create physical inventory");
  }

  public Optional<PhysicalInventory> findPhysicalInventory(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id, tenant_id, store_id, status, notes, started_at, completed_at"
                + " FROM physical_inventories WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            InventoryRepository::mapPhysicalInventory,
            "find physical inventory");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<PhysicalInventory> listPhysicalInventories(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, status, notes, started_at, completed_at"
            + " FROM physical_inventories WHERE tenant_id=?"
            + (storeId != null ? " AND store_id=?" : "")
            + " ORDER BY started_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        InventoryRepository::mapPhysicalInventory,
        "list physical inventories");
  }

  public List<PhysicalInventoryTag> listTags(UUID tenantId, UUID physicalInventoryId) {
    return query(
        "SELECT id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty,"
            + " counted_qty, adjustment_qty, status, counted_at"
            + " FROM physical_inventory_tags WHERE tenant_id=? AND physical_inventory_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, physicalInventoryId);
        },
        InventoryRepository::mapTag,
        "list physical inventory tags");
  }

  public PhysicalInventoryTag addTag(PhysicalInventoryTag tag) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO physical_inventory_tags"
                  + " (id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty)"
                  + " VALUES (?,?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tag.id());
            ps.setObject(2, tag.tenantId());
            ps.setObject(3, tag.physicalInventoryId());
            ps.setObject(4, tag.variantId());
            ps.setObject(5, tag.zoneId());
            ps.setBigDecimal(6, tag.systemQty());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw dbError("add pi tag", new java.sql.SQLException());
              return mapTag(rs);
            }
          }
        },
        "add physical inventory tag");
  }

  public PhysicalInventoryTag countTag(
      UUID tenantId, UUID physicalInventoryId, UUID tagId, BigDecimal countedQty) {
    return inTx(
        c -> {
          String sql =
              "UPDATE physical_inventory_tags SET counted_qty=?, status='COUNTED', counted_at=now()"
                  + " WHERE tenant_id=? AND physical_inventory_id=? AND id=? RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, countedQty);
            ps.setObject(2, tenantId);
            ps.setObject(3, physicalInventoryId);
            ps.setObject(4, tagId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("TAG_NOT_FOUND", "Physical inventory tag not found");
              return mapTag(rs);
            }
          }
        },
        "count pi tag");
  }

  public PhysicalInventory completePhysicalInventory(UUID tenantId, UUID piId, OutboxRow event) {
    return inTx(
        c -> {
          // Create stock_movement for each COUNTED tag where adjustment != 0
          String tagSql =
              "SELECT id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty,"
                  + " counted_qty, adjustment_qty, status, counted_at"
                  + " FROM physical_inventory_tags"
                  + " WHERE tenant_id=? AND physical_inventory_id=?"
                  + " AND status='COUNTED' AND counted_qty IS NOT NULL"
                  + " AND counted_qty <> system_qty";
          try (PreparedStatement ps = c.prepareStatement(tagSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                PhysicalInventoryTag tag = mapTag(rs);
                BigDecimal adj = tag.adjustmentQty();
                String moveType = adj.compareTo(BigDecimal.ZERO) > 0 ? "RECEIVE" : "ISSUE";
                UUID movId = UUID.randomUUID();
                try (PreparedStatement mps =
                    c.prepareStatement(
                        "INSERT INTO stock_movements"
                            + " (id, tenant_id, store_id, variant_id, qty, type, ref_type, ref_id)"
                            + " SELECT ?,?,store_id,?,?,?,?,?"
                            + " FROM physical_inventories WHERE id=?")) {
                  mps.setObject(1, movId);
                  mps.setObject(2, tenantId);
                  mps.setObject(3, tag.variantId());
                  mps.setBigDecimal(4, adj.abs());
                  mps.setString(5, moveType);
                  mps.setString(6, "PHYSICAL_INVENTORY");
                  mps.setObject(7, piId);
                  mps.setObject(8, piId);
                  mps.executeUpdate();
                }
              }
            }
          }
          // Mark all COUNTED tags as ADJUSTED
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE physical_inventory_tags SET status='ADJUSTED'"
                      + " WHERE tenant_id=? AND physical_inventory_id=? AND status='COUNTED'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            ps.executeUpdate();
          }
          // Mark header COMPLETED
          String doneSql =
              "UPDATE physical_inventories SET status='COMPLETED', completed_at=now()"
                  + " WHERE tenant_id=? AND id=? AND status<>'COMPLETED' RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(doneSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409,
                    "PI_ALREADY_COMPLETED",
                    "Physical inventory already completed",
                    List.of(),
                    null);
              PhysicalInventory done = mapPhysicalInventory(rs);
              insertOutbox(c, event);
              return done;
            }
          }
        },
        "complete physical inventory");
  }

  private static PhysicalInventory mapPhysicalInventory(ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
    return new PhysicalInventory(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
        completed == null ? null : completed.toInstant());
  }

  private static PhysicalInventoryTag mapTag(ResultSet rs) throws java.sql.SQLException {
    OffsetDateTime countedAt = rs.getObject("counted_at", OffsetDateTime.class);
    return new PhysicalInventoryTag(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("physical_inventory_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getBigDecimal("system_qty"),
        rs.getBigDecimal("counted_qty"),
        rs.getBigDecimal("adjustment_qty"),
        rs.getString("status"),
        countedAt == null ? null : countedAt.toInstant());
  }

  // ── Gap #19: Reorder Point + EOQ ────────────────────────────────────────────

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
            InventoryRepository::mapRopPlan,
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
        InventoryRepository::mapRopPlan,
        "list rop plans");
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

  // ── Gap #18: Kanban Replenishment ───────────────────────────────────────────

  public KanbanCard createKanbanCard(KanbanCard card, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO kanban_cards (id, tenant_id, store_id, variant_id, kanban_type,"
                  + " status, reorder_qty, source_store_id, supplier_ref, notes)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                  + " RETURNING id, tenant_id, store_id, variant_id, kanban_type, status,"
                  + "   reorder_qty, source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                  + "   lot_multiplier, created_at, triggered_at, replenished_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, card.id());
            ps.setObject(2, card.tenantId());
            ps.setObject(3, card.storeId());
            ps.setObject(4, card.variantId());
            ps.setString(5, card.kanbanType());
            ps.setString(6, card.status());
            ps.setBigDecimal(7, card.reorderQty());
            ps.setObject(8, card.sourceStoreId());
            ps.setString(9, card.supplierRef());
            ps.setString(10, card.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.unprocessable(
                    "KANBAN_CREATE_ERROR", "kanban card creation failed");
              KanbanCard saved = mapKanbanCard(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create kanban card");
  }

  public KanbanCard triggerKanbanCard(UUID tenantId, UUID cardId, String notes, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE kanban_cards SET status='TRIGGERED', triggered_at=now(),"
                  + " notes=COALESCE(?,notes)"
                  + " WHERE tenant_id=? AND id=? AND status='EMPTY'"
                  + " RETURNING id, tenant_id, store_id, variant_id, kanban_type, status,"
                  + "   reorder_qty, source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                  + "   lot_multiplier, created_at, triggered_at, replenished_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, notes);
            ps.setObject(2, tenantId);
            ps.setObject(3, cardId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.conflict(
                    "KANBAN_NOT_EMPTY", "card not found or not in EMPTY status");
              KanbanCard updated = mapKanbanCard(rs);
              insertOutbox(c, event);
              return updated;
            }
          }
        },
        "trigger kanban card");
  }

  public KanbanCard replenishKanbanCard(UUID tenantId, UUID cardId, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE kanban_cards SET status='REPLENISHED', replenished_at=now()"
                  + " WHERE tenant_id=? AND id=? AND status IN ('TRIGGERED','IN_PROGRESS')"
                  + " RETURNING id, tenant_id, store_id, variant_id, kanban_type, status,"
                  + "   reorder_qty, source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                  + "   lot_multiplier, created_at, triggered_at, replenished_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, cardId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.conflict(
                    "KANBAN_NOT_TRIGGERED", "card not found or not triggered");
              KanbanCard updated = mapKanbanCard(rs);
              insertOutbox(c, event);
              return updated;
            }
          }
        },
        "replenish kanban card");
  }

  public Optional<KanbanCard> findKanbanCard(UUID tenantId, UUID cardId) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, kanban_type, status, reorder_qty,"
                + " source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                + " lot_multiplier, created_at, triggered_at, replenished_at"
                + " FROM kanban_cards WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, cardId);
            },
            InventoryRepository::mapKanbanCard,
            "find kanban card")
        .stream()
        .findFirst();
  }

  public List<KanbanCard> listKanbanCards(UUID tenantId, UUID storeId, String status) {
    if (status != null && !status.isBlank()) {
      return query(
          "SELECT id, tenant_id, store_id, variant_id, kanban_type, status, reorder_qty,"
              + " source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
              + " lot_multiplier, created_at, triggered_at, replenished_at"
              + " FROM kanban_cards WHERE tenant_id=? AND store_id=? AND status=?"
              + " ORDER BY created_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setString(3, status);
          },
          InventoryRepository::mapKanbanCard,
          "list kanban cards by status");
    }
    return query(
        "SELECT id, tenant_id, store_id, variant_id, kanban_type, status, reorder_qty,"
            + " source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
            + " lot_multiplier, created_at, triggered_at, replenished_at"
            + " FROM kanban_cards WHERE tenant_id=? AND store_id=? ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        InventoryRepository::mapKanbanCard,
        "list kanban cards");
  }

  private static KanbanCard mapKanbanCard(ResultSet rs) throws SQLException {
    OffsetDateTime triggeredAt = rs.getObject("triggered_at", OffsetDateTime.class);
    OffsetDateTime replenishedAt = rs.getObject("replenished_at", OffsetDateTime.class);
    Object srcStoreRaw = rs.getObject("source_store_id");
    UUID sourceStoreId = srcStoreRaw == null ? null : rs.getObject("source_store_id", UUID.class);
    return new KanbanCard(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("kanban_type"),
        rs.getString("status"),
        rs.getBigDecimal("reorder_qty"),
        sourceStoreId,
        rs.getString("supplier_ref"),
        rs.getString("notes"),
        rs.getBigDecimal("min_order_qty"),
        rs.getBigDecimal("max_order_qty"),
        rs.getBigDecimal("lot_multiplier"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        triggeredAt == null ? null : triggeredAt.toInstant(),
        replenishedAt == null ? null : replenishedAt.toInstant());
  }

  // ── Gap #17: Costing Methods ────────────────────────────────────────────────

  public CostingMethod upsertCostingMethod(
      UUID tenantId, UUID storeId, UUID variantId, String method, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO costing_methods (id, tenant_id, store_id, variant_id, method)"
                  + " VALUES (gen_random_uuid(),?,?,?,?)"
                  + " ON CONFLICT (tenant_id, store_id, variant_id)"
                  + " DO UPDATE SET method=EXCLUDED.method, updated_at=now()"
                  + " RETURNING id, tenant_id, store_id, variant_id, method, average_cost, updated_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, variantId);
            ps.setString(4, method);
            ResultSet rs = ps.executeQuery();
            if (!rs.next())
              throw ApiException.unprocessable(
                  "COSTING_METHOD_ERROR", "upsert costing method returned nothing");
            CostingMethod cm = mapCostingMethod(rs);
            insertOutbox(c, event);
            return cm;
          }
        },
        "upsert costing method");
  }

  public Optional<CostingMethod> findCostingMethod(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, method, average_cost, updated_at"
                + " FROM costing_methods WHERE tenant_id=? AND store_id=? AND variant_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            InventoryRepository::mapCostingMethod,
            "find costing method")
        .stream()
        .findFirst();
  }

  public List<CostingMethod> listCostingMethods(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, method, average_cost, updated_at"
            + " FROM costing_methods WHERE tenant_id=? AND store_id=? ORDER BY updated_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        InventoryRepository::mapCostingMethod,
        "list costing methods");
  }

  public AccountingPeriod openPeriod(
      UUID tenantId, UUID storeId, String periodName, LocalDate periodDate, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO accounting_periods (id, tenant_id, store_id, period_name, period_date)"
                  + " VALUES (gen_random_uuid(),?,?,?,?)"
                  + " RETURNING id, tenant_id, store_id, period_name, period_date,"
                  + "   status, opened_at, closed_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setString(3, periodName);
            ps.setObject(4, java.sql.Date.valueOf(periodDate));
            try {
              ResultSet rs = ps.executeQuery();
              if (!rs.next())
                throw ApiException.unprocessable(
                    "PERIOD_OPEN_ERROR", "open period returned nothing");
              AccountingPeriod ap = mapPeriod(rs);
              insertOutbox(c, event);
              return ap;
            } catch (java.sql.SQLException sqle) {
              if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
                throw new ApiException(
                    409,
                    "PERIOD_DUPLICATE_DATE",
                    "a period already exists for this date",
                    java.util.List.of(),
                    sqle);
              throw sqle;
            }
          }
        },
        "open accounting period");
  }

  public AccountingPeriod closePeriod(UUID tenantId, UUID periodId, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE accounting_periods SET status='CLOSED', closed_at=now()"
                  + " WHERE tenant_id=? AND id=? AND status='OPEN'"
                  + " RETURNING id, tenant_id, store_id, period_name, period_date,"
                  + "   status, opened_at, closed_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, periodId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next())
              throw ApiException.conflict("PERIOD_NOT_OPEN", "period not found or already closed");
            AccountingPeriod ap = mapPeriod(rs);
            insertOutbox(c, event);
            return ap;
          }
        },
        "close accounting period");
  }

  public Optional<AccountingPeriod> findPeriod(UUID tenantId, UUID periodId) {
    return query(
            "SELECT id, tenant_id, store_id, period_name, period_date, status, opened_at, closed_at"
                + " FROM accounting_periods WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, periodId);
            },
            InventoryRepository::mapPeriod,
            "find period")
        .stream()
        .findFirst();
  }

  public List<AccountingPeriod> listPeriods(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, period_name, period_date, status, opened_at, closed_at"
            + " FROM accounting_periods WHERE tenant_id=? AND store_id=? ORDER BY period_date DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        InventoryRepository::mapPeriod,
        "list periods");
  }

  private static CostingMethod mapCostingMethod(ResultSet rs) throws SQLException {
    return new CostingMethod(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("method"),
        rs.getBigDecimal("average_cost"),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static AccountingPeriod mapPeriod(ResultSet rs) throws SQLException {
    OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new AccountingPeriod(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("period_name"),
        rs.getObject("period_date", java.sql.Date.class).toLocalDate(),
        rs.getString("status"),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant());
  }

  // ── Tier-1 Gap #23: Lot actions (split / merge) ───────────────────────────

  public LotAction insertLotAction(
      UUID tenantId,
      String actionType,
      UUID sourceBatchId,
      UUID resultBatchId,
      java.math.BigDecimal qty,
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
        InventoryRepository::mapLotAction,
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

  // ── Tier-1 Gap #24: Expiry alert query ────────────────────────────────────

  public List<Batch> listExpiringBatches(UUID tenantId, UUID storeId, int withinDays) {
    return query(
        "SELECT id,tenant_id,store_id,variant_id,batch_no,received_qty,remaining_qty,"
            + "cost_price,expiry_date,created_at,status,material_status,material_status_reason,grade,zone_id"
            + " FROM inventory_batches"
            + " WHERE tenant_id=? AND store_id=? AND status='ACTIVE'"
            + " AND expiry_date IS NOT NULL"
            + " AND expiry_date <= CURRENT_DATE + make_interval(days => ?)"
            + " ORDER BY expiry_date ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setInt(3, withinDays);
        },
        InventoryRepository::mapBatch,
        "list expiring batches");
  }

  // ── Tier-1 Gap #25: Grade update on batch ────────────────────────────────

  public Batch updateBatchGrade(UUID tenantId, UUID batchId, String grade) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE inventory_batches SET grade=? WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,store_id,variant_id,batch_no,received_qty,"
                      + "remaining_qty,cost_price,expiry_date,created_at,status,"
                      + "material_status,material_status_reason,grade,zone_id")) {
            ps.setString(1, grade);
            ps.setObject(2, tenantId);
            ps.setObject(3, batchId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw ApiException.notFound("BATCH_NOT_FOUND", "No such batch");
              return mapBatch(rs);
            }
          }
        },
        "update batch grade");
  }

  // ── Tier-1 Gap #28: Order modifier updates ───────────────────────────────

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

  public KanbanCard updateKanbanOrderModifiers(
      UUID tenantId,
      UUID id,
      java.math.BigDecimal minOrderQty,
      java.math.BigDecimal maxOrderQty,
      java.math.BigDecimal lotMultiplier) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE kanban_cards SET min_order_qty=?,max_order_qty=?,lot_multiplier=?"
                      + " WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,store_id,variant_id,kanban_type,status,reorder_qty,"
                      + "source_store_id,supplier_ref,notes,min_order_qty,max_order_qty,lot_multiplier,"
                      + "created_at,triggered_at,replenished_at")) {
            ps.setBigDecimal(1, minOrderQty);
            ps.setBigDecimal(2, maxOrderQty);
            ps.setBigDecimal(3, lotMultiplier);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("KANBAN_NOT_FOUND", "No such kanban card");
              return mapKanbanCard(rs);
            }
          }
        },
        "update kanban order modifiers");
  }

  // ── Tier-1 Gap #30: Purge transaction history ────────────────────────────
  // Golden rule #8: stock_movements stays append-only. "Purge" relocates matching
  // rows into stock_movements_archive (insert + delete in one transaction) instead
  // of destroying them — the hot table shrinks, history is never lost.

  public int purgeMovementsBefore(UUID tenantId, java.time.Instant before) {
    OffsetDateTime cutoff = OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC);
    return inTx(
        c -> {
          try (var insert =
              c.prepareStatement(
                  "INSERT INTO stock_movements_archive"
                      + " (id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, created_at)"
                      + " SELECT id, tenant_id, store_id, variant_id, batch_id, type, qty,"
                      + " ref_type, ref_id, reason_code, created_at FROM stock_movements"
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

  // ─────────────────────────────────────────────────── picking rules (Gap #38)

  public PickingRule createPickingRule(
      UUID tenantId, String name, String strategy, String gradePreference) {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO picking_rules (id,tenant_id,name,strategy,grade_preference,status,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setString(3, name);
          ps.setString(4, strategy);
          ps.setString(5, gradePreference);
          ps.setString(6, PickingRule.ACTIVE);
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
          ps.setObject(8, now.atOffset(ZoneOffset.UTC));
        },
        "create picking rule");
    return findPickingRule(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PICKING_RULE_NOT_FOUND", "Picking rule not found"));
  }

  public Optional<PickingRule> findPickingRule(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,name,strategy,grade_preference,status,created_at,updated_at"
                + " FROM picking_rules WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            InventoryRepository::mapPickingRule,
            "find picking rule")
        .stream()
        .findFirst();
  }

  public List<PickingRule> listPickingRules(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,name,strategy,grade_preference,status,created_at,updated_at"
            + " FROM picking_rules WHERE tenant_id=? AND status='ACTIVE' ORDER BY name",
        ps -> ps.setObject(1, tenantId),
        InventoryRepository::mapPickingRule,
        "list picking rules");
  }

  public PickingRule deactivatePickingRule(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE picking_rules SET status='INACTIVE', updated_at=? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate picking rule");
    return findPickingRule(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PICKING_RULE_NOT_FOUND", "Picking rule not found"));
  }

  public void replaceZonePriorities(
      UUID tenantId, UUID ruleId, List<PickingRuleZonePriority> items) {
    exec(
        "DELETE FROM picking_rule_zone_priorities WHERE tenant_id=? AND rule_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, ruleId);
        },
        "delete zone priorities");
    for (PickingRuleZonePriority p : items) {
      exec(
          "INSERT INTO picking_rule_zone_priorities (id,tenant_id,rule_id,zone_id,priority)"
              + " VALUES (?,?,?,?,?)",
          ps -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setObject(3, ruleId);
            ps.setObject(4, p.zoneId());
            ps.setInt(5, p.priority());
          },
          "insert zone priority");
    }
  }

  public List<PickingRuleZonePriority> listZonePriorities(UUID tenantId, UUID ruleId) {
    return query(
        "SELECT id,tenant_id,rule_id,zone_id,priority FROM picking_rule_zone_priorities"
            + " WHERE tenant_id=? AND rule_id=? ORDER BY priority ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, ruleId);
        },
        InventoryRepository::mapZonePriority,
        "list zone priorities");
  }

  public PickingRuleAssignment createPickingRuleAssignment(
      UUID tenantId, UUID ruleId, String scopeType, UUID scopeId) {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO picking_rule_assignments (id,tenant_id,rule_id,scope_type,scope_id,created_at)"
            + " VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id,scope_type,scope_id) DO UPDATE"
            + " SET rule_id=EXCLUDED.rule_id",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, ruleId);
          ps.setString(4, scopeType);
          if (scopeId != null) ps.setObject(5, scopeId);
          else ps.setNull(5, java.sql.Types.OTHER);
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
        },
        "create picking rule assignment");
    return findPickingRuleAssignment(tenantId, scopeType, scopeId)
        .orElseThrow(
            () ->
                ApiException.notFound("ASSIGNMENT_NOT_FOUND", "Picking rule assignment not found"));
  }

  public Optional<PickingRuleAssignment> findPickingRuleAssignment(
      UUID tenantId, String scopeType, UUID scopeId) {
    return query(
            "SELECT id,tenant_id,rule_id,scope_type,scope_id,created_at"
                + " FROM picking_rule_assignments WHERE tenant_id=? AND scope_type=?"
                + " AND (scope_id=? OR (scope_id IS NULL AND ?::uuid IS NULL))",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, scopeType);
              if (scopeId != null) ps.setObject(3, scopeId);
              else ps.setNull(3, java.sql.Types.OTHER);
              if (scopeId != null) ps.setObject(4, scopeId);
              else ps.setNull(4, java.sql.Types.OTHER);
            },
            InventoryRepository::mapPickingRuleAssignment,
            "find picking rule assignment")
        .stream()
        .findFirst();
  }

  public List<PickingRuleAssignment> listPickingRuleAssignments(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,rule_id,scope_type,scope_id,created_at"
            + " FROM picking_rule_assignments WHERE tenant_id=? ORDER BY scope_type, scope_id",
        ps -> ps.setObject(1, tenantId),
        InventoryRepository::mapPickingRuleAssignment,
        "list picking rule assignments");
  }

  public boolean deletePickingRuleAssignment(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM picking_rule_assignments WHERE tenant_id=? AND id=? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete picking rule assignment");
    return found[0] != null;
  }

  /** Resolve the most-specific applicable picking rule for a (tenant, store, variant) triple. */
  public Optional<PickingRule> resolvePickingRule(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT pr.id,pr.tenant_id,pr.name,pr.strategy,pr.grade_preference,pr.status,"
                + "pr.created_at,pr.updated_at"
                + " FROM picking_rule_assignments pra"
                + " JOIN picking_rules pr ON pr.id=pra.rule_id AND pr.status='ACTIVE'"
                + " WHERE pra.tenant_id=?"
                + " AND ((pra.scope_type='PRODUCT' AND pra.scope_id=?)"
                + "   OR (pra.scope_type='STORE' AND pra.scope_id=?)"
                + "   OR (pra.scope_type='GLOBAL'))"
                + " ORDER BY CASE pra.scope_type WHEN 'PRODUCT' THEN 1"
                + "           WHEN 'STORE' THEN 2 ELSE 3 END LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setObject(3, storeId);
            },
            InventoryRepository::mapPickingRule,
            "resolve picking rule")
        .stream()
        .findFirst();
  }

  /**
   * Preview pick order for a (tenant, store, variant) — returns available batches in rule order.
   */
  public List<Batch> previewPickOrder(
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      String strategy,
      String gradePreference,
      List<UUID> zonePriorityOrder) {
    String orderBy = pickOrderClause(strategy, gradePreference, zonePriorityOrder);
    return query(
        "SELECT id,tenant_id,store_id,variant_id,batch_no,received_qty,remaining_qty,"
            + "cost_price,expiry_date,created_at,status,material_status,material_status_reason,grade,zone_id"
            + " FROM inventory_batches"
            + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND remaining_qty>0"
            + " AND material_status='AVAILABLE'"
            + " ORDER BY "
            + orderBy,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setObject(3, variantId);
        },
        InventoryRepository::mapBatch,
        "preview pick order");
  }

  /** Returns the ORDER BY clause for the given picking strategy. */
  private static String pickOrderClause(
      String strategy, String gradePreference, List<UUID> zonePriorityOrder) {
    String effectiveStrategy = strategy == null ? PickingRule.FEFO : strategy;
    return switch (effectiveStrategy) {
      case PickingRule.FIFO -> "created_at ASC";
      case PickingRule.LIFO -> "created_at DESC";
      case PickingRule.FEFO_GRADE ->
          gradePreference != null
              ? "CASE WHEN grade='"
                  + gradePreference.replace("'", "''")
                  + "' THEN 0 ELSE 1 END ASC,"
                  + " expiry_date ASC NULLS LAST, created_at ASC"
              : "expiry_date ASC NULLS LAST, created_at ASC";
      case PickingRule.ZONE_PRIORITY ->
          zonePriorityOrder != null && !zonePriorityOrder.isEmpty()
              ? buildZoneCaseClause(zonePriorityOrder)
                  + ", expiry_date ASC NULLS LAST, created_at ASC"
              : "expiry_date ASC NULLS LAST, created_at ASC";
      default -> "expiry_date ASC NULLS LAST, created_at ASC"; // FEFO
    };
  }

  private static String buildZoneCaseClause(List<UUID> zoneOrder) {
    StringBuilder sb = new StringBuilder("CASE zone_id");
    for (int i = 0; i < zoneOrder.size(); i++) {
      sb.append(" WHEN '").append(zoneOrder.get(i)).append("' THEN ").append(i);
    }
    sb.append(" ELSE ").append(zoneOrder.size()).append(" END ASC");
    return sb.toString();
  }

  private static PickingRule mapPickingRule(ResultSet rs) throws SQLException {
    return new PickingRule(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("strategy"),
        rs.getString("grade_preference"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static PickingRuleZonePriority mapZonePriority(ResultSet rs) throws SQLException {
    return new PickingRuleZonePriority(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("rule_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getInt("priority"));
  }

  private static PickingRuleAssignment mapPickingRuleAssignment(ResultSet rs) throws SQLException {
    return new PickingRuleAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("rule_id", UUID.class),
        rs.getString("scope_type"),
        rs.getObject("scope_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /**
   * Within an open transaction: if a reorder threshold exists for (tenant, store, variant) and the
   * current available qty is below it, inserts a StockBelowThreshold outbox event. Called after any
   * stock-reducing operation so the alert and the deduction are atomic (golden rule #6).
   */
  private void checkThresholdTx(Connection c, UUID tenantId, UUID storeId, UUID variantId)
      throws SQLException {
    BigDecimal threshold = null;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT threshold FROM reorder_thresholds"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) threshold = rs.getBigDecimal("threshold");
      }
    }
    if (threshold == null) return;

    BigDecimal onHand = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(remaining_qty),0) AS q FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                + " AND material_status='AVAILABLE'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) onHand = rs.getBigDecimal("q");
      }
    }
    BigDecimal reserved = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(qty),0) AS q FROM reservations"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND status='HELD'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) reserved = rs.getBigDecimal("q");
      }
    }
    BigDecimal available = onHand.subtract(reserved);
    if (available.compareTo(threshold) < 0) {
      insertOutbox(
          c,
          new OutboxRow(
              "StockBelowThreshold",
              "shelfj.inventory.stock-below-threshold",
              tenantId,
              variantId,
              com.shelfj.inventory.service.Events.stockBelowThreshold(
                  tenantId, storeId, variantId, available, threshold)));
    }
  }
}
