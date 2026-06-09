package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.AbcAssignment;
import com.shelfj.inventory.domain.Domain.AbcCompileRun;
import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.CycleCountHeader;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.inventory.domain.Domain.DemandBucket;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.MoveOrder;
import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.MoveType;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.SafetyStockParams;
import com.shelfj.inventory.domain.Domain.SerialMovement;
import com.shelfj.inventory.domain.Domain.SerialNumber;
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
  /** Create a batch + RECEIVE movement + outbox event, atomically. */
  public Batch receive(Batch batch, String refType, UUID refId, OutboxRow event) {
    return inTx(
        c -> {
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
          return batch;
        },
        "receive stock");
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
    inTx(
        c -> {
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
                    null);
            insertBatch(c, b);
          } else {
            deductFifo(
                c,
                tenantId,
                storeId,
                variantId,
                delta.negate(),
                MoveType.ADJUST,
                "ADJUSTMENT",
                null);
          }
          insertMovement(
              c, tenantId, storeId, variantId, null, MoveType.ADJUST, delta, "ADJUSTMENT", null);
          insertOutbox(c, event);
          return null;
        },
        "adjust stock");
  }

  // ---------------------------------------------------------------- reserve
  /**
   * Hold stock if available. Inserts a HELD reservation + RESERVE movement + outbox. Throws 409 if
   * short.
   */
  public Reservation reserve(Reservation r, OutboxRow event) {
    return inTx(
        c -> {
          BigDecimal available = availableForUpdate(c, r.tenantId(), r.storeId(), r.variantId());
          if (available.compareTo(r.qty()) < 0) {
            throw ApiException.unprocessable(
                "INSUFFICIENT_STOCK",
                "Only "
                    + available.toPlainString()
                    + " available, requested "
                    + r.qty().toPlainString());
          }
          insertReservation(c, r);
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
  public void consume(UUID tenantId, UUID reservationId, OutboxRow event) {
    inTx(
        c -> {
          Reservation r = loadReservationForUpdate(c, tenantId, reservationId);
          if (!Reservation.HELD.equals(r.status())) {
            throw ApiException.unprocessable(
                "RESERVATION_NOT_HELD", "Reservation is " + r.status());
          }
          deductFifo(
              c,
              tenantId,
              r.storeId(),
              r.variantId(),
              r.qty(),
              MoveType.SALE,
              "ORDER",
              r.orderId());
          setReservationStatus(c, reservationId, Reservation.CONSUMED);
          insertOutbox(c, event);
          return null;
        },
        "consume reservation");
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
                + " material_status, material_status_reason"
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
                + " material_status, material_status_reason"
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
                + " created_at FROM stock_movements WHERE tenant_id = ?");
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
                      + " material_status, material_status_reason")) {
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

  // ---------------------------------------------------------------- processed events

  public boolean markProcessedIfNew(UUID eventId, String consumer) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO processed_events (event_id, consumer) VALUES (?,?)"
                    + " ON CONFLICT (event_id) DO NOTHING")) {
      ps.setObject(1, eventId);
      ps.setString(2, consumer);
      return ps.executeUpdate() > 0;
    } catch (SQLException e) {
      throw dbError("mark processed event", e);
    }
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

  // ---------------------------------------------------------------- serial numbers

  /** Bulk-insert serial numbers + initial genealogy movement + outbox, atomically. */
  public List<SerialNumber> registerSerials(List<SerialNumber> serials, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO serial_numbers"
                      + " (id, tenant_id, store_id, variant_id, batch_id, serial_no, status, received_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, serial_no) DO NOTHING")) {
            for (SerialNumber s : serials) {
              ps.setObject(1, s.id());
              ps.setObject(2, s.tenantId());
              ps.setObject(3, s.storeId());
              ps.setObject(4, s.variantId());
              ps.setObject(5, s.batchId());
              ps.setString(6, s.serialNo());
              ps.setString(7, s.status());
              ps.setObject(8, s.receivedAt().atOffset(ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO serial_movements"
                      + " (id, tenant_id, serial_id, from_status, to_status, ref_type)"
                      + " VALUES (?,?,?,NULL,?,?)")) {
            for (SerialNumber s : serials) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, s.tenantId());
              ps.setObject(3, s.id());
              ps.setString(4, SerialNumber.IN_STOCK);
              ps.setString(5, "RECEIVE");
              ps.addBatch();
            }
            ps.executeBatch();
          }
          insertOutbox(c, event);
          return serials;
        },
        "register serials");
  }

  public List<SerialNumber> listSerials(
      UUID tenantId, UUID storeId, UUID variantId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY received_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        InventoryRepository::mapSerial,
        "list serials");
  }

  public Optional<SerialNumber> findSerial(UUID tenantId, UUID serialId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, serialId);
            },
            InventoryRepository::mapSerial,
            "get serial");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public Optional<SerialNumber> findSerialByNo(UUID tenantId, String serialNo) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ? AND serial_no = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, serialNo);
            },
            InventoryRepository::mapSerial,
            "lookup serial by no");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** Transition serial to a new status; records genealogy movement + outbox, atomically. */
  public Optional<SerialNumber> updateSerialStatus(
      UUID tenantId, UUID serialId, String newStatus, OutboxRow event) {
    return inTx(
        c -> {
          String oldStatus;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT status FROM serial_numbers"
                      + " WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, serialId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<SerialNumber>empty();
              oldStatus = rs.getString("status");
            }
          }
          SerialNumber updated;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE serial_numbers"
                      + " SET status = ?,"
                      + " sold_at = CASE WHEN ? = 'SOLD' THEN now() ELSE sold_at END"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, store_id, variant_id, batch_id,"
                      + " serial_no, status, received_at, sold_at")) {
            ps.setString(1, newStatus);
            ps.setString(2, newStatus);
            ps.setObject(3, tenantId);
            ps.setObject(4, serialId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<SerialNumber>empty();
              updated = mapSerial(rs);
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO serial_movements"
                      + " (id, tenant_id, serial_id, from_status, to_status)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setObject(3, serialId);
            ps.setString(4, oldStatus);
            ps.setString(5, newStatus);
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return Optional.of(updated);
        },
        "update serial status");
  }

  public List<SerialMovement> listSerialHistory(UUID tenantId, UUID serialId) {
    return query(
        "SELECT id, tenant_id, serial_id, from_status, to_status, ref_type, ref_id, created_at"
            + " FROM serial_movements WHERE tenant_id = ? AND serial_id = ?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, serialId);
        },
        InventoryRepository::mapSerialMovement,
        "list serial history");
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
   * FIFO deduction: walk batches soonest-expiry/oldest-first WITH FOR UPDATE, decrement remaining,
   * log SALE per batch.
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
    BigDecimal toDeduct = qty;
    List<Object[]> batches = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, remaining_qty FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? AND remaining_qty > 0"
                + " AND material_status='AVAILABLE'"
                + " ORDER BY expiry_date ASC NULLS LAST, created_at ASC FOR UPDATE")) {
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
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO inventory_batches"
                + " (id, tenant_id, store_id, variant_id, batch_no, received_qty,"
                + " remaining_qty, cost_price, expiry_date, created_at, status, material_status)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
      ps.executeUpdate();
    }
  }

  private void insertReservation(Connection c, Reservation r) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO reservations"
                + " (id, tenant_id, store_id, variant_id, qty, order_id, status, expires_at, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, r.id());
      ps.setObject(2, r.tenantId());
      ps.setObject(3, r.storeId());
      ps.setObject(4, r.variantId());
      ps.setBigDecimal(5, r.qty());
      ps.setObject(6, r.orderId());
      ps.setString(7, r.status());
      ps.setObject(8, r.expiresAt() == null ? null : r.expiresAt().atOffset(ZoneOffset.UTC));
      ps.setObject(9, r.createdAt().atOffset(ZoneOffset.UTC));
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
        rs.getString("material_status_reason"));
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

  private static SerialNumber mapSerial(ResultSet rs) throws SQLException {
    OffsetDateTime soldOdt = rs.getObject("sold_at", OffsetDateTime.class);
    return new SerialNumber(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("serial_no"),
        rs.getString("status"),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        soldOdt == null ? null : soldOdt.toInstant());
  }

  private static SerialMovement mapSerialMovement(ResultSet rs) throws SQLException {
    return new SerialMovement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("serial_id", UUID.class),
        rs.getString("from_status"),
        rs.getString("to_status"),
        rs.getString("ref_type"),
        rs.getObject("ref_id", UUID.class),
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
}
