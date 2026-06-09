package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.MoveType;
import com.shelfj.inventory.domain.Domain.Movement;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.inventory.domain.Domain.Threshold;
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
                  "INSERT INTO reorder_thresholds (id, tenant_id, store_id, variant_id, threshold)"
                      + " VALUES (?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " DO UPDATE SET threshold = EXCLUDED.threshold"
                      + " RETURNING id, tenant_id, store_id, variant_id, threshold")) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setObject(3, t.storeId());
            ps.setObject(4, t.variantId());
            ps.setBigDecimal(5, t.threshold());
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return new Threshold(
                  rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("store_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getBigDecimal("threshold"));
            }
          }
        },
        "upsert threshold");
  }

  public List<Threshold> listThresholds(UUID tenantId, UUID storeId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, threshold"
                + " FROM reorder_thresholds WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    sb.append(" ORDER BY store_id, variant_id");
    return query(
        sb.toString(),
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        rs ->
            new Threshold(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("threshold")),
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
}
