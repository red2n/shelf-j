package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.Batch;
import com.shelfj.inventory.domain.Domain.Level;
import com.shelfj.inventory.domain.Domain.MoveType;
import com.shelfj.inventory.domain.Domain.Reservation;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
                    Instant.now());
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
                WHERE b.tenant_id = ?"""
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

  /** Available = sum(remaining batches) − sum(HELD reservations), with the batch rows locked. */
  private BigDecimal availableForUpdate(Connection c, UUID tenantId, UUID storeId, UUID variantId)
      throws SQLException {
    // Lock the batch rows first (FOR UPDATE can't be combined with an aggregate), then sum them.
    BigDecimal onHand = BigDecimal.ZERO;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT remaining_qty FROM inventory_batches"
                + " WHERE tenant_id=? AND store_id=? AND variant_id=? FOR UPDATE")) {
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
                + " remaining_qty, cost_price, expiry_date, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, b.id());
      ps.setObject(2, b.tenantId());
      ps.setObject(3, b.storeId());
      ps.setObject(4, b.variantId());
      ps.setString(5, b.batchNo());
      ps.setBigDecimal(6, b.receivedQty());
      ps.setBigDecimal(7, b.remainingQty());
      ps.setBigDecimal(8, b.costPrice());
      ps.setDate(9, b.expiryDate() == null ? null : Date.valueOf(b.expiryDate()));
      ps.setObject(10, b.createdAt());
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
      ps.setObject(8, r.expiresAt());
      ps.setObject(9, r.createdAt());
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

  private static Reservation mapReservation(ResultSet rs) throws SQLException {
    Instant exp = rs.getObject("expires_at", Instant.class);
    return new Reservation(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getObject("order_id", UUID.class),
        rs.getString("status"),
        exp,
        rs.getObject("created_at", Instant.class));
  }
}
