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

  public PaymentTender createTender(PaymentTender t, OutboxRow event) {
    return inTx(
        c -> {
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
            ps.setObject(10, t.createdAt());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return t;
        },
        "create payment tender");
  }

  public RefundTender createRefund(RefundTender r, OutboxRow event) {
    return inTx(
        c -> {
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
            ps.setObject(10, r.createdAt());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return r;
        },
        "create refund tender");
  }

  /** Sum all committed refunds for a given payment. Used to enforce cumulative refund cap. */
  public BigDecimal sumRefunds(UUID tenantId, UUID paymentId) {
    var rows =
        query(
            "SELECT COALESCE(SUM(amount), 0) AS total"
                + " FROM refund_tenders WHERE tenant_id=? AND payment_id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, paymentId);
            },
            rs -> rs.getBigDecimal("total"),
            "sum refunds");
    return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
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
