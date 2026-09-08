package com.shelfj.purchase.service;

import com.shelfj.purchase.domain.Domain;
import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.IntercompanyInvoice;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.Domain.Supplier;
import com.shelfj.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.shelfj.purchase.dto.Dtos.CancelPurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.CreateGoodsReceiptRequest;
import com.shelfj.purchase.dto.Dtos.CreatePurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.CreateSupplierRequest;
import com.shelfj.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.shelfj.purchase.repo.PurchaseRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Business logic for purchase-svc. No HTTP types here. */
@ApplicationScoped
public class PurchaseService {

  // BACS standard payment terms: 30 days per UK Finance / HMRC guidance
  private static final int BACS_TERMS_DAYS = 30;

  @Inject PurchaseRepository repo;

  // ── Suppliers ─────────────────────────────────────────────────────────────────

  public Supplier createSupplier(CreateSupplierRequest req, TenantContext ctx) {
    Supplier s =
        new Supplier(
            UUID.randomUUID(),
            ctx.requireTenantId(),
            req.name(),
            req.vatNumber(),
            req.vatRegistered(),
            req.countryCode() != null ? req.countryCode().toUpperCase(java.util.Locale.ROOT) : "GB",
            req.currency() != null ? req.currency().toUpperCase(java.util.Locale.ROOT) : "GBP",
            req.paymentTermsDays() != null ? req.paymentTermsDays() : BACS_TERMS_DAYS,
            Instant.now(),
            Instant.now());
    return repo.createSupplier(s);
  }

  public List<Supplier> listSuppliers(TenantContext ctx, int limit) {
    return repo.findSuppliers(ctx.requireTenantId(), limit);
  }

