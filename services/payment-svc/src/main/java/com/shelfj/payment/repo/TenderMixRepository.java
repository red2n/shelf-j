package com.shelfj.payment.repo;

import com.shelfj.payment.domain.Domain.TenderMixRow;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * How customers actually paid — the tender-mix report.
 *
 * <p>Reads {@code payment_tenders} and {@code refund_tenders}, both owned by this service. It could
 * not have been built in reporting-svc: {@code sales_facts} carries one gross amount per order and
 * no tender at all, so a split payment of £20 cash and £30 card is a single £50 row there.
 *
 * <p><b>Refunds are subtracted per method, not netted globally.</b> A £100 card sale refunded to
 * store credit is not a £0 card day — the card processor still settled £100 and the store still
 * carries £100 of credit. Keeping the two sides visible per method is the difference between a
 * report you can reconcile a merchant statement against and one you cannot.
 *
 * <p><b>Failures are counted, not hidden.</b> A method whose captures are fine but whose failure
 * count is climbing is a terminal or an acquirer problem, and this is the only report where that
 * shows up next to the volume it is costing.
 */
@ApplicationScoped
public class TenderMixRepository extends BaseJdbcRepository {

  /**
   * Captured and refunded amounts per tender method over a window.
   *
   * <p>The two tables are combined with UNION ALL into one pass rather than joined: a method with
   * refunds but no captures in the window, and one with captures but no refunds, both have to
   * appear, and neither an inner nor an outer join gives that symmetrically.
   *
   * <p>Amounts are summed across whatever currencies the rows carry, because they carry none —
   * neither table has a currency column, and since SJ-D2 a tenant trades in exactly one declared
   * currency. If per-tenant multi-currency ever arrives, this is one of the places that has to
   * learn about it.
   *
   * @param tenantId the owning tenant; always the first filter (golden rule #3)
   * @param from inclusive lower bound on tender time, or null for no lower bound
   * @param to exclusive upper bound, or null for no upper bound
   * @return one row per method, largest net first
   */
  public List<TenderMixRow> tenderMix(UUID tenantId, Instant from, Instant to) {
    // Each side contributes signed columns so the outer aggregate is a plain SUM. Written this
    // way rather than as two subqueries joined on method so that a method appearing on only one
    // side still produces a row.
    String window =
        (from != null ? " AND created_at >= ?" : "") + (to != null ? " AND created_at < ?" : "");

    String sql =
        "SELECT method,"
            + " SUM(captured)::numeric(14,4) AS captured_amount,"
            + " SUM(captured_n) AS captured_count,"
            + " SUM(refunded)::numeric(14,4) AS refunded_amount,"
            + " SUM(refunded_n) AS refunded_count,"
            + " SUM(failed_n) AS failed_count"
            + " FROM ("
            + "   SELECT method,"
            + "          CASE WHEN status = 'CAPTURED' THEN amount ELSE 0 END AS captured,"
            + "          CASE WHEN status = 'CAPTURED' THEN 1 ELSE 0 END AS captured_n,"
            + "          0 AS refunded, 0 AS refunded_n,"
            + "          CASE WHEN status <> 'CAPTURED' THEN 1 ELSE 0 END AS failed_n"
            + "     FROM payment_tenders"
            + "    WHERE tenant_id = ?"
            + window
            + "   UNION ALL"
            // refund_tenders has no status column: a row here is a refund that happened.
            + "   SELECT method, 0, 0, amount, 1, 0"
            + "     FROM refund_tenders"
            + "    WHERE tenant_id = ?"
            + window
            + " ) t"
            + " GROUP BY method"
            + " ORDER BY (SUM(captured) - SUM(refunded)) DESC, method ASC";

    var fromTs = from == null ? null : from.atOffset(ZoneOffset.UTC);
    var toTs = to == null ? null : to.atOffset(ZoneOffset.UTC);
    return query(
        sql,
        ps -> {
          int i = 1;
          // The same window binds twice, once per side of the UNION.
          for (int side = 0; side < 2; side++) {
            ps.setObject(i++, tenantId);
            if (fromTs != null) ps.setObject(i++, fromTs);
            if (toTs != null) ps.setObject(i++, toTs);
          }
        },
        TenderMixRepository::mapRow,
        "aggregate tender mix");
  }

  private static TenderMixRow mapRow(ResultSet rs) throws SQLException {
    return new TenderMixRow(
        rs.getString("method"),
        rs.getBigDecimal("captured_amount"),
        rs.getLong("captured_count"),
        rs.getBigDecimal("refunded_amount"),
        rs.getLong("refunded_count"),
        rs.getLong("failed_count"),
        // Net and share are filled in by the service: share needs every row's total, which no
        // single row can see.
        null,
        null);
  }
}
