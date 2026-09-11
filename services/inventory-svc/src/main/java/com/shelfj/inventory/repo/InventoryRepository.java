package com.shelfj.inventory.repo;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.LevelSummary;
import com.shelfj.inventory.domain.Domain.MoveOrder;
import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.MoveType;
import com.shelfj.inventory.domain.Domain.MovementAttribution;
import com.shelfj.inventory.domain.Domain.PickingRule;
import com.shelfj.inventory.domain.Domain.PickingRuleZonePriority;
import com.shelfj.inventory.domain.Domain.Reservation;
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
import java.sql.Savepoint;
import java.time.Instant;
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
          String recallHold;
          try {
            recallHold = insertBatch(c, batch, idempotencyKey);
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
              refId,
              MovementAttribution.system());
          insertOutbox(c, event);
          return recallHold == null ? batch : recalledCopy(batch, recallHold);
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
              refId,
              MovementAttribution.system());
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
      OutboxRow event,
      MovementAttribution attribution) {
    adjust(tenantId, storeId, variantId, delta, reason, event, null, attribution);
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
      String idempotencyKey,
      MovementAttribution attribution) {
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
          adjustTx(c, tenantId, storeId, variantId, delta, reason, event, attribution);
          return null;
        },
        "adjust stock");
  }

  // `reason` is accepted from the API down to here but not yet persisted: stock_movements has no
  // free-text column for it, and ref_type below is a fixed small tag set ("ORDER", "LOT_MERGE_IN",
  // ...), not a place to put arbitrary caller-supplied text. Kept as a parameter (rather than
  // dropped from the call chain) so a future migration adding a notes column has it ready to wire
  // up instead of re-threading it back through every caller.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private void adjustTx(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      BigDecimal delta,
      String reason,
      OutboxRow event,
      MovementAttribution attribution)
      throws SQLException {
    if (delta.signum() >= 0) {
      Batch b =
          new Batch(
              Ids.newId(),
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
      // A positive adjustment creates a batch but no deduction, so this is the only movement it
      // writes. Negative adjustments are recorded below instead, per batch.
      insertMovement(
          c,
          tenantId,
          storeId,
          variantId,
          null,
          MoveType.ADJUST,
          delta,
          "ADJUSTMENT",
          null,
          attribution);
    } else {
      // deductFifo writes one ADJUST movement per batch it draws down, each carrying its batch_id.
      // A summary movement on top of those used to be written unconditionally, which double-counted
      // every negative adjustment in the ledger: a write-off of 30 units appeared as 60 across two
      // rows. Stock levels were unaffected (only one deduction ever happened), but every
      // movement-based read was wrong -- the movements list, movement-stats, and now the shrinkage
      // report. The per-batch rows are also strictly more informative, since they tie the write-off
      // to the batches it actually came out of.
      deductFifo(
          c,
          tenantId,
          storeId,
          variantId,
          delta.negate(),
          MoveType.ADJUST,
          "ADJUSTMENT",
          null,
          attribution);
      checkThresholdTx(c, tenantId, storeId, variantId);
    }
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
      BigDecimal qty,
      MovementAttribution attribution) {
    inTx(
        c -> {
          adjustTx(
              c,
              tenantId,
              sourceStoreId,
              sourceVariantId,
              qty.negate(),
              "LOT_MERGE_OUT",
              outEvent,
              attribution);
          adjustTx(
              c,
              tenantId,
              targetStoreId,
              targetVariantId,
              qty,
              "LOT_MERGE_IN",
              inEvent,
              attribution);
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
    return inTx(c -> reserveTx(c, r, event, idempotencyKey), "reserve stock");
  }

  /** One item of a {@link #reserveBatch} call. */
  public record ReserveBatchItem(Reservation reservation, OutboxRow event, String idempotencyKey) {}

  /** Per-item result of {@link #reserveBatch}: exactly one of the two fields is set. */
  public record ReserveOutcome(Reservation reservation, RuntimeException error) {
    static ReserveOutcome success(Reservation r) {
      return new ReserveOutcome(r, null);
    }

    static ReserveOutcome failure(RuntimeException e) {
      return new ReserveOutcome(null, e);
    }

    public boolean succeeded() {
      return error == null;
    }
  }

  /**
   * Batch form of {@link #reserve}: holds stock for every item within ONE transaction instead of
   * one {@code BEGIN}/{@code COMMIT} (and connection-pool checkout) per item — {@code bulkReserve}
   * used to loop calling {@link #reserve} once per line, fanning a single "bulk" request out into N
   * separate round trips. A {@code SAVEPOINT} per item preserves the original partial-success
   * behavior: a short/failing line rolls back only its own work, leaving earlier and later items in
   * the batch unaffected.
   */
  public List<ReserveOutcome> reserveBatch(List<ReserveBatchItem> items) {
    return inTx(
        c -> {
          List<ReserveOutcome> outcomes = new ArrayList<>(items.size());
          for (ReserveBatchItem item : items) {
            Savepoint sp = c.setSavepoint();
            try {
              Reservation r = reserveTx(c, item.reservation(), item.event(), item.idempotencyKey());
              outcomes.add(ReserveOutcome.success(r));
            } catch (ApiException e) {
              c.rollback(sp);
              outcomes.add(ReserveOutcome.failure(e));
            } catch (SQLException e) {
              c.rollback(sp);
              outcomes.add(
                  ReserveOutcome.failure(handleTxSqlException("reserve stock (batch item)", e)));
            }
          }
          return outcomes;
        },
        "bulk reserve stock");
  }

  private Reservation reserveTx(Connection c, Reservation r, OutboxRow event, String idempotencyKey)
      throws SQLException {
    if (idempotencyKey != null) {
      Reservation existing = findReservationByIdempotencyKeyTx(c, r.tenantId(), idempotencyKey);
      if (existing != null) {
        return existing;
      }
    }
    BigDecimal available = availableForUpdate(c, r.tenantId(), r.storeId(), r.variantId());
    if (available.compareTo(r.qty()) < 0) {
      throw ApiException.unprocessable(
          "INSUFFICIENT_STOCK",
          "Only " + available.toPlainString() + " available, requested " + r.qty().toPlainString());
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
        r.id(),
        MovementAttribution.system());
    insertOutbox(c, event);
    return r;
  }

  // ---------------------------------------------------------------- consume (FIFO deduct)
  /**
   * Consume a HELD reservation: FIFO-deduct from batches, mark CONSUMED, SALE movements + outbox.
   */
  public void consume(UUID tenantId, UUID reservationId) {
    inTx(
        c -> {
          consumeTx(c, tenantId, reservationId);
          return null;
        },
        "consume reservation");
  }

  /**
   * {@link #consume} deduped on {@code dedupeId}: mark + consume commit in ONE transaction (see
   * {@link #receiveOnce}). Returns false if already processed. Used by the OrderFulfilled consumer
   * so a redelivered event can't double-deduct a line whose reservation was already consumed.
   */
  public boolean consumeOnce(
      UUID dedupeId, String consumerName, UUID tenantId, UUID reservationId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, dedupeId, consumerName)) {
            return false;
          }
          consumeTx(c, tenantId, reservationId);
          return true;
        },
        "consume reservation (deduped)");
  }

  private void consumeTx(Connection c, UUID tenantId, UUID reservationId) throws SQLException {
    Reservation r = loadReservationForUpdate(c, tenantId, reservationId);
    if (!Reservation.HELD.equals(r.status())) {
      throw ApiException.unprocessable("RESERVATION_NOT_HELD", "Reservation is " + r.status());
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
        zonePriorities,
        MovementAttribution.system());
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
          deductFifo(
              c,
              tenantId,
              storeId,
              variantId,
              qty,
              MoveType.SALE,
              "ORDER",
              orderId,
              MovementAttribution.system());
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
          deductFifo(
              c,
              tenantId,
              storeId,
              variantId,
              qty,
              MoveType.SALE,
              "ORDER",
              orderId,
              MovementAttribution.system());
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
              reservationId,
              MovementAttribution.system());
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
   * Per-(store,variant) aggregation shared by the paginated list and the summary count: on-hand
   * (sum remaining batches), reserved (sum HELD). Binds two params — {@code tenant_id} for
   * reservations then {@code tenant_id} for batches. Callers append store/cursor filters + GROUP BY
   * / ORDER BY / LIMIT, or wrap it for aggregate counts.
   */
  private static final String LEVELS_CORE =
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
      WHERE b.tenant_id = ? AND b.material_status = 'AVAILABLE'""";

  /**
   * DB-load safety valve for {@link #levels}: internal callers (min/max planning, storefront
   * availability) legitimately want every SKU in one call, not a client-paginated page, but nothing
   * upstream caps how large a tenant's catalog can grow. This bounds the worst case instead of
   * leaving the query truly unbounded.
   */
  private static final int LEVELS_SAFETY_CAP = 20_000;

  /**
   * On-hand / reserved / available per (store,variant) — every SKU, for internal callers (min/max
   * planning, storefront) that need the full set rather than a client-paginated page. Capped at
   * {@link #LEVELS_SAFETY_CAP} as a DB-load safety valve. Paginated reads use {@link #levelsPage}.
   */
  public List<Level> levels(UUID tenantId, UUID storeId) {
    return levelsPage(tenantId, storeId, null, null, LEVELS_SAFETY_CAP);
  }

  /**
   * One keyset page of levels, ordered by {@code (store_id, variant_id)} and starting strictly
   * after the {@code (afterStoreId, afterVariantId)} cursor when both are supplied. {@code limit}
   * caps the returned rows so the caller can request {@code limit + 1} to detect a further page.
   */
  public List<Level> levelsPage(
      UUID tenantId, UUID storeId, UUID afterStoreId, UUID afterVariantId, int limit) {
    boolean hasStore = storeId != null;
    boolean hasCursor = afterStoreId != null && afterVariantId != null;
    String sql =
        LEVELS_CORE
            + (hasStore ? " AND b.store_id = ?" : "")
            + (hasCursor ? " AND (b.store_id, b.variant_id) > (?, ?)" : "")
            + " GROUP BY b.store_id, b.variant_id"
            + " ORDER BY b.store_id, b.variant_id"
            + " LIMIT ?";
    return query(
        sql,
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, tenantId);
          if (hasStore) {
            ps.setObject(i++, storeId);
          }
          if (hasCursor) {
            ps.setObject(i++, afterStoreId);
            ps.setObject(i++, afterVariantId);
          }
          ps.setInt(i, limit);
        },
        InventoryRepository::mapLevel,
        "load levels");
  }

  /**
   * Aggregate counts over the same per-(store,variant) levels: total distinct SKUs and how many are
   * at or below {@code lowThreshold} available. A single query that never materializes the full
   * list — backs the admin dashboard KPI tiles.
   */
  public LevelSummary levelsSummary(UUID tenantId, UUID storeId, BigDecimal lowThreshold) {
    boolean hasStore = storeId != null;
    String sql =
        "SELECT COUNT(*) AS sku_count,"
            + " COUNT(*) FILTER (WHERE lv.on_hand - lv.reserved <= ?) AS low_count FROM ("
            + LEVELS_CORE
            + (hasStore ? " AND b.store_id = ?" : "")
            + " GROUP BY b.store_id, b.variant_id) lv";
    List<LevelSummary> rows =
        query(
            sql,
            ps -> {
              int i = 1;
              ps.setBigDecimal(i++, lowThreshold);
              ps.setObject(i++, tenantId);
              ps.setObject(i++, tenantId);
              if (hasStore) {
                ps.setObject(i, storeId);
              }
            },
            rs -> new LevelSummary(rs.getLong("sku_count"), rs.getLong("low_count")),
            "load levels summary");
    return rows.isEmpty() ? new LevelSummary(0, 0) : rows.get(0);
  }

  private static Level mapLevel(ResultSet rs) throws SQLException {
    BigDecimal onHand = rs.getBigDecimal("on_hand");
    BigDecimal reserved = rs.getBigDecimal("reserved");
    return new Level(
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        onHand,
        reserved,
        onHand.subtract(reserved));
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

  /**
   * The HELD reservations placed for one order at checkout — consumed at fulfilment, released on
   * cancellation (both driven by order-svc events).
   */
  public List<Reservation> heldReservationsByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at,"
            + " created_at FROM reservations WHERE tenant_id = ? AND order_id = ?"
            + " AND status = 'HELD'",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        InventoryRepository::mapReservation,
        "held reservations by order");
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

  // ---------------------------------------------------------------- cycle counting (Gap #10)
  // Only applyAdjustments stays here — see CycleCountRepository's class javadoc for why.

  /**
   * Apply stock adjustments for all APPROVED lines and mark them ADJUSTED in one transaction.
   * Returns the number of lines adjusted.
   */
  public int applyAdjustments(UUID tenantId, UUID headerId, OutboxRow event, UUID actorId) {
    MovementAttribution attribution =
        MovementAttribution.by(actorId, MovementAttribution.CYCLE_COUNT_VARIANCE);
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
                      Ids.newId(),
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
                  headerId,
                  attribution);
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
                  headerId,
                  attribution);
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

  /** Only {@code applyAdjustments} above needs this — see the section comment. */
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
      UUID refId,
      MovementAttribution attribution)
      throws SQLException {
    deductBatches(
        c,
        tenantId,
        storeId,
        variantId,
        qty,
        moveType,
        refType,
        refId,
        null,
        null,
        null,
        attribution);
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
      List<UUID> zonePriorityOrder,
      MovementAttribution attribution)
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
          c,
          tenantId,
          storeId,
          variantId,
          batchId,
          moveType,
          take.negate(),
          refType,
          refId,
          attribution);
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

  /**
   * Inserts a batch and, if an open recall covers it, holds it before the transaction commits.
   * Every way stock enters a store comes through here — a delivery, a transfer, a return, a count —
   * so a recalled lot arriving the day after the recall is never on sale for a moment.
   *
   * @return why a recall now holds the batch, or null when none does
   */
  private String insertBatch(Connection c, Batch b, String idempotencyKey) throws SQLException {
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
    return RecallRepository.holdOnArrival(c, b);
  }

  private static Batch recalledCopy(Batch b, String reason) {
    return new Batch(
        b.id(),
        b.tenantId(),
        b.storeId(),
        b.variantId(),
        b.batchNo(),
        b.receivedQty(),
        b.remainingQty(),
        b.costPrice(),
        b.expiryDate(),
        b.createdAt(),
        b.status(),
        Batch.MATERIAL_RECALLED,
        reason,
        b.grade(),
        b.zoneId());
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

  /**
   * Writes one append-only stock movement (golden rule #8).
   *
   * <p>System-caused movements pass {@code null} for both {@code reasonCode} and {@code actorId}:
   * they already carry {@code refType}/{@code refId} pointing at the order, GRN or transfer header
   * that caused them, and that record names its own actor. NULL here therefore means "see the
   * referenced record", not "unknown". Adjustments are the exception -- they are written with
   * {@code refId = null}, so without these two columns nothing links a stock correction to a person
   * or a reason (SJ-D4).
   */
  static void insertMovement(
      Connection c,
      UUID tenantId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String type,
      BigDecimal qty,
      String refType,
      UUID refId,
      MovementAttribution attribution)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO stock_movements"
                + " (id, tenant_id, store_id, variant_id, batch_id, type, qty, ref_type, ref_id,"
                + "  reason_code, actor_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, storeId);
      ps.setObject(4, variantId);
      ps.setObject(5, batchId);
      ps.setString(6, type);
      ps.setBigDecimal(7, qty);
      ps.setString(8, refType);
      ps.setObject(9, refId);
      ps.setString(10, attribution.reasonCode());
      ps.setObject(11, attribution.actorId());
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
                orderId,
                MovementAttribution.system());
            Batch dest =
                new Batch(
                    Ids.newId(),
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
                orderId,
                MovementAttribution.system());
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
                orderId,
                MovementAttribution.system());
            if (isDirect) {
              Batch dest =
                  new Batch(
                      Ids.newId(),
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
                  orderId,
                  MovementAttribution.system());
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
                    Ids.newId(),
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
                orderId,
                MovementAttribution.system());
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
  // Both methods moved: ROP → ReorderPointRepository, Kanban → KanbanRepository
  // (same aggregate/table as each).

  // ─────────────────────────────────────────────────── picking rules (Gap #38)
  // CRUD for rules/zone-priorities/assignments moved to PickingRuleRepository.
  // previewPickOrder (+ its pickOrderClause/buildZoneCaseClause helpers) stays here
  // since it needs mapBatch — same family as batches(read)/material-status/grade-update.

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

  /**
   * Internal-only copy of the picking-rule resolution used by {@code consumeTx} so FIFO/FEFO/zone
   * deduction picks the right strategy. The public, service-facing CRUD for picking rules lives in
   * {@link PickingRuleRepository}; this duplicates just the read path rather than injecting that
   * repo. Matches the original (pre-extraction) behavior exactly: {@code query()} acquires its own
   * connection, so this was never part of {@code consumeTx}'s transaction even before the split.
   */
  private Optional<PickingRule> resolvePickingRule(UUID tenantId, UUID storeId, UUID variantId) {
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

  private List<PickingRuleZonePriority> listZonePriorities(UUID tenantId, UUID ruleId) {
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
