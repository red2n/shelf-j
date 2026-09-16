package com.shelfj.order.repo;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.SalesInvoices;
import com.shelfj.order.domain.SalesInvoices.Pending;
import com.shelfj.order.domain.SalesInvoices.SalesInvoice;
import com.shelfj.order.domain.SalesInvoices.Written;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Invoices and credit notes to business buyers (18.9): numbered gaplessly per series and year, one
 * invoice per sale and one credit note per return, kept exactly as issued.
 */
@ApplicationScoped
public class SalesInvoiceRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, store_id, order_id, return_id, type_code, series_code, period, number,"
          + " full_number, issue_date, issued_at, issued_by, customer_id, buyer_name, buyer_vat_id,"
          + " currency, net_amount, vat_amount, payable_amount, preceding_invoice_id,"
          + " customization_id, document, irp_payload, irp_problems";

  private static final String BY_ORDER =
      "SELECT "
          + COLUMNS
          + " FROM sales_invoices WHERE tenant_id = ? AND order_id = ? AND type_code = '380'";

  private static final String BY_RETURN =
      "SELECT " + COLUMNS + " FROM sales_invoices WHERE tenant_id = ? AND return_id = ?";

  private static final String BY_ID =
      "SELECT " + COLUMNS + " FROM sales_invoices WHERE tenant_id = ? AND id = ?";

  /** A return, as far as a credit note needs it. */
  public record ReturnRef(UUID id, UUID orderId, UUID storeId) {}

  /**
   * Issues a document with the next number in its series, or returns the one already issued for the
   * sale or the return.
   *
   * <p>The series row is locked before anything is looked at, so two requests for one sale wait on
   * each other and the second finds the first's document rather than taking a number of its own.
   * The document is written by {@code writer} once its number is known, inside the same
   * transaction: a writer that refuses rolls the number back.
   *
   * @param writer from the full number to the written document
   */
  public SalesInvoice issue(Pending p, Function<String, Written> writer) {
    return inTx(
        c -> {
          try (PreparedStatement open =
              c.prepareStatement(
                  "INSERT INTO sales_invoice_series (tenant_id, series_code, period, next_number)"
                      + " VALUES (?,?,?,1) ON CONFLICT DO NOTHING")) {
            open.setObject(1, p.tenantId());
            open.setString(2, p.seriesCode());
            open.setString(3, p.period());
            open.executeUpdate();
          }
          try (PreparedStatement lock =
              c.prepareStatement(
                  "SELECT next_number FROM sales_invoice_series"
                      + " WHERE tenant_id = ? AND series_code = ? AND period = ? FOR UPDATE")) {
            lock.setObject(1, p.tenantId());
            lock.setString(2, p.seriesCode());
            lock.setString(3, p.period());
            try (ResultSet rs = lock.executeQuery()) {
              if (!rs.next()) throw new SQLException("sales invoice series vanished");
            }
          }
          Optional<SalesInvoice> existing =
              p.returnId() == null
                  ? one(c, BY_ORDER, p.tenantId(), p.orderId())
                  : one(c, BY_RETURN, p.tenantId(), p.returnId());
          if (existing.isPresent()) return existing.get();

          long number;
          try (PreparedStatement take =
              c.prepareStatement(
                  "UPDATE sales_invoice_series SET next_number = next_number + 1"
                      + " WHERE tenant_id = ? AND series_code = ? AND period = ?"
                      + " RETURNING next_number - 1")) {
            take.setObject(1, p.tenantId());
            take.setString(2, p.seriesCode());
            take.setString(3, p.period());
            try (ResultSet rs = take.executeQuery()) {
              if (!rs.next()) throw new SQLException("sales invoice series vanished");
              number = rs.getLong(1);
            }
          }
          String full = SalesInvoices.fullNumber(p.seriesCode(), p.period(), number);
          Written w;
          try {
            w = writer.apply(full);
          } catch (RuntimeException e) {
            // Whatever stopped the writer, the number goes back: a transaction left open when
            // autocommit is restored would be committed, and the counter with it.
            c.rollback();
            throw e;
          }
          UUID id = Ids.newId();
          Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
          try (PreparedStatement ins =
              c.prepareStatement(
                  "INSERT INTO sales_invoices ("
                      + COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)")) {
            int i = 1;
            ins.setObject(i++, id);
            ins.setObject(i++, p.tenantId());
            ins.setObject(i++, p.storeId());
            ins.setObject(i++, p.orderId());
            ins.setObject(i++, p.returnId());
            ins.setString(i++, p.typeCode());
            ins.setString(i++, p.seriesCode());
            ins.setString(i++, p.period());
            ins.setLong(i++, number);
            ins.setString(i++, full);
            ins.setDate(i++, Date.valueOf(p.issueDate()));
            ins.setObject(i++, issuedAt.atOffset(ZoneOffset.UTC));
            ins.setObject(i++, p.issuedBy());
            ins.setObject(i++, p.customerId());
            ins.setString(i++, p.buyerName());
            ins.setString(i++, p.buyerVatId());
            ins.setString(i++, p.currency());
            ins.setBigDecimal(i++, w.netAmount());
            ins.setBigDecimal(i++, w.vatAmount());
            ins.setBigDecimal(i++, w.payableAmount());
            ins.setObject(i++, p.precedingInvoiceId());
            ins.setString(i++, w.customizationId());
            ins.setString(i++, w.document());
            ins.setString(i++, w.irpPayload());
            ins.setString(i++, w.irpProblems());
            ins.executeUpdate();
          }
          return one(c, BY_ID, p.tenantId(), id).orElseThrow();
        },
        "issue sales invoice");
  }

  /** The invoice issued for a sale. */
  public Optional<SalesInvoice> findInvoice(UUID tenantId, UUID orderId) {
    return first(
        query(
            BY_ORDER,
            bind(tenantId, orderId),
            SalesInvoiceRepository::map,
            "find sales invoice by order"));
  }

  /** The credit note issued for a return. */
  public Optional<SalesInvoice> findCreditNote(UUID tenantId, UUID returnId) {
    return first(
        query(
            BY_RETURN,
            bind(tenantId, returnId),
            SalesInvoiceRepository::map,
            "find credit note by return"));
  }

  /** One document. */
  public Optional<SalesInvoice> find(UUID tenantId, UUID id) {
    return first(
        query(BY_ID, bind(tenantId, id), SalesInvoiceRepository::map, "find sales invoice"));
  }

  /**
   * Documents newest first, a page at a time.
   *
   * @param orderId only this sale's, or null for all
   * @param after the last document of the previous page, or null for the first page
   */
  public List<SalesInvoice> list(UUID tenantId, UUID orderId, UUID after, int limit) {
    return query(
        "SELECT "
            + COLUMNS
            + " FROM sales_invoices s WHERE s.tenant_id = ?"
            + " AND (?::uuid IS NULL OR s.order_id = ?)"
            + " AND (?::uuid IS NULL OR (s.issued_at, s.id) < (SELECT p.issued_at, p.id"
            + " FROM sales_invoices p WHERE p.tenant_id = ? AND p.id = ?))"
            + " ORDER BY s.issued_at DESC, s.id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, orderId);
          ps.setObject(3, orderId);
          ps.setObject(4, after);
          ps.setObject(5, tenantId);
          ps.setObject(6, after);
          ps.setInt(7, limit);
        },
        SalesInvoiceRepository::map,
        "list sales invoices");
  }

  /** A return, for the credit note that closes it. */
  public Optional<ReturnRef> findReturn(UUID tenantId, UUID returnId) {
    return first(
        query(
            "SELECT id, order_id, store_id FROM returns WHERE tenant_id = ? AND id = ?",
            bind(tenantId, returnId),
            rs ->
                new ReturnRef(
                    rs.getObject("id", UUID.class),
                    rs.getObject("order_id", UUID.class),
                    rs.getObject("store_id", UUID.class)),
            "find return"));
  }

  private static Binder bind(UUID tenantId, UUID key) {
    return ps -> {
      ps.setObject(1, tenantId);
      ps.setObject(2, key);
    };
  }

  private static <T> Optional<T> first(List<T> rows) {
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static Optional<SalesInvoice> one(Connection c, String sql, UUID tenantId, UUID key)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  private static SalesInvoice map(ResultSet rs) throws SQLException {
    return new SalesInvoice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getObject("return_id", UUID.class),
        rs.getString("type_code"),
        rs.getString("series_code"),
        rs.getString("period"),
        rs.getLong("number"),
        rs.getString("full_number"),
        rs.getDate("issue_date").toLocalDate(),
        rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
        rs.getObject("issued_by", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("buyer_name"),
        rs.getString("buyer_vat_id"),
        rs.getString("currency"),
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("payable_amount"),
        rs.getObject("preceding_invoice_id", UUID.class),
        rs.getString("customization_id"),
        rs.getString("document"),
        rs.getString("irp_payload"),
        rs.getString("irp_problems"));
  }
}
