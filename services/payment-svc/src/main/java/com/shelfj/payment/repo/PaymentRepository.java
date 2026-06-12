package com.shelfj.payment.repo;

import com.shelfj.payment.domain.Domain.PaymentTender;
import com.shelfj.payment.domain.Domain.RefundTender;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PaymentRepository extends BaseOutboxRepository {

  /**
   * Record a captured tender. If the same Idempotency-Key was already stored for this tenant, the
   * original tender is returned unchanged (replay) — the retry must not double-charge AND must not
   * surface as an error (golden rule #11).
   */
  public PaymentTender createTender(PaymentTender t, OutboxRow event) {
    return inTx(
        c -> {
          if (t.idempotencyKey() != null) {
            PaymentTender existing = findTenderByKeyTx(c, t.tenantId(), t.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO payment_tenders"
                      + " (id, tenant_id, order_id, amount, method, reference,"
                      + "  idempotency_key, status, notes, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setObject(3, t.orderId());
            ps.setBigDecimal(4, t.amount());
            ps.setString(5, t.method());
            ps.setString(6, t.reference());
            ps.setString(7, t.idempotencyKey());
            ps.setString(8, t.status());
            ps.setString(9, t.notes());
            // pgjdbc cannot infer a SQL type for a raw java.time.Instant.
            ps.setObject(10, t.createdAt().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return t;
        },
        "create payment tender");
  }

  /**
   * Record a refund with the cumulative cap enforced atomically: the payment row is locked ({@code
   * FOR UPDATE}) before existing refunds are summed, so two concurrent refunds cannot both pass the
   * check and together exceed the original payment. Duplicate Idempotency-Key replays the stored
   * refund.
   */
  public RefundTender createRefundGuarded(RefundTender r, OutboxRow event) {
    return inTx(
        c -> {
          if (r.idempotencyKey() != null) {
            RefundTender existing = findRefundByKeyTx(c, r.tenantId(), r.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }

          PaymentTender payment = lockTenderTx(c, r.tenantId(), r.paymentId());
          if (payment == null) {
            throw com.shelfj.web.ApiException.notFound(
                "PAYMENT_NOT_FOUND", "payment tender not found");
          }
          if (!payment.orderId().equals(r.orderId())) {
            throw com.shelfj.web.ApiException.conflict(
                "PAYMENT_ORDER_MISMATCH", "payment does not belong to this order");
          }

          BigDecimal alreadyRefunded = sumRefundsTx(c, r.tenantId(), r.paymentId());
          if (alreadyRefunded.add(r.amount()).compareTo(payment.amount()) > 0) {
            throw com.shelfj.web.ApiException.conflict(
                "REFUND_EXCEEDS_PAYMENT",
                "total refunds would exceed original payment of " + payment.amount());
          }

          try (var ps =
              c.prepareStatement(
                  "INSERT INTO refund_tenders"
                      + " (id, tenant_id, order_id, payment_id, amount, method,"
                      + "  reference, idempotency_key, reason, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.orderId());
            ps.setObject(4, r.paymentId());
            ps.setBigDecimal(5, r.amount());
            ps.setString(6, r.method());
            ps.setString(7, r.reference());
            ps.setString(8, r.idempotencyKey());
            ps.setString(9, r.reason());
            // pgjdbc cannot infer a SQL type for a raw java.time.Instant.
            ps.setObject(10, r.createdAt().atOffset(java.time.ZoneOffset.UTC));
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return r;
        },
        "create refund tender");
  }

  /**
   * Concurrent same-key requests can both miss the replay pre-check; the unique index then rejects
   * the loser. Surface that as a retryable 409 instead of a generic 500 so the client's next retry
   * hits the replay path.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
      return new com.shelfj.web.ApiException(
          409,
          "IDEMPOTENCY_CONFLICT",
          "A request with this Idempotency-Key is already being processed - retry to fetch it",
          List.of(),
          e);
    }
    return dbError(what, e);
  }

  private PaymentTender findTenderByKeyTx(
      java.sql.Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at"
                + " FROM payment_tenders WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapTender(rs) : null;
      }
    }
  }

  private RefundTender findRefundByKeyTx(
      java.sql.Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, payment_id, amount, method,"
                + " reference, idempotency_key, reason, created_at"
                + " FROM refund_tenders WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapRefund(rs) : null;
      }
    }
  }

  private PaymentTender lockTenderTx(java.sql.Connection c, UUID tenantId, UUID tenderId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at"
                + " FROM payment_tenders WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, tenderId);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapTender(rs) : null;
      }
    }
  }

  private static BigDecimal sumRefundsTx(java.sql.Connection c, UUID tenantId, UUID paymentId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount), 0) AS total"
                + " FROM refund_tenders WHERE tenant_id=? AND payment_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, paymentId);
      try (var rs = ps.executeQuery()) {
        // An aggregate without GROUP BY always yields exactly one row.
        return rs.next() ? rs.getBigDecimal("total") : BigDecimal.ZERO;
      }
    }
  }

  public Optional<PaymentTender> findTender(UUID tenantId, UUID tenderId) {
    var rows =
        query(
            "SELECT id, tenant_id, order_id, amount, method, reference,"
                + " idempotency_key, status, notes, created_at"
                + " FROM payment_tenders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, tenderId);
            },
            this::mapTender,
            "find tender");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public List<PaymentTender> findTendersByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, amount, method, reference,"
            + " idempotency_key, status, notes, created_at"
            + " FROM payment_tenders WHERE tenant_id=? AND order_id=?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapTender,
        "list tenders by order");
  }

  public List<RefundTender> findRefundsByOrder(UUID tenantId, UUID orderId) {
    return query(
        "SELECT id, tenant_id, order_id, payment_id, amount, method,"
            + " reference, idempotency_key, reason, created_at"
            + " FROM refund_tenders WHERE tenant_id=? AND order_id=?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
        },
        this::mapRefund,
        "list refunds by order");
  }

  // ── mappers ───────────────────────────────────────────────────────────────

  private PaymentTender mapTender(ResultSet rs) throws SQLException {
    return new PaymentTender(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("method"),
        rs.getString("reference"),
        rs.getString("idempotency_key"),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getTimestamp("created_at").toInstant());
  }

  private RefundTender mapRefund(ResultSet rs) throws SQLException {
    return new RefundTender(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("method"),
        rs.getString("reference"),
        rs.getString("idempotency_key"),
        rs.getString("reason"),
        rs.getTimestamp("created_at").toInstant());
  }
}
