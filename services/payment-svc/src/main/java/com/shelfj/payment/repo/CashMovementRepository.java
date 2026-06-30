package com.shelfj.payment.repo;

import com.shelfj.payment.dto.Dtos.CashMovementResponse;
import com.shelfj.payment.dto.Dtos.ZReportResponse;
import com.shelfj.service.BaseOutboxRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for cash movements (pay-in/pay-out) and daily Z-reports. */
@ApplicationScoped
public class CashMovementRepository extends BaseOutboxRepository {

  /**
   * Record a pay-in/pay-out. If the same Idempotency-Key was already stored for this tenant, the
   * original movement is returned unchanged (replay) — a retried request must not double-count cash
   * in/out of the till.
   */
  public CashMovementResponse insertMovement(
      UUID tenantId,
      UUID storeId,
      UUID tillSessionId,
      String direction,
      BigDecimal amount,
      String reason,
      UUID authorisedBy,
      UUID recordedBy,
      String idempotencyKey) {
    return inTx(
        c -> {
          if (idempotencyKey != null) {
            CashMovementResponse existing = findMovementByKeyTx(c, tenantId, idempotencyKey);
            if (existing != null) {
              return existing;
            }
          }
          UUID id = UUID.randomUUID();
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO cash_movements (id, tenant_id, store_id, till_session_id,"
                      + " direction, amount, reason, authorised_by, recorded_by, created_at,"
                      + " idempotency_key) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, tillSessionId);
            ps.setString(5, direction);
            ps.setBigDecimal(6, amount);
            ps.setString(7, reason);
            ps.setObject(8, authorisedBy);
            ps.setObject(9, recordedBy);
            ps.setObject(10, now.atOffset(ZoneOffset.UTC));
            ps.setString(11, idempotencyKey);
            ps.executeUpdate();
          }
          return new CashMovementResponse(
              id, storeId, tillSessionId, direction, amount, reason, now);
        },
        "insert cash movement");
  }