  public Supplier getSupplier(TenantContext ctx, UUID id) {
    return repo.findSupplier(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + id));
  }

  // ── Purchase Orders ───────────────────────────────────────────────────────────

  public PurchaseOrder createPurchaseOrder(CreatePurchaseOrderRequest req, TenantContext ctx) {
    getSupplier(ctx, req.supplierId());
    PurchaseOrder po =
        new PurchaseOrder(
            UUID.randomUUID(),
            ctx.requireTenantId(),
            req.supplierId(),
            req.storeId(),
            Domain.PO_DRAFT,
            req.currency() != null ? req.currency().toUpperCase(java.util.Locale.ROOT) : "GBP",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            req.expectedDelivery() != null
                ? Parsing.date(req.expectedDelivery(), "expectedDelivery")
                : null,
            Instant.now(),
            Instant.now(),
            null,
            null);
    return repo.createPurchaseOrder(
        po, Events.purchaseOrderCreated(ctx.requireTenantId(), po.id()));
  }

  public List<PurchaseOrder> listPurchaseOrders(TenantContext ctx, int limit) {
    return repo.findPurchaseOrders(ctx.requireTenantId(), limit);
  }

  public PurchaseOrder getPurchaseOrder(TenantContext ctx, UUID id) {
    return repo.findPurchaseOrder(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_PO_NOT_FOUND", "Purchase order not found: " + id));
  }

  public PurchaseOrderLine addPurchaseOrderLine(
      TenantContext ctx, UUID poId, AddPurchaseOrderLineRequest req) {
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_DRAFT.equals(po.status()))
      throw ApiException.badRequest(
          "PURCHASE_PO_NOT_DRAFT", "Lines can only be added to DRAFT purchase orders");
    PurchaseOrderLine line =
        new PurchaseOrderLine(
            UUID.randomUUID(),
            ctx.requireTenantId(),
            poId,
            req.variantId(),
            req.qty(),
            req.unitPrice(),
            req.vatCode() != null ? req.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1",
            Instant.now());
    return repo.addPurchaseOrderLine(line);
  }

  public List<PurchaseOrderLine> listPurchaseOrderLines(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findPurchaseOrderLines(ctx.requireTenantId(), poId);
  }

  public PurchaseOrder submitPurchaseOrder(TenantContext ctx, UUID poId) {
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_DRAFT.equals(po.status()))
      throw ApiException.badRequest("PURCHASE_PO_NOT_DRAFT", "Only DRAFT orders can be submitted");
    repo.updatePurchaseOrderStatus(ctx.requireTenantId(), poId, Domain.PO_SUBMITTED);
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * Cancels a purchase order raised in error (SJ-D3).
   *
   * <p>{@code CANCELLED} was declared in V1's CHECK constraint and in {@link Domain} from the
   * start, but nothing ever wrote it -- the only transitions in the service were DRAFT to SUBMITTED
   * here and SUBMITTED to RECEIVED inside {@code createGoodsReceipt}. A purchase order raised by
   * mistake was therefore stuck forever, and a stuck SUBMITTED order stays receivable indefinitely.
   *
   * <p>Cancellable from DRAFT and SUBMITTED only. A RECEIVED order has stock booked against it, so
   * cancelling it would silently orphan that stock -- reverse it with a return to vendor instead
   * (not yet built). Re-cancelling an already-cancelled order is refused rather than treated as
   * idempotent: the second caller's reason would be discarded, and a cancellation whose stated
   * reason is not the one recorded is worse than an error.
   *
   * <p>The state guard is enforced in the UPDATE's WHERE clause, not by the read above it, so a
   * cancel racing a goods receipt cannot both succeed. The read exists only to distinguish 404 from
   * 409 for the caller.
   *
   * @param ctx the caller's tenant context
   * @param poId the purchase order to cancel
   * @param req the cancellation request, carrying the required reason
   * @return the cancelled purchase order
   * @throws ApiException 404 {@code PURCHASE_PO_NOT_FOUND} if no such order exists for this tenant;
   *     409 {@code PURCHASE_PO_NOT_CANCELLABLE} if it is already RECEIVED or CANCELLED
   */
  public PurchaseOrder cancelPurchaseOrder(
      TenantContext ctx, UUID poId, CancelPurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    String reason = req.reason().trim();

    boolean cancelled =
        repo.cancelPurchaseOrder(
            tenantId, poId, reason, Events.purchaseOrderCancelled(tenantId, poId, reason));
    if (!cancelled)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_CANCELLABLE",
          "Only DRAFT or SUBMITTED purchase orders can be cancelled (status: " + po.status() + ")");
    return getPurchaseOrder(ctx, poId);
  }

  // ── Goods Receipts ────────────────────────────────────────────────────────────

  public GoodsReceipt receiveGoods(
      CreateGoodsReceiptRequest req, TenantContext ctx, String idempotencyKey) {
    PurchaseOrder po = getPurchaseOrder(ctx, req.poId());
    if (!Domain.PO_SUBMITTED.equals(po.status()))
      throw ApiException.badRequest(
          "PURCHASE_PO_NOT_SUBMITTED", "Only SUBMITTED orders can be received");
    if (req.lines() == null || req.lines().isEmpty())
      throw ApiException.badRequest("PURCHASE_GRN_EMPTY", "GRN must have at least one line");

    GoodsReceipt gr =
        new GoodsReceipt(
            UUID.randomUUID(),
            ctx.requireTenantId(),
            req.poId(),
            req.storeId(),
            Instant.now(),
            Instant.now(),
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
    List<GoodsReceiptLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new GoodsReceiptLine(
                        UUID.randomUUID(),
                        ctx.requireTenantId(),
                        gr.id(),
                        l.variantId(),
                        l.qtyReceived(),
                        Instant.now()))
            .toList();
    return repo.createGoodsReceipt(
        gr,
        lines,
        Events.goodsReceived(ctx.requireTenantId(), gr.id(), gr.storeId(), gr.poId(), lines));
  }

  public List<GoodsReceipt> listGoodsReceipts(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findGoodsReceiptsByPo(ctx.requireTenantId(), poId);
  }

  // ── Intercompany Invoices (Gap #20) ───────────────────────────────────────────

  /**
   * Raises an AR invoice for the sending store and an AP invoice for the receiving store
   * atomically. Posts the corresponding FRS 102 / UK GAAP double-entry nominal ledger entries.
   *
   * <p>BACS payment due date = invoice_date + 30 days (UK standard trade terms).
   *
   * <p>Group VAT: if vatDisregarded=true (HMRC VAT Notice 700/2 — same VAT group), no VAT nominal
   * entries are posted and vat_disregarded is set on both records.
   *
   * <p>Transfer pricing: caller provides net_amount which should reflect arm's length pricing per
   * HMRC INTM (typically: cost price of the transferred goods).
   */
  public List<IntercompanyInvoice> raiseIntercompanyInvoices(
      RaiseIntercompanyInvoiceRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = UUID.fromString(req.fromStoreId());
    UUID toStore = UUID.fromString(req.toStoreId());
    if (fromStore.equals(toStore))
      throw ApiException.badRequest(
          "PURCHASE_IC_SAME_STORE", "from and to store must be different");

    UUID transferRef = req.transferRef() != null ? UUID.fromString(req.transferRef()) : null;
    String vatCode =
        req.vatCode() != null ? req.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1";
    String currency =
        req.currency() != null ? req.currency().toUpperCase(java.util.Locale.ROOT) : "GBP";
    LocalDate today = LocalDate.now();
    LocalDate dueDate = today.plusDays(BACS_TERMS_DAYS);

    UUID arId = UUID.randomUUID();
    UUID apId = UUID.randomUUID();

    IntercompanyInvoice ar =
        new IntercompanyInvoice(
            arId,
            tenantId,
            Domain.INV_AR,
            fromStore,
            toStore,
            transferRef,
            req.netAmount(),
            req.vatAmount(),
            req.grossAmount(),
            vatCode,
            req.vatDisregarded(),
            Domain.INV_RAISED,
            today,
            dueDate,
            currency,
            Instant.now());

    IntercompanyInvoice ap =
        new IntercompanyInvoice(
            apId,
            tenantId,
            Domain.INV_AP,
            fromStore,
            toStore,
            transferRef,
            req.netAmount(),
            req.vatAmount(),
            req.grossAmount(),
            vatCode,
            req.vatDisregarded(),
            Domain.INV_RAISED,
            today,
            dueDate,
            currency,
            Instant.now());

    List<NominalLedgerEntry> arEntries = buildArEntries(tenantId, arId, req, today);
    List<NominalLedgerEntry> apEntries = buildApEntries(tenantId, apId, req, today);

    return repo.createIntercompanyInvoicePair(
        ar,
        arEntries,
        Events.intercompanyInvoiceRaised(tenantId, arId),
        ap,
        apEntries,
        Events.intercompanyInvoiceRaised(tenantId, apId));
  }

  private List<NominalLedgerEntry> buildArEntries(
      UUID tenantId, UUID arId, RaiseIntercompanyInvoiceRequest req, LocalDate today) {
    String desc = "Intercompany AR invoice " + arId;
    List<NominalLedgerEntry> entries = new ArrayList<>();
    BigDecimal gross = req.grossAmount();
    BigDecimal net = req.netAmount();
    BigDecimal vat = req.vatAmount();

    // DR 1100 Debtors (gross amount owed to sending store)
    entries.add(
        ledgerEntry(
            tenantId,
            today,
            Domain.CODE_DEBTORS,
            Domain.NAME_DEBTORS,
            gross,
            BigDecimal.ZERO,
            desc,
            arId));
    if (!req.vatDisregarded() && vat.compareTo(BigDecimal.ZERO) > 0) {
      // CR 2200 VAT Output
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_VAT_OUTPUT,
              Domain.NAME_VAT_OUTPUT,
              BigDecimal.ZERO,
              vat,
              desc,
              arId));
      // CR 4000 Intercompany Sales (net only)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_SALES,
              Domain.NAME_IC_SALES,
              BigDecimal.ZERO,
              net,
              desc,
              arId));
    } else {
      // CR 4000 Intercompany Sales (gross = net when VAT disregarded)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_SALES,
              Domain.NAME_IC_SALES,
              BigDecimal.ZERO,
              gross,
              desc,
              arId));
    }
    return entries;
  }

  private List<NominalLedgerEntry> buildApEntries(
      UUID tenantId, UUID apId, RaiseIntercompanyInvoiceRequest req, LocalDate today) {
    String desc = "Intercompany AP invoice " + apId;
    List<NominalLedgerEntry> entries = new ArrayList<>();
    BigDecimal gross = req.grossAmount();
    BigDecimal net = req.netAmount();
    BigDecimal vat = req.vatAmount();

    if (!req.vatDisregarded() && vat.compareTo(BigDecimal.ZERO) > 0) {
      // DR 5000 Purchases (net)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_PURCHASES,
              Domain.NAME_IC_PURCHASES,
              net,
              BigDecimal.ZERO,
              desc,
              apId));
      // DR 2201 VAT Input
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_VAT_INPUT,
              Domain.NAME_VAT_INPUT,
              vat,
              BigDecimal.ZERO,
              desc,
              apId));
    } else {
      // DR 5000 Purchases (gross = net when VAT disregarded)
      entries.add(
          ledgerEntry(
              tenantId,
              today,
              Domain.CODE_IC_PURCHASES,
              Domain.NAME_IC_PURCHASES,
              gross,
              BigDecimal.ZERO,
              desc,
              apId));
    }
    // CR 2100 Creditors (gross)
    entries.add(
        ledgerEntry(
            tenantId,
            today,
            Domain.CODE_CREDITORS,
            Domain.NAME_CREDITORS,
            BigDecimal.ZERO,
            gross,
            desc,
            apId));
    return entries;
  }

  public IntercompanyInvoice getIntercompanyInvoice(TenantContext ctx, UUID id) {
    return repo.findIntercompanyInvoice(ctx.requireTenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_INVOICE_NOT_FOUND", "Invoice not found: " + id));
  }

  public List<IntercompanyInvoice> listIntercompanyInvoices(TenantContext ctx, int limit) {
    return repo.findIntercompanyInvoices(ctx.requireTenantId(), limit);
  }

  public void settleIntercompanyInvoice(TenantContext ctx, UUID id) {
    IntercompanyInvoice inv = getIntercompanyInvoice(ctx, id);
    LocalDate today = LocalDate.now();
    String desc = "Settlement of intercompany invoice " + id;
    List<NominalLedgerEntry> settlements = new ArrayList<>();

    if (Domain.INV_AR.equals(inv.invoiceType())) {
      // DR 1200 Bank / CR 1100 Debtors
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_BANK,
              Domain.NAME_BANK,
              inv.grossAmount(),
              BigDecimal.ZERO,
              desc,
              id));
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_DEBTORS,
              Domain.NAME_DEBTORS,
              BigDecimal.ZERO,
              inv.grossAmount(),
              desc,
              id));
    } else {
      // DR 2100 Creditors / CR 1200 Bank
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_CREDITORS,
              Domain.NAME_CREDITORS,
              inv.grossAmount(),
              BigDecimal.ZERO,
              desc,
              id));
      settlements.add(
          ledgerEntry(
              ctx.requireTenantId(),
              today,
              Domain.CODE_BANK,
              Domain.NAME_BANK,
              BigDecimal.ZERO,
              inv.grossAmount(),
              desc,
              id));
    }
    repo.settleIntercompanyInvoice(ctx.requireTenantId(), id, settlements);
  }

  // ── Nominal Ledger ────────────────────────────────────────────────────────────

  /** Cursor-paginated nominal ledger. Cursor wraps {@code entryDate|createdAt|id}. */
  public com.shelfj.web.Cursor.Page<NominalLedgerEntry> getNominalLedger(
      TenantContext ctx,
      String nominalCode,
      String fromStr,
      String toStr,
      String after,
      int limit) {
    LocalDate from = fromStr != null ? Parsing.date(fromStr, "from") : null;
    LocalDate to = toStr != null ? Parsing.date(toStr, "to") : null;
    String rawKey = com.shelfj.web.Cursor.decode(after);
    LocalDate afterEntryDate = null;
    java.time.Instant afterCreatedAt = null;
    UUID afterId = null;
    if (rawKey != null) {
      String[] parts = rawKey.split("\\|", 3);
      if (parts.length != 3) {
        throw new ApiException(
            400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), null);
      }
      try {
        afterEntryDate = LocalDate.parse(parts[0]);
        afterCreatedAt = java.time.Instant.parse(parts[1]);
        afterId = UUID.fromString(parts[2]);
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<NominalLedgerEntry> rows =
        repo.findNominalLedger(
            ctx.requireTenantId(),
            nominalCode,
            from,
            to,
            afterEntryDate,
            afterCreatedAt,
            afterId,
            limit + 1);
    return com.shelfj.web.Cursor.page(
        rows, limit, e -> e.entryDate() + "|" + e.createdAt() + "|" + e.id());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private static NominalLedgerEntry ledgerEntry(
      UUID tenantId,
      LocalDate date,
      String code,
      String name,
      BigDecimal debit,
      BigDecimal credit,
      String desc,
      UUID sourceRef) {
    return new NominalLedgerEntry(
        UUID.randomUUID(),
        tenantId,
        date,
        code,
        name,
        debit,
        credit,
        desc,
        sourceRef,
        Instant.now());
  }
}
