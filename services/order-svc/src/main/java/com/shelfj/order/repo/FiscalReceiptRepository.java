package com.shelfj.order.repo;

import com.shelfj.order.domain.Domain;
import com.shelfj.order.domain.Domain.FiscalReceipt;
import com.shelfj.order.domain.Domain.SequenceGap;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
          // The chain (18.4): the previous document's hash, or GENESIS for the first — and for
          // the first after a document issued before the chain existed. The counter row is still
          // locked, so the previous document is committed and nothing can slip between.
          String prevHash = Domain.FiscalReceipt.GENESIS;
          try (var prev =
              c.prepareStatement(
                  "SELECT hash FROM fiscal_receipts WHERE tenant_id = ? AND store_id = ?"
                      + " AND series_code = ? AND period = ? AND number = ?")) {
            prev.setObject(1, draft.tenantId());
            prev.setObject(2, draft.storeId());
            prev.setString(3, draft.seriesCode());
            prev.setString(4, draft.period());
            prev.setLong(5, number - 1);
            try (ResultSet rs = prev.executeQuery()) {
              if (rs.next() && rs.getString(1) != null) {
                prevHash = rs.getString(1);
              }
            }
          }
          // Issued-at is part of the hash, so it is chosen here rather than by the database, at
          // the microsecond precision the column keeps.
          Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          FiscalReceipt chained =
              new FiscalReceipt(
                  draft.id(),
                  draft.tenantId(),
                  draft.storeId(),
                  draft.seriesCode(),
                  draft.period(),
                  number,
                  full,
                  draft.orderId(),
                  issuedAt,
                  draft.issuedBy(),
                  draft.currency(),
                  draft.grossTotal(),
                  draft.taxTotal(),
                  null,
                  null,
                  prevHash,
                  null);
          String hash = hashOf(chained);
          try (var ins =
              c.prepareStatement(
                  "INSERT INTO fiscal_receipts"
                      + " (id, tenant_id, store_id, series_code, period, number, full_number,"
                      + "  order_id, issued_at, issued_by, currency, gross_total, tax_total,"
                      + "  prev_hash, hash)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setObject(1, draft.id());
            ins.setObject(2, draft.tenantId());
            ins.setObject(3, draft.storeId());
            ins.setString(4, draft.seriesCode());
            ins.setString(5, draft.period());
            ins.setLong(6, number);
            ins.setString(7, full);
            ins.setObject(8, draft.orderId());
            ins.setObject(9, issuedAt.atOffset(java.time.ZoneOffset.UTC));
            ins.setObject(10, draft.issuedBy());
            ins.setString(11, draft.currency());
            ins.setBigDecimal(12, draft.grossTotal());
            ins.setBigDecimal(13, draft.taxTotal());
            ins.setString(14, prevHash);
            ins.setString(15, hash);
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
                + " issued_at, issued_by, currency, gross_total, tax_total, voided_at, void_reason,"
                + " prev_hash, hash"
                + " FROM fiscal_receipts WHERE tenant_id = ? AND order_id = ?")) {
      st.setObject(1, tenantId);
      st.setObject(2, orderId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  /**
   * The receipt issued for one sale.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param orderId the sale whose receipt to read
   * @return the receipt, or empty when none has been issued yet
   */
  public Optional<FiscalReceipt> findByOrder(UUID tenantId, UUID orderId) {
    var rows =
        query(
            "SELECT id, tenant_id, store_id, series_code, period, number, full_number, order_id,"
                + " issued_at, issued_by, currency, gross_total, tax_total, voided_at, void_reason,"
                + " prev_hash, hash"
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

  /**
   * Every receipt in one series, by number — the register a store keeps.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store whose register to read
   * @param series the numbering series
   * @param period the fiscal period, normally the year
   * @param limit maximum rows
   * @return the receipts in number order
   */
  public List<FiscalReceipt> listSeries(
      UUID tenantId, UUID storeId, String series, String period, int limit) {
    return query(
        "SELECT id, tenant_id, store_id, series_code, period, number, full_number, order_id,"
            + " issued_at, issued_by, currency, gross_total, tax_total, voided_at, void_reason,"
            + " prev_hash, hash"
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
  /** One counter row: the series a store runs, where it has got to, and what it prints in front. */
  public record ReceiptSeries(
      UUID storeId, String seriesCode, String period, long nextNumber, String prefix) {}

  /**
   * Every series a store has opened, oldest first.
   *
   * @param tenantId owning tenant; the first condition
   * @param storeId the store
   * @return the counters
   */
  public List<ReceiptSeries> listSeriesConfig(UUID tenantId, UUID storeId) {
    return query(
        "SELECT store_id, series_code, period, next_number, prefix FROM receipt_series"
            + " WHERE tenant_id = ? AND store_id = ? ORDER BY period DESC, series_code",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        rs ->
            new ReceiptSeries(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getString(5)),
        "list receipt series config");
  }

  /**
   * Opens a series if it does not exist and sets the prefix it prints. The counter is never touched
   * here: a prefix change affects the documents issued after it, and every document already issued
   * keeps the full number it was printed with.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param series the series code
   * @param period the fiscal period
   * @param prefix what is printed in front of the number, or {@code null} for nothing
   * @return the counter as it now stands
   */
  public ReceiptSeries setSeriesPrefix(
      UUID tenantId, UUID storeId, String series, String period, String prefix) {
    exec(
        "INSERT INTO receipt_series (tenant_id, store_id, series_code, period, next_number, prefix)"
            + " VALUES (?,?,?,?,1,?)"
            + " ON CONFLICT (tenant_id, store_id, series_code, period)"
            + " DO UPDATE SET prefix = EXCLUDED.prefix",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
          ps.setString(5, prefix);
        },
        "set receipt series prefix");
    return listSeriesConfig(tenantId, storeId).stream()
        .filter(r -> r.seriesCode().equals(series) && r.period().equals(period))
        .findFirst()
        .orElseThrow();
  }

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
        rs.getString(15),
        rs.getString(16),
        rs.getString(17));
  }

  /**
   * The SHA-256 a document must carry: over the figures an inspector reads off it and the hash of
   * the document before, so a changed figure or a re-inserted row no longer matches (18.4).
   *
   * @param r the document, with the previous hash it chains to
   * @return 64 hex characters
   */
  public static String hashOf(FiscalReceipt r) {
    String canonical =
        String.join(
            "|",
            r.tenantId().toString(),
            r.storeId().toString(),
            r.seriesCode(),
            r.period(),
            Long.toString(r.number()),
            r.fullNumber(),
            r.orderId().toString(),
            r.issuedAt().toString(),
            r.currency(),
            r.grossTotal().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
            r.taxTotal().setScale(4, java.math.RoundingMode.HALF_UP).toPlainString(),
            r.prevHash());
    try {
      var md = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 missing", e);
    }
  }

  /** What the audit says about the chain: intact or not, from where, and where it breaks. */
  public record ChainVerdict(Boolean intact, Long from, Long brokenAt) {}

  /**
   * Walks a series in number order and re-derives every hash (18.4). Documents from before the
   * chain (null hash) are passed over; the first chained document is not held to a particular
   * previous hash, every later one must name its predecessor's, and each must equal the hash of its
   * own stored figures.
   *
   * @return intact null when nothing in the series is chained yet
   */
  public ChainVerdict verifyChain(UUID tenantId, UUID storeId, String series, String period) {
    Long from = null;
    String prev = null;
    for (FiscalReceipt r : listSeries(tenantId, storeId, series, period, 1_000_000)) {
      if (r.hash() == null) {
        if (from != null) {
          return new ChainVerdict(false, from, r.number());
        }
        continue;
      }
      boolean linked = prev == null || prev.equals(r.prevHash());
      if (!linked || r.prevHash() == null || !hashOf(r).equals(r.hash())) {
        return new ChainVerdict(false, from == null ? Long.valueOf(r.number()) : from, r.number());
      }
      if (from == null) {
        from = r.number();
      }
      prev = r.hash();
    }
    return new ChainVerdict(from == null ? null : Boolean.TRUE, from, null);
  }

  /** One order line under the document it was sold on, for the register export. */
  public record RegisterLine(
      long number, UUID variantId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}

  /**
   * Every order line behind every document in a series, in document order — the same service's
   * tables, joined here rather than fetched per document.
   */
  public List<RegisterLine> linesInSeries(
      UUID tenantId, UUID storeId, String series, String period) {
    return query(
        "SELECT fr.number, oi.variant_id, oi.qty, oi.unit_price, oi.line_total"
            + " FROM fiscal_receipts fr"
            + " JOIN order_items oi ON oi.tenant_id = fr.tenant_id AND oi.order_id = fr.order_id"
            + " WHERE fr.tenant_id = ? AND fr.store_id = ? AND fr.series_code = ? AND fr.period = ?"
            + " ORDER BY fr.number, oi.created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
          ps.setString(3, series);
          ps.setString(4, period);
        },
        rs ->
            new RegisterLine(
                rs.getLong(1),
                (UUID) rs.getObject(2),
                rs.getBigDecimal(3),
                rs.getBigDecimal(4),
                rs.getBigDecimal(5)),
        "register lines");
  }
}
