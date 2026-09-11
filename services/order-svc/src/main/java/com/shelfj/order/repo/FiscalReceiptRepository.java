package com.shelfj.order.repo;

import com.shelfj.order.domain.Domain.FiscalReceipt;
import com.shelfj.order.domain.Domain.SequenceGap;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The gapless receipt sequence.
 *
 * <p>Everything here exists to make one promise keepable: the numbers in a series run consecutively
 * with no holes, and that can be demonstrated to an inspector.
 */
@ApplicationScoped
public class FiscalReceiptRepository extends BaseJdbcRepository {

  /**
   * Issues the receipt for an order, allocating the next number in its series.
   *
   * <p><b>Idempotent.</b> A second call for the same order returns the document already issued
   * rather than allocating another. A reprint is not a sale, and two numbers for one sale is how a
   * day's takings end up counted twice.
   *
   * <p><b>The allocation is the point.</b> The counter is incremented with an {@code UPDATE …
   * RETURNING} inside this transaction, so the row lock serialises concurrent tills and a rollback
   * puts the number back. A {@code SEQUENCE} would be faster and would gap on the first aborted
   * transaction — and the gap is exactly what an inspector asks about.
   */
  public FiscalReceipt issue(FiscalReceipt draft, String defaultPrefix) {
    return inTx(
        c -> {
          var existing = findByOrderTx(c, draft.tenantId(), draft.orderId());
          if (existing.isPresent()) {
            return existing.get();
          }

          // First sale in this series and period opens it. DO NOTHING rather than a prior SELECT:
          // two tills opening the same store on the same morning is the normal case, not a race
          // worth failing.
          try (var open =
              c.prepareStatement(
                  "INSERT INTO receipt_series"
                      + " (tenant_id, store_id, series_code, period, next_number, prefix)"
                      + " VALUES (?,?,?,?,1,?) ON CONFLICT DO NOTHING")) {
            open.setObject(1, draft.tenantId());
            open.setObject(2, draft.storeId());
            open.setString(3, draft.seriesCode());
            open.setString(4, draft.period());
            open.setString(5, defaultPrefix);
            open.executeUpdate();
          }

          long number;
          String prefix;
          try (var take =
              c.prepareStatement(
                  "UPDATE receipt_series SET next_number = next_number + 1"
                      + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
                      + " RETURNING next_number - 1, prefix")) {
            take.setObject(1, draft.tenantId());
            take.setObject(2, draft.storeId());
            take.setString(3, draft.seriesCode());
            take.setString(4, draft.period());
            try (ResultSet rs = take.executeQuery()) {
              if (!rs.next()) {
                throw new SQLException("receipt series vanished between open and take");
              }
              number = rs.getLong(1);
              prefix = rs.getString(2);
            }
          }

          String full =
              (prefix == null || prefix.isBlank() ? "" : prefix + "-")
                  + draft.period()
                  + "-"
                  + String.format("%06d", number);

          try (var ins =
              c.prepareStatement(
                  "INSERT INTO fiscal_receipts"
                      + " (id, tenant_id, store_id, series_code, period, number, full_number,"
                      + "  order_id, issued_by, currency, gross_total, tax_total)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setObject(1, draft.id());
            ins.setObject(2, draft.tenantId());
            ins.setObject(3, draft.storeId());
            ins.setString(4, draft.seriesCode());
            ins.setString(5, draft.period());
            ins.setLong(6, number);
            ins.setString(7, full);
            ins.setObject(8, draft.orderId());
            ins.setObject(9, draft.issuedBy());
            ins.setString(10, draft.currency());
            ins.setBigDecimal(11, draft.grossTotal());
            ins.setBigDecimal(12, draft.taxTotal());
            ins.executeUpdate();
          }

          return findByOrderTx(c, draft.tenantId(), draft.orderId()).orElseThrow();
        },
        "issue fiscal receipt");
  }

  private static Optional<FiscalReceipt> findByOrderTx(Connection c, UUID tenantId, UUID orderId)
      throws SQLException {
    try (var st =
        c.prepareStatement(
            "SELECT id, tenant_id, store_id, series_code, period, number, full_number, order_id,"
                + " issued_at, issued_by, currency, gross_total, tax_total, voided_at, void_reason"
                + " FROM fiscal_receipts WHERE tenant_id = ? AND order_id = ?")) {
      st.setObject(1, tenantId);
      st.setObject(2, orderId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  public Optional<FiscalReceipt> findByOrder(UUID tenantId, UUID orderId) {
    var rows =
        query(
            "SELECT id, tenant_id, store_id, series_code, period, number, full_number, order_id,"
                + " issued_at, issued_by, currency, gross_total, tax_total, voided_at, void_reason"
                + " FROM fiscal_receipts WHERE tenant_id = ? AND order_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, orderId);
            },
            FiscalReceiptRepository::map,
            "find receipt by order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Marks a receipt's sale as void. The number stays.
   *
   * <p>Deleting or renumbering it is precisely the fraud gapless numbering exists to make visible:
   * ring the sale, take the cash, void the receipt, close the gap, and the till balances. Here the
   * document remains, numbered, marked void and with a reason against it.
   */
  public boolean markVoided(UUID tenantId, UUID orderId, String reason) {
    return inTx(
        c -> {
          try (var st =
              c.prepareStatement(
                  "UPDATE fiscal_receipts SET voided_at = now(), void_reason = ?"
                      + " WHERE tenant_id = ? AND order_id = ? AND voided_at IS NULL")) {
            st.setString(1, reason);
            st.setObject(2, tenantId);
            st.setObject(3, orderId);
            return st.executeUpdate() > 0;
          }
        },
        "void fiscal receipt");
  }

  public List<FiscalReceipt> listSeries(
      UUID tenantId, UUID storeId, String series, String period, int limit) {
    return query(
        "SELECT id, tenant_id, store_id, series_code, period, number, full_number, order_id,"
            + " issued_at, issued_by, currency, gross_total, tax_total, voided_at, void_reason"
            + " FROM fiscal_receipts"
            + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
            + " ORDER BY number LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
          ps.setInt(5, limit);
        },
        FiscalReceiptRepository::map,
        "list receipt series");
  }

  /**
   * Every hole in a series — the inspector's question, answered by the database rather than by
   * assertion.
   *
   * <p>A row is returned for each number that has an issued receipt before it and none at it, up to
   * the highest number issued. An empty result is the proof that the sequence is intact; it is
   * deliberately not a boolean, because "there is a gap" is not a useful answer without "where".
   */
  public List<SequenceGap> findGaps(UUID tenantId, UUID storeId, String series, String period) {
    return query(
        "WITH s AS ("
            + "  SELECT number FROM fiscal_receipts"
            + "   WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?"
            + ")"
            + " SELECT r.number + 1 AS gap_from,"
            + "        (SELECT MIN(n.number) FROM s n WHERE n.number > r.number) - 1 AS gap_to"
            + "   FROM s r"
            + "  WHERE NOT EXISTS (SELECT 1 FROM s n WHERE n.number = r.number + 1)"
            + "    AND r.number < (SELECT MAX(number) FROM s)"
            + "  ORDER BY 1",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
        },
        rs -> new SequenceGap(rs.getLong(1), rs.getLong(2)),
        "find sequence gaps");
  }

  /** First number, last number and how many were issued — the header of the same audit. */
  public long[] seriesBounds(UUID tenantId, UUID storeId, String series, String period) {
    var rows =
        query(
            "SELECT COALESCE(MIN(number),0), COALESCE(MAX(number),0), COUNT(*)"
                + " FROM fiscal_receipts"
                + " WHERE tenant_id = ? AND store_id = ? AND series_code = ? AND period = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setString(3, series);
              ps.setString(4, period);
            },
            rs -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)},
            "receipt series bounds");
    return rows.isEmpty() ? new long[] {0, 0, 0} : rows.get(0);
  }

  private static FiscalReceipt map(ResultSet rs) throws SQLException {
    OffsetDateTime voided = rs.getObject(14, OffsetDateTime.class);
    return new FiscalReceipt(
        (UUID) rs.getObject(1),
        (UUID) rs.getObject(2),
        (UUID) rs.getObject(3),
        rs.getString(4),
        rs.getString(5),
        rs.getLong(6),
        rs.getString(7),
        (UUID) rs.getObject(8),
        rs.getObject(9, OffsetDateTime.class).toInstant(),
        (UUID) rs.getObject(10),
        rs.getString(11),
        rs.getBigDecimal(12),
        rs.getBigDecimal(13),
        voided == null ? null : voided.toInstant(),
        rs.getString(15));
  }
}