  private CashMovementResponse findMovementByKeyTx(
      Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, till_session_id, direction, amount, reason,"
                + " authorised_by, recorded_by, created_at"
                + " FROM cash_movements WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? mapMovement(rs) : null;
      }
    }
  }

  public List<CashMovementResponse> listMovements(UUID tenantId, UUID tillSessionId) {
    return query(
        "SELECT id, tenant_id, store_id, till_session_id, direction, amount, reason,"
            + " authorised_by, recorded_by, created_at"
            + " FROM cash_movements WHERE tenant_id=? AND till_session_id=? ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, tillSessionId);
        },
        CashMovementRepository::mapMovement,
        "list cash movements");
  }

  /**
   * Generate a Z-report for a store + business date. Aggregates sales, refunds, tender types, and
   * cash movements from the transactional tables within payment-svc. Uses INSERT ... ON CONFLICT DO
   * UPDATE so re-running is idempotent.
   */
  public ZReportResponse generateZReport(
      UUID tenantId,
      UUID storeId,
      LocalDate businessDate,
      BigDecimal countedCash,
      String currency,
      UUID generatedBy) {
    return inTx(
        c -> {
          LocalDate next = businessDate.plusDays(1);
          BigDecimal totalSales = sumPayments(c, tenantId, storeId, businessDate, next, "CAPTURED");
          BigDecimal totalRefunds = sumRefunds(c, tenantId, storeId, businessDate, next);
          BigDecimal cashSales = sumTender(c, tenantId, storeId, businessDate, next, "CASH");
          BigDecimal cardSales = sumTender(c, tenantId, storeId, businessDate, next, "CARD");
          BigDecimal giftCardSales =
              sumTender(c, tenantId, storeId, businessDate, next, "GIFT_CARD");
          BigDecimal otherSales =
              totalSales.subtract(cashSales).subtract(cardSales).subtract(giftCardSales);
          BigDecimal netSales = totalSales.subtract(totalRefunds);

          BigDecimal openFloat = sumTillFloats(c, tenantId, storeId, businessDate, next);
          BigDecimal drops = sumDrops(c, tenantId, storeId, businessDate, next);
          BigDecimal payIns = sumMovements(c, tenantId, storeId, businessDate, next, "PAY_IN");
          BigDecimal payOuts = sumMovements(c, tenantId, storeId, businessDate, next, "PAY_OUT");
          BigDecimal expectedCash =
              openFloat.add(cashSales).add(payIns).subtract(drops).subtract(payOuts);
          BigDecimal overShort = countedCash.subtract(expectedCash);
          int txCount = countTransactions(c, tenantId, storeId, businessDate, next);

          UUID reportId = UUID.randomUUID();
          Instant now = Instant.now();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO z_reports (id, tenant_id, store_id, business_date,"
                      + " total_sales, total_refunds, total_discounts, total_tax, net_sales,"
                      + " cash_sales, card_sales, gift_card_sales, other_sales,"
                      + " opening_float, cash_drops, pay_ins, pay_outs,"
                      + " expected_cash, counted_cash, over_short, transaction_count,"
                      + " currency, generated_by, generated_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, business_date) DO UPDATE SET"
                      + " counted_cash=EXCLUDED.counted_cash, over_short=EXCLUDED.over_short,"
                      + " generated_by=EXCLUDED.generated_by, generated_at=EXCLUDED.generated_at"
                      + " RETURNING id, generated_at")) {
            ps.setObject(1, reportId);
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, java.sql.Date.valueOf(businessDate));
            ps.setBigDecimal(5, totalSales);
            ps.setBigDecimal(6, totalRefunds);
            ps.setBigDecimal(7, BigDecimal.ZERO); // discounts from order-svc — not owned here
            ps.setBigDecimal(8, BigDecimal.ZERO); // tax from order-svc
            ps.setBigDecimal(9, netSales);
            ps.setBigDecimal(10, cashSales);
            ps.setBigDecimal(11, cardSales);
            ps.setBigDecimal(12, giftCardSales);
            ps.setBigDecimal(13, otherSales.max(BigDecimal.ZERO));
            ps.setBigDecimal(14, openFloat);
            ps.setBigDecimal(15, drops);
            ps.setBigDecimal(16, payIns);
            ps.setBigDecimal(17, payOuts);
            ps.setBigDecimal(18, expectedCash);
            ps.setBigDecimal(19, countedCash);
            ps.setBigDecimal(20, overShort);
            ps.setInt(21, txCount);
            ps.setString(22, currency);
            ps.setObject(23, generatedBy);
            ps.setObject(24, now.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              reportId = rs.getObject("id", UUID.class);
              now = rs.getObject("generated_at", OffsetDateTime.class).toInstant();
            }
          }
          return new ZReportResponse(
              reportId,
              storeId,
              businessDate,
              totalSales,
              totalRefunds,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              netSales,
              cashSales,
              cardSales,
              giftCardSales,
              openFloat,
              drops,
              payIns,
              payOuts,
              expectedCash,
              countedCash,
              overShort,
              txCount,
              currency,
              now);
        },
        "generate z-report");
  }

  public Optional<ZReportResponse> findZReport(
      UUID tenantId, UUID storeId, LocalDate businessDate) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, tenant_id, store_id, business_date, total_sales, total_refunds,"
                      + " total_discounts, total_tax, net_sales, cash_sales, card_sales,"
                      + " gift_card_sales, other_sales, opening_float, cash_drops, pay_ins,"
                      + " pay_outs, expected_cash, counted_cash, over_short, transaction_count,"
                      + " currency, generated_by, generated_at"
                      + " FROM z_reports WHERE tenant_id=? AND store_id=? AND business_date=?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setObject(3, java.sql.Date.valueOf(businessDate));
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.empty();
              return Optional.of(mapZReport(rs));
            }
          }
        },
        "find z-report");
  }

  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    return dbError(what, e);
  }

  // ─────────────────────────────────────────── aggregation helpers

  private BigDecimal sumPayments(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to, String status)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM payment_tenders"
                + " WHERE tenant_id=? AND store_id=? AND status=?"
                + " AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, status);
      ps.setObject(4, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(5, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumRefunds(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM refund_tenders"
                + " WHERE tenant_id=? AND store_id=? AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumTender(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to, String method)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM payment_tenders"
                + " WHERE tenant_id=? AND store_id=? AND method=? AND status='CAPTURED'"
                + " AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, method);
      ps.setObject(4, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(5, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumTillFloats(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(float_amount),0) FROM till_sessions"
                + " WHERE tenant_id=? AND store_id=? AND opened_at >= ? AND opened_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumDrops(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(cd.amount),0) FROM cash_drops cd"
                + " JOIN till_sessions ts ON ts.id = cd.till_session_id AND ts.tenant_id = cd.tenant_id"
                + " WHERE cd.tenant_id=? AND ts.store_id=? AND cd.created_at >= ? AND cd.created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private BigDecimal sumMovements(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to, String direction)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COALESCE(SUM(amount),0) FROM cash_movements"
                + " WHERE tenant_id=? AND store_id=? AND direction=?"
                + " AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setString(3, direction);
      ps.setObject(4, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(5, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
      }
    }
  }

  private int countTransactions(
      Connection c, UUID tenantId, UUID storeId, LocalDate from, LocalDate to) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT COUNT(DISTINCT order_id) FROM payment_tenders"
                + " WHERE tenant_id=? AND store_id=? AND created_at >= ? AND created_at < ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, from.atStartOfDay().atOffset(ZoneOffset.UTC));
      ps.setObject(4, to.atStartOfDay().atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  // ─────────────────────────────────────────── row mappers

  private static CashMovementResponse mapMovement(ResultSet rs) throws SQLException {
    return new CashMovementResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("till_session_id", UUID.class),
        rs.getString("direction"),
        rs.getBigDecimal("amount"),
        rs.getString("reason"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ZReportResponse mapZReport(ResultSet rs) throws SQLException {
    return new ZReportResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getDate("business_date").toLocalDate(),
        rs.getBigDecimal("total_sales"),
        rs.getBigDecimal("total_refunds"),
        rs.getBigDecimal("total_discounts"),
        rs.getBigDecimal("total_tax"),
        rs.getBigDecimal("net_sales"),
        rs.getBigDecimal("cash_sales"),
        rs.getBigDecimal("card_sales"),
        rs.getBigDecimal("gift_card_sales"),
        rs.getBigDecimal("opening_float"),
        rs.getBigDecimal("cash_drops"),
        rs.getBigDecimal("pay_ins"),
        rs.getBigDecimal("pay_outs"),
        rs.getBigDecimal("expected_cash"),
        rs.getBigDecimal("counted_cash"),
        rs.getBigDecimal("over_short"),
        rs.getInt("transaction_count"),
        rs.getString("currency"),
        rs.getObject("generated_at", OffsetDateTime.class).toInstant());
  }
}
