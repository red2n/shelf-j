package com.shelfj.purchase.repo;

import com.shelfj.purchase.domain.Domain;
import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.IntercompanyInvoice;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLineProgress;
import com.shelfj.purchase.domain.Domain.Supplier;
import com.shelfj.purchase.domain.Totals;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
            + "total_net,total_vat,total_gross,expected_delivery,created_at,updated_at,cancelled_at,"
            + "cancelled_reason,closed_at,closed_reason"
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
                + "total_net,total_vat,total_gross,expected_delivery,created_at,updated_at,cancelled_at,"
                + "cancelled_reason,closed_at,closed_reason"
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

  /**
   * Cancels a purchase order and publishes the event in one transaction (golden rule #6).
   *
   * <p>The guard lives in the {@code WHERE} clause rather than in a preceding read, so two
   * concurrent cancels -- or a cancel racing a goods receipt -- cannot both win: whichever commits
   * first moves the row out of the cancellable set and the other sees zero rows updated. A
   * check-then-act in the service layer would leave exactly that window open.
   *
   * @param tenantId the owning tenant
   * @param id the purchase order to cancel
   * @param reason caller-supplied cancellation reason, already validated as non-blank
   * @param event the {@code PurchaseOrderCancelled} outbox row, written in the same transaction
   * @return {@code true} if this call cancelled the order; {@code false} if it was already RECEIVED
   *     or CANCELLED and therefore not cancellable
   */
  public boolean cancelPurchaseOrder(UUID tenantId, UUID id, String reason, OutboxRow event) {
    return inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE purchase_orders SET status='CANCELLED', cancelled_at=now(),"
                      + " cancelled_reason=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status IN ('DRAFT','SUBMITTED')")) {
            ps.setString(1, reason);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            rows = ps.executeUpdate();
          }
          if (rows == 0) return false;
          insertOutbox(c, event);
          return true;
        },
        "cancel purchase order");
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
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getObject("cancelled_at", OffsetDateTime.class) == null
            ? null
            : rs.getObject("cancelled_at", OffsetDateTime.class).toInstant(),
        rs.getString("cancelled_reason"),
        rs.getObject("closed_at", OffsetDateTime.class) == null
            ? null
            : rs.getObject("closed_at", OffsetDateTime.class).toInstant(),
        rs.getString("closed_reason"));
  }

  // ── PO Lines ──────────────────────────────────────────────────────────────────

  /**
   * Appends a line and restates the order's totals from every line it now has, atomically (SJ-D22).
   *
   * <p>The two halves must not be separable. A committed line whose order still shows the old total
   * is a purchase order that understates what it commits — and once spend authority is enforced
   * against that figure, an order could be approved against a total that its own lines contradict.
   *
   * <p>Recomputed from all lines rather than incremented by this one, so the stored figure is a
   * function of the rows rather than of the sequence of calls that produced them. An increment that
   * is missed, applied twice or applied against a since-changed rate drifts silently and for good;
   * a recompute cannot.
   *
   * <p>The arithmetic itself is not done here — it is {@link Totals#of}, a pure function this
   * method calls. Keeping money arithmetic out of the repository is what lets every rounding and
   * VAT case be a unit test rather than a Testcontainers one.
   *
   * @param line the line to append
   * @param currency the order's currency, which fixes the rounding scale
   * @param vatRates VAT code to rate, resolved from pricing-svc by the caller
   * @return the line as stored
   */
  public PurchaseOrderLine addPurchaseOrderLine(
      PurchaseOrderLine line, String currency, java.util.Map<String, BigDecimal> vatRates) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO purchase_order_lines"
                      + " (id,tenant_id,po_id,variant_id,qty,unit_price,vat_code)"
                      + " VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, line.id());
            ps.setObject(2, line.tenantId());
            ps.setObject(3, line.poId());
            ps.setObject(4, line.variantId());
            ps.setBigDecimal(5, line.qty());
            ps.setBigDecimal(6, line.unitPrice());
            ps.setString(7, line.vatCode());
            ps.executeUpdate();
          }
          restateTotals(c, line.tenantId(), line.poId(), currency, vatRates);
          return line;
        },
        "add po line");
  }

  /**
   * Reads every line of the order and writes the three totals back onto it. Runs on the caller's
   * connection so it joins their transaction.
   *
   * @param c the open connection, inside the caller's transaction
   * @param tenantId the owning tenant — first condition of every query (golden rule #3)
   * @param poId the order to restate
   * @param currency the order's currency
   * @param vatRates VAT code to rate
   * @throws SQLException if either statement fails, aborting the caller's transaction
   */
  private void restateTotals(
      Connection c,
      UUID tenantId,
      UUID poId,
      String currency,
      java.util.Map<String, BigDecimal> vatRates)
      throws SQLException {
    List<PurchaseOrderLine> lines = new ArrayList<>();
    try (var ps =
        c.prepareStatement(
            "SELECT id,tenant_id,po_id,variant_id,qty,unit_price,vat_code,created_at"
                + " FROM purchase_order_lines WHERE tenant_id=? AND po_id=?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, poId);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          lines.add(
              new PurchaseOrderLine(
                  rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class),
                  rs.getObject("po_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getBigDecimal("qty"),
                  rs.getBigDecimal("unit_price"),
                  rs.getString("vat_code"),
                  rs.getObject("created_at", OffsetDateTime.class).toInstant()));
        }
      }
    }
    Totals totals = Totals.of(lines, currency, vatRates);
    try (var ps =
        c.prepareStatement(
            "UPDATE purchase_orders SET total_net=?, total_vat=?, total_gross=?, updated_at=now()"
                + " WHERE tenant_id=? AND id=?")) {
      ps.setBigDecimal(1, totals.net());
      ps.setBigDecimal(2, totals.vat());
      ps.setBigDecimal(3, totals.gross());
      ps.setObject(4, tenantId);
      ps.setObject(5, poId);
      ps.executeUpdate();
    }
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

  /**
   * Record a goods receipt and move the purchase order to whichever state the quantities imply.
   *
   * <p><b>The quantities are now read.</b> This used to end {@code SET status='RECEIVED' WHERE
   * status='SUBMITTED'} with no reference to what had actually turned up, so a delivery of 6
   * against an order of 10 closed the order — and the second delivery of the remaining 4 was then
   * refused, because the order was no longer SUBMITTED. A split delivery stranded its own balance.
   *
   * <p>The comparison happens inside this transaction, against a {@code FOR UPDATE} lock on the
   * order, so two lorries arriving at once cannot both read "4 outstanding" and both book it.
   *
   * <p><b>Over-receipt is refused rather than absorbed.</b> Accepting more than was ordered would
   * book stock nobody asked for against a purchase order that cannot account for it, and a mistyped
   * 60 for 6 would do it silently. Whether a tolerance band should be allowed is a procurement
   * policy question — a real one, with a real answer per tenant — and inventing one here would be
   * guessing.
   *
   * <p>If the same Idempotency-Key was already stored for this tenant, the original receipt is
   * returned unchanged (replay), before any quantity is counted.
   */
  public GoodsReceipt createGoodsReceipt(
      GoodsReceipt gr, List<GoodsReceiptLine> lines, OutboxRow event) {
    return inTx(
        c -> {
          if (gr.idempotencyKey() != null) {
            GoodsReceipt existing = findGoodsReceiptByKeyTx(c, gr.tenantId(), gr.idempotencyKey());
            if (existing != null) {
              return existing;
            }
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO goods_receipts (id,tenant_id,po_id,store_id,received_at,"
                      + " idempotency_key) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, gr.id());
            ps.setObject(2, gr.tenantId());
            ps.setObject(3, gr.poId());
            ps.setObject(4, gr.storeId());
            ps.setObject(5, toOdt(gr.receivedAt()));
            ps.setString(6, gr.idempotencyKey());
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
          // Lock the order first: the status decision below reads every receipt against it, and
          // two deliveries arriving together must not both see the same outstanding quantity.
          String status = lockPurchaseOrderStatusTx(c, gr.tenantId(), gr.poId());
          if (status == null) {
            throw ApiException.notFound("PURCHASE_PO_NOT_FOUND", "No such purchase order");
          }
          if (!Domain.PO_SUBMITTED.equals(status) && !Domain.PO_PARTIALLY_RECEIVED.equals(status)) {
            throw ApiException.conflict(
                "PURCHASE_PO_NOT_RECEIVABLE",
                "a purchase order can only be received while SUBMITTED or PARTIALLY_RECEIVED —"
                    + " this one is "
                    + status);
          }

          // Ordered against received, this receipt included. Both sides are already in the
          // schema; nothing read them until now.
          List<PurchaseOrderLineProgress> progress = lineProgressTx(c, gr.tenantId(), gr.poId());
          if (progress.isEmpty()) {
            throw ApiException.unprocessable(
                "PURCHASE_PO_HAS_NO_LINES",
                "a purchase order with no lines has nothing to receive against");
          }
          for (PurchaseOrderLineProgress p : progress) {
            if (p.qtyReceived().compareTo(p.qtyOrdered()) > 0) {
              throw ApiException.unprocessable(
                  "PURCHASE_OVER_RECEIPT",
                  "variant "
                      + p.variantId()
                      + ": received "
                      + p.qtyReceived()
                      + " against an order of "
                      + p.qtyOrdered()
                      + " — amend the purchase order if the extra was genuinely ordered");
            }
          }
          boolean complete = progress.stream().allMatch(p -> p.qtyOutstanding().signum() == 0);
          setPurchaseOrderStatusTx(
              c,
              gr.tenantId(),
              gr.poId(),
              complete ? Domain.PO_RECEIVED : Domain.PO_PARTIALLY_RECEIVED);

          insertOutbox(c, event);
          return gr;
        },
        "create goods receipt");
  }

  /** {@code SELECT ... FOR UPDATE}, so the outstanding-quantity read below is serialised. */
  private static String lockPurchaseOrderStatusTx(java.sql.Connection c, UUID tenantId, UUID poId)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT status FROM purchase_orders WHERE tenant_id=? AND id=? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, poId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString("status") : null;
      }
    }
  }

  private static void setPurchaseOrderStatusTx(
      java.sql.Connection c, UUID tenantId, UUID poId, String status) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "UPDATE purchase_orders SET status=?, updated_at=now() WHERE tenant_id=? AND id=?")) {
      ps.setString(1, status);
      ps.setObject(2, tenantId);
      ps.setObject(3, poId);
      ps.executeUpdate();
    }
  }

  /**
   * Ordered against received per variant, for one purchase order.
   *
   * <p>A LEFT JOIN from the order's own lines, so a variant ordered but never delivered still
   * appears with its full quantity outstanding — an INNER JOIN would have made "nothing arrived"
   * indistinguishable from "nothing was ordered", and the whole point of this query is to notice
   * what is missing.
   */
  private static List<PurchaseOrderLineProgress> lineProgressTx(
      java.sql.Connection c, UUID tenantId, UUID poId) throws SQLException {
    List<PurchaseOrderLineProgress> out = new java.util.ArrayList<>();
    try (var ps =
        c.prepareStatement(
            "SELECT l.variant_id,"
                + "       SUM(l.qty)::numeric(14,3) AS qty_ordered,"
                + "       COALESCE((SELECT SUM(grl.qty_received) FROM goods_receipt_lines grl"
                + "                   JOIN goods_receipts gr ON gr.id = grl.gr_id"
                + "                  WHERE gr.tenant_id = l.tenant_id AND gr.po_id = ?"
                + "                    AND grl.variant_id = l.variant_id), 0)::numeric(14,3)"
                + "         AS qty_received"
                + "  FROM purchase_order_lines l"
                + " WHERE l.tenant_id = ? AND l.po_id = ?"
                + " GROUP BY l.tenant_id, l.variant_id")) {
      ps.setObject(1, poId);
      ps.setObject(2, tenantId);
      ps.setObject(3, poId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BigDecimal ordered = rs.getBigDecimal("qty_ordered");
          BigDecimal received = rs.getBigDecimal("qty_received");
          out.add(
              new PurchaseOrderLineProgress(
                  rs.getObject("variant_id", UUID.class),
                  ordered,
                  received,
                  ordered.subtract(received).max(BigDecimal.ZERO)));
        }
      }
    }
    return out;
  }

  /** The same progress view, for a caller asking what is still outstanding on an order. */
  public List<PurchaseOrderLineProgress> findLineProgress(UUID tenantId, UUID poId) {
    return inTx(c -> lineProgressTx(c, tenantId, poId), "read purchase order progress");
  }

  /**
   * Short-closes a partially received order: the balance is never coming and we have stopped
   * waiting.
   *
   * <p>Only from PARTIALLY_RECEIVED. A SUBMITTED order with nothing delivered is a cancellation
   * (SJ-D3), and a RECEIVED one has nothing outstanding to close. Guarded in the {@code WHERE} so a
   * close racing a final delivery cannot both win — whichever commits second finds no row.
   */
  public boolean closePurchaseOrderShort(UUID tenantId, UUID poId, String reason) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE purchase_orders SET status='CLOSED', closed_at=now(),"
                      + " closed_reason=?, updated_at=now()"
                      + " WHERE tenant_id=? AND id=? AND status='PARTIALLY_RECEIVED'")) {
            ps.setString(1, reason);
            ps.setObject(2, tenantId);
            ps.setObject(3, poId);
            return ps.executeUpdate() > 0;
          }
        },
        "close purchase order short");
  }

  private GoodsReceipt findGoodsReceiptByKeyTx(
      java.sql.Connection c, UUID tenantId, String idempotencyKey) throws SQLException {
    try (var ps =
        c.prepareStatement(
            "SELECT id,tenant_id,po_id,store_id,received_at,created_at,idempotency_key"
                + " FROM goods_receipts WHERE tenant_id=? AND idempotency_key=?")) {
      ps.setObject(1, tenantId);
      ps.setString(2, idempotencyKey);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? mapGoodsReceipt(rs) : null;
      }
    }
  }

  private static GoodsReceipt mapGoodsReceipt(ResultSet rs) throws SQLException {
    return new GoodsReceipt(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("po_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getString("idempotency_key"));
  }

  public List<GoodsReceipt> findGoodsReceiptsByPo(UUID tenantId, UUID poId) {
    return query(
        "SELECT id,tenant_id,po_id,store_id,received_at,created_at,idempotency_key"
            + " FROM goods_receipts WHERE tenant_id=? AND po_id=? ORDER BY received_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, poId);
        },
        PurchaseRepository::mapGoodsReceipt,
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

  /**
   * Keyset page of the nominal ledger, ordered by {@code (entry_date, created_at, id)} ascending
   * (chronological journal order). {@code from}/{@code to} were previously the only bound, and both
   * are optional — a caller omitting them (a legitimate "since inception" trial-balance query) got
   * a fully unbounded scan of the ledger. Now paginated instead of relying on the date filter
   * alone.
   */
  public List<NominalLedgerEntry> findNominalLedger(
      UUID tenantId,
      String nominalCode,
      LocalDate from,
      LocalDate to,
      LocalDate afterEntryDate,
      Instant afterCreatedAt,
      UUID afterId,
      int limit) {
    boolean hasCursor = afterEntryDate != null && afterCreatedAt != null && afterId != null;
    return query(
        "SELECT id,tenant_id,entry_date,nominal_code,nominal_name,debit,credit,"
            + "description,source_ref,created_at"
            + " FROM nominal_ledger_entries"
            + " WHERE tenant_id=?"
            + (nominalCode != null ? " AND nominal_code=?" : "")
            + (from != null ? " AND entry_date >= ?" : "")
            + (to != null ? " AND entry_date <= ?" : "")
            + (hasCursor ? " AND (entry_date, created_at, id) > (?, ?, ?)" : "")
            + " ORDER BY entry_date, created_at, id LIMIT ?",
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (nominalCode != null) ps.setString(i++, nominalCode);
          if (from != null) ps.setObject(i++, from);
          if (to != null) ps.setObject(i++, to);
          if (hasCursor) {
            ps.setObject(i++, afterEntryDate);
            ps.setObject(i++, afterCreatedAt.atOffset(ZoneOffset.UTC));
            ps.setObject(i++, afterId);
          }
          ps.setInt(i, limit);
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
