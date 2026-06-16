package com.shelfj.purchase.repo;

import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.IntercompanyInvoice;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.Domain.Supplier;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC persistence for purchase-svc. Every tenant query filters by tenant_id first. */
@ApplicationScoped
public class PurchaseRepository extends BaseOutboxRepository {

  // ── Suppliers ─────────────────────────────────────────────────────────────────

  public Supplier createSupplier(Supplier s) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO suppliers"
                      + " (id,tenant_id,name,vat_number,vat_registered,country_code,currency,payment_terms_days)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setString(3, s.name());
            ps.setString(4, s.vatNumber());
            ps.setBoolean(5, s.vatRegistered());
            ps.setString(6, s.countryCode());
            ps.setString(7, s.currency());
            ps.setInt(8, s.paymentTermsDays());
            ps.executeUpdate();
          } catch (java.sql.SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState()))
              throw new ApiException(
                  409,
                  "PURCHASE_SUPPLIER_DUPLICATE",
                  "Supplier with that name already exists for this tenant",
                  List.of(),
                  sqle);
            throw sqle;
          }
          return s;
        },
        "create supplier");
  }

  public List<Supplier> findSuppliers(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,name,vat_number,vat_registered,country_code,currency,"
            + "payment_terms_days,created_at,updated_at"
            + " FROM suppliers WHERE tenant_id=? ORDER BY name LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        this::mapSupplier,
        "find suppliers");
  }

  public Optional<Supplier> findSupplier(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id,tenant_id,name,vat_number,vat_registered,country_code,currency,"
                + "payment_terms_days,created_at,updated_at"
                + " FROM suppliers WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapSupplier,
            "find supplier");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private Supplier mapSupplier(ResultSet rs) throws SQLException {
    return new Supplier(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("vat_number"),
        rs.getBoolean("vat_registered"),
        rs.getString("country_code"),
        rs.getString("currency"),
        rs.getInt("payment_terms_days"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // ── Purchase Orders ───────────────────────────────────────────────────────────

  public PurchaseOrder createPurchaseOrder(PurchaseOrder po, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO purchase_orders"
                      + " (id,tenant_id,supplier_id,store_id,status,currency,"
                      + "  total_net,total_vat,total_gross,expected_delivery)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, po.id());
            ps.setObject(2, po.tenantId());
            ps.setObject(3, po.supplierId());
            ps.setObject(4, po.storeId());
            ps.setString(5, po.status());
            ps.setString(6, po.currency());
            ps.setBigDecimal(7, po.totalNet());
            ps.setBigDecimal(8, po.totalVat());
            ps.setBigDecimal(9, po.totalGross());
            ps.setObject(10, po.expectedDelivery());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return po;
        },
        "create purchase order");
  }

  public List<PurchaseOrder> findPurchaseOrders(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,supplier_id,store_id,status,currency,"
            + "total_net,total_vat,total_gross,expected_delivery,created_at,updated_at"
            + " FROM purchase_orders WHERE tenant_id=? ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        this::mapPurchaseOrder,
        "find purchase orders");
  }

  public Optional<PurchaseOrder> findPurchaseOrder(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id,tenant_id,supplier_id,store_id,status,currency,"
                + "total_net,total_vat,total_gross,expected_delivery,created_at,updated_at"
                + " FROM purchase_orders WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapPurchaseOrder,
            "find purchase order");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  public void updatePurchaseOrderStatus(UUID tenantId, UUID id, String status) {
    exec(
        "UPDATE purchase_orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "update po status");
  }

  private PurchaseOrder mapPurchaseOrder(ResultSet rs) throws SQLException {
    return new PurchaseOrder(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getString("currency"),
        rs.getBigDecimal("total_net"),
        rs.getBigDecimal("total_vat"),
        rs.getBigDecimal("total_gross"),
        rs.getObject("expected_delivery", LocalDate.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  // ── PO Lines ──────────────────────────────────────────────────────────────────

  public PurchaseOrderLine addPurchaseOrderLine(PurchaseOrderLine line) {
    exec(
        "INSERT INTO purchase_order_lines"
            + " (id,tenant_id,po_id,variant_id,qty,unit_price,vat_code)"
            + " VALUES (?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, line.id());
          ps.setObject(2, line.tenantId());
          ps.setObject(3, line.poId());
          ps.setObject(4, line.variantId());
          ps.setBigDecimal(5, line.qty());
          ps.setBigDecimal(6, line.unitPrice());
          ps.setString(7, line.vatCode());
        },
        "add po line");
    return line;
  }

  public List<PurchaseOrderLine> findPurchaseOrderLines(UUID tenantId, UUID poId) {
    return query(
        "SELECT id,tenant_id,po_id,variant_id,qty,unit_price,vat_code,created_at"
            + " FROM purchase_order_lines WHERE tenant_id=? AND po_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        rs ->
            new PurchaseOrderLine(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("po_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty"),
                rs.getBigDecimal("unit_price"),
                rs.getString("vat_code"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        "find po lines");
  }

  // ── Goods Receipts ────────────────────────────────────────────────────────────

  public GoodsReceipt createGoodsReceipt(
      GoodsReceipt gr, List<GoodsReceiptLine> lines, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO goods_receipts (id,tenant_id,po_id,store_id,received_at)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, gr.id());
            ps.setObject(2, gr.tenantId());
            ps.setObject(3, gr.poId());
            ps.setObject(4, gr.storeId());
            ps.setObject(5, toOdt(gr.receivedAt()));
            ps.executeUpdate();
          }
          for (GoodsReceiptLine l : lines) {
            try (var ps =
                c.prepareStatement(
                    "INSERT INTO goods_receipt_lines (id,tenant_id,gr_id,variant_id,qty_received)"
                        + " VALUES (?,?,?,?,?)")) {
              ps.setObject(1, l.id());
              ps.setObject(2, l.tenantId());
              ps.setObject(3, l.grId());
              ps.setObject(4, l.variantId());
              ps.setBigDecimal(5, l.qtyReceived());
              ps.executeUpdate();
            }
          }
          // Update PO status to RECEIVED
          try (var ps =
              c.prepareStatement(
                  "UPDATE purchase_orders SET status='RECEIVED', updated_at=now()"
                      + " WHERE tenant_id=? AND id=?")) {
            ps.setObject(1, gr.tenantId());
            ps.setObject(2, gr.poId());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return gr;
        },
        "create goods receipt");
  }

  public List<GoodsReceipt> findGoodsReceiptsByPo(UUID tenantId, UUID poId) {
    return query(
        "SELECT id,tenant_id,po_id,store_id,received_at,created_at"
            + " FROM goods_receipts WHERE tenant_id=? AND po_id=? ORDER BY received_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        rs ->
            new GoodsReceipt(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("po_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("received_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        "find grns by po");
  }

  public List<GoodsReceiptLine> findGoodsReceiptLines(UUID tenantId, UUID grId) {
    return query(
        "SELECT id,tenant_id,gr_id,variant_id,qty_received,created_at"
            + " FROM goods_receipt_lines WHERE tenant_id=? AND gr_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, grId);
        },
        rs ->
            new GoodsReceiptLine(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("gr_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("qty_received"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        "find grn lines");
  }

  // ── Intercompany Invoices ─────────────────────────────────────────────────────

  public IntercompanyInvoice createIntercompanyInvoice(
      IntercompanyInvoice inv, List<NominalLedgerEntry> ledgerEntries, OutboxRow event) {
    return inTx(
        c -> {
          insertIntercompanyInvoice(c, inv);
          for (NominalLedgerEntry e : ledgerEntries) {
            insertNominalEntry(c, e);
          }
          insertOutbox(c, event);
          return inv;
        },
        "create intercompany invoice");
  }

  /** Creates an AR invoice and its matching AP invoice atomically (double-entry integrity). */
  public List<IntercompanyInvoice> createIntercompanyInvoicePair(
      IntercompanyInvoice ar,
      List<NominalLedgerEntry> arEntries,
      OutboxRow arEvent,
      IntercompanyInvoice ap,
      List<NominalLedgerEntry> apEntries,
      OutboxRow apEvent) {
    return inTx(
        c -> {
          for (IntercompanyInvoice inv : new IntercompanyInvoice[] {ar, ap}) {
            insertIntercompanyInvoice(c, inv);
          }
          for (NominalLedgerEntry e : arEntries) insertNominalEntry(c, e);
          for (NominalLedgerEntry e : apEntries) insertNominalEntry(c, e);
          insertOutbox(c, arEvent);
          insertOutbox(c, apEvent);
          return List.of(ar, ap);
        },
        "create intercompany invoice pair");
  }

  public void settleIntercompanyInvoice(
      UUID tenantId, UUID id, List<NominalLedgerEntry> settlementEntries) {
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE intercompany_invoices SET status='SETTLED'"
                      + " WHERE tenant_id=? AND id=? AND status='RAISED'"
                      + " RETURNING id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            var rs = ps.executeQuery();
            if (!rs.next())
              throw new ApiException(
                  409,
                  "PURCHASE_INVOICE_NOT_RAISEABLE",
                  "Invoice already settled or not found",
                  List.of());
          }
          for (NominalLedgerEntry e : settlementEntries) {
            insertNominalEntry(c, e);
          }
          return null;
        },
        "settle intercompany invoice");
  }

  private void insertIntercompanyInvoice(java.sql.Connection c, IntercompanyInvoice inv)
      throws java.sql.SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO intercompany_invoices"
                + " (id,tenant_id,invoice_type,from_store_id,to_store_id,transfer_ref,"
                + "  net_amount,vat_amount,gross_amount,vat_code,vat_disregarded,"
                + "  status,invoice_date,payment_due_date,currency)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, inv.id());
      ps.setObject(2, inv.tenantId());
      ps.setString(3, inv.invoiceType());
      ps.setObject(4, inv.fromStoreId());
      ps.setObject(5, inv.toStoreId());
      ps.setObject(6, inv.transferRef());
      ps.setBigDecimal(7, inv.netAmount());
      ps.setBigDecimal(8, inv.vatAmount());
      ps.setBigDecimal(9, inv.grossAmount());
      ps.setString(10, inv.vatCode());
      ps.setBoolean(11, inv.vatDisregarded());
      ps.setString(12, inv.status());
      ps.setObject(13, inv.invoiceDate());
      ps.setObject(14, inv.paymentDueDate());
      ps.setString(15, inv.currency());
      ps.executeUpdate();
    }
  }

  private void insertNominalEntry(java.sql.Connection c, NominalLedgerEntry e)
      throws java.sql.SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO nominal_ledger_entries"
                + " (id,tenant_id,entry_date,nominal_code,nominal_name,debit,credit,description,source_ref)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, e.id());
      ps.setObject(2, e.tenantId());
      ps.setObject(3, e.entryDate());
      ps.setString(4, e.nominalCode());
      ps.setString(5, e.nominalName());
      ps.setBigDecimal(6, e.debit());
      ps.setBigDecimal(7, e.credit());
      ps.setString(8, e.description());
      ps.setObject(9, e.sourceRef());
      ps.executeUpdate();
    }
  }

  public List<IntercompanyInvoice> findIntercompanyInvoices(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,invoice_type,from_store_id,to_store_id,transfer_ref,"
            + "net_amount,vat_amount,gross_amount,vat_code,vat_disregarded,"
            + "status,invoice_date,payment_due_date,currency,created_at"
            + " FROM intercompany_invoices WHERE tenant_id=? ORDER BY created_at DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        this::mapInvoice,
        "find intercompany invoices");
  }

  public Optional<IntercompanyInvoice> findIntercompanyInvoice(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id,tenant_id,invoice_type,from_store_id,to_store_id,transfer_ref,"
                + "net_amount,vat_amount,gross_amount,vat_code,vat_disregarded,"
                + "status,invoice_date,payment_due_date,currency,created_at"
                + " FROM intercompany_invoices WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            this::mapInvoice,
            "find intercompany invoice");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private IntercompanyInvoice mapInvoice(ResultSet rs) throws SQLException {
    UUID transferRef = rs.getObject("transfer_ref", UUID.class);
    return new IntercompanyInvoice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("invoice_type"),
        rs.getObject("from_store_id", UUID.class),
        rs.getObject("to_store_id", UUID.class),
        transferRef,
        rs.getBigDecimal("net_amount"),
        rs.getBigDecimal("vat_amount"),
        rs.getBigDecimal("gross_amount"),
        rs.getString("vat_code"),
        rs.getBoolean("vat_disregarded"),
        rs.getString("status"),
        rs.getObject("invoice_date", LocalDate.class),
        rs.getObject("payment_due_date", LocalDate.class),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Nominal Ledger ────────────────────────────────────────────────────────────

  public List<NominalLedgerEntry> findNominalLedger(
      UUID tenantId, String nominalCode, LocalDate from, LocalDate to) {
    return query(
        "SELECT id,tenant_id,entry_date,nominal_code,nominal_name,debit,credit,"
            + "description,source_ref,created_at"
            + " FROM nominal_ledger_entries"
            + " WHERE tenant_id=?"
            + (nominalCode != null ? " AND nominal_code=?" : "")
            + (from != null ? " AND entry_date >= ?" : "")
            + (to != null ? " AND entry_date <= ?" : "")
            + " ORDER BY entry_date, created_at",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (nominalCode != null) ps.setString(i++, nominalCode);
          if (from != null) ps.setObject(i++, from);
          if (to != null) ps.setObject(i, to);
        },
        this::mapNominalEntry,
        "find nominal ledger");
  }

  private NominalLedgerEntry mapNominalEntry(ResultSet rs) throws SQLException {
    UUID sourceRef = rs.getObject("source_ref", UUID.class);
    return new NominalLedgerEntry(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("entry_date", LocalDate.class),
        rs.getString("nominal_code"),
        rs.getString("nominal_name"),
        rs.getBigDecimal("debit"),
        rs.getBigDecimal("credit"),
        rs.getString("description"),
        sourceRef,
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private static OffsetDateTime toOdt(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
