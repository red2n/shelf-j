package com.shelfj.purchase.service;

import com.shelfj.ids.Ids;
import com.shelfj.purchase.client.PricingClient;
import com.shelfj.purchase.client.TenantClient;
import com.shelfj.purchase.config.ServiceConfig;
import com.shelfj.purchase.domain.Domain;
import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.IntercompanyInvoice;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.Domain.Supplier;
import com.shelfj.purchase.domain.Money;
import com.shelfj.purchase.domain.SpendAuthority;
import com.shelfj.purchase.domain.ThreeWayMatch;
import com.shelfj.purchase.domain.Totals;
import com.shelfj.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.shelfj.purchase.dto.Dtos.CancelPurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.CaptureSupplierInvoiceRequest;
import com.shelfj.purchase.dto.Dtos.CreateGoodsReceiptRequest;
import com.shelfj.purchase.dto.Dtos.CreatePurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.CreateSupplierRequest;
import com.shelfj.purchase.dto.Dtos.DecidePurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.shelfj.purchase.dto.Dtos.UpdateSupplierRequest;
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

  @Inject TenantClient tenants;

  @Inject PricingClient pricing;

  @Inject ServiceConfig config;

  // ── Currency ──────────────────────────────────────────────────────────────────

  /**
   * The tenant's own trading currency, for use when the caller names none (SJ-D23).
   *
   * <p>This service stamped a hardcoded {@code "GBP"} onto suppliers, purchase orders and
   * intercompany invoices alike — the SJ-D2 defect, in the one service SJ-D2's sweep never reached.
   * On a platform whose tenants trade in USD, JPY, INR and CNY, that is not a cosmetic default: it
   * is one country's currency written onto another country's money, and every downstream figure
   * built on it inherits the error.
   *
   * <p>Falls back to the configured platform default when tenant-svc cannot answer, rather than
   * refusing the write — see {@link TenantClient} for why that trade is the right way round here.
   *
   * @param tenantId the tenant whose currency is wanted
   * @return an ISO 4217 code, never null
   */
  private String resolveTenantCurrency(UUID tenantId) {
    return tenants
        .findCurrency(tenantId)
        .orElseGet(() -> config.defaultCurrency().toUpperCase(java.util.Locale.ROOT));
  }

  // ── Suppliers ─────────────────────────────────────────────────────────────────

  /**
   * Registers a supplier.
   *
   * <p>The supplier's currency is the one they invoice in. It defaults to the tenant's own — most
   * suppliers are domestic — but is deliberately settable, because the case that matters is the one
   * that is not: a UK tenant buying from a Japanese supplier is invoiced in JPY, and every purchase
   * order raised against that supplier is a JPY commitment.
   */
  public Supplier createSupplier(CreateSupplierRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    String currency =
        req.currency() != null
            ? Money.requireIso4217(req.currency())
            : resolveTenantCurrency(tenantId);
    Supplier s =
        new Supplier(
            Ids.newId(),
            tenantId,
            req.name(),
            req.vatNumber(),
            req.vatRegistered(),
            req.countryCode() != null ? req.countryCode().toUpperCase(java.util.Locale.ROOT) : "GB",
            currency,
            req.paymentTermsDays() != null ? req.paymentTermsDays() : BACS_TERMS_DAYS,
            Instant.now(),
            Instant.now());
    return repo.createSupplier(s);
  }

  /**
   * Corrects a supplier's master data (SJ-D34). Every field is replaceable; the currency only while
   * no purchase order against the supplier is open, because each open order is a commitment in that
   * currency and the orders already raised keep theirs either way.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the supplier to correct
   * @param req the master data as it should now read; currency, country and terms unchanged when
   *     omitted
   * @return the supplier as it now stands
   * @throws ApiException {@code PURCHASE_SUPPLIER_NOT_FOUND} (404); {@code
   *     PURCHASE_SUPPLIER_CURRENCY_IN_USE} (409) when the currency would change under an open
   *     order; {@code PURCHASE_SUPPLIER_DUPLICATE} (409) when the name is taken
   */
  public Supplier updateSupplier(TenantContext ctx, UUID id, UpdateSupplierRequest req) {
    Supplier existing = getSupplier(ctx, id);
    String currency =
        req.currency() != null ? Money.requireIso4217(req.currency()) : existing.currency();
    if (!currency.equals(existing.currency())) {
      int open = repo.countOpenPurchaseOrders(existing.tenantId(), id);
      if (open > 0) {
        throw ApiException.conflict(
            "PURCHASE_SUPPLIER_CURRENCY_IN_USE",
            open
                + " open purchase order(s) are denominated in "
                + existing.currency()
                + "; receive, close or cancel them before changing the currency");
      }
    }
    Supplier updated =
        new Supplier(
            existing.id(),
            existing.tenantId(),
            req.name().trim(),
            req.vatNumber(),
            req.vatRegistered(),
            req.countryCode() != null
                ? req.countryCode().toUpperCase(java.util.Locale.ROOT)
                : existing.countryCode(),
            currency,
            req.paymentTermsDays() != null ? req.paymentTermsDays() : existing.paymentTermsDays(),
            existing.createdAt(),
            Instant.now());
    if (!repo.updateSupplier(updated)) {
      throw ApiException.notFound("PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
    }
    return getSupplier(ctx, id);
  }

  /**
   * Lists the tenant's suppliers.
   *
   * @param ctx caller context; supplies the tenant
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the suppliers
   */
  public List<Supplier> listSuppliers(TenantContext ctx, int limit) {
    return repo.findSuppliers(ctx.requireTenantId(), limit);
  }

  /**
   * Reads one supplier.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the supplier to read
   * @return the supplier
   * @throws ApiException {@code PURCHASE_SUPPLIER_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public Supplier getSupplier(TenantContext ctx, UUID id) {
    return repo.findSupplier(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + id));
  }

  // ── Purchase Orders ───────────────────────────────────────────────────────────

  /**
   * Raises a draft purchase order against a supplier.
   *
   * <p><b>The order's currency is the supplier's</b>, not the tenant's and not a literal (SJ-D24).
   * A purchase order is a commitment to pay whoever is going to invoice, so it is denominated in
   * the currency that supplier bills in: a UK tenant ordering from a Japanese supplier commits to
   * JPY, and stamping GBP on it would misstate the liability, the approval threshold and every
   * downstream total.
   *
   * <p>A caller naming a different currency is refused rather than silently overridden, on the
   * SJ-D2 precedent — a request whose stated currency is not the one recorded is worse than an
   * error. Changing what a supplier invoices in is a change to the supplier, not to one order.
   *
   * @throws ApiException 404 if the supplier does not exist for this tenant; 400 {@code
   *     PURCHASE_CURRENCY_MISMATCH} if an explicit currency contradicts the supplier's; 400 {@code
   *     PURCHASE_INVALID_CURRENCY} if it is not an ISO 4217 code
   */
  public PurchaseOrder createPurchaseOrder(CreatePurchaseOrderRequest req, TenantContext ctx) {
    Supplier supplier = getSupplier(ctx, req.supplierId());
    String currency = supplier.currency();
    if (req.currency() != null) {
      String asked = Money.requireIso4217(req.currency());
      if (!asked.equals(currency))
        throw ApiException.badRequest(
            "PURCHASE_CURRENCY_MISMATCH",
            "currency "
                + asked
                + " does not match supplier "
                + supplier.name()
                + "'s invoicing currency "
                + currency);
    }
    PurchaseOrder po =
        new PurchaseOrder(
            Ids.newId(),
            ctx.requireTenantId(),
            req.supplierId(),
            req.storeId(),
            Domain.PO_DRAFT,
            currency,
            // Scaled to the currency rather than a bare ZERO, so a JPY order opens at 0 and a
            // GBP one at 0.00 — the same figure every later restatement will produce.
            Totals.zero(currency).net(),
            Totals.zero(currency).vat(),
            Totals.zero(currency).gross(),
            req.expectedDelivery() != null
                ? Parsing.date(req.expectedDelivery(), "expectedDelivery")
                : null,
            Instant.now(),
            Instant.now(),
            null,
            null,
            null,
            null,
            // Who raised it, from the verified JWT — never from the request body. Identity is
            // subject to golden rule #3 for the same reason tenant_id is.
            ctx.userId(),
            null,
            null);
    return repo.createPurchaseOrder(
        po, Events.purchaseOrderCreated(ctx.requireTenantId(), po.id()));
  }

  /**
   * Lists the tenant's purchase orders.
   *
   * @param ctx caller context; supplies the tenant
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the purchase orders
   */
  public List<PurchaseOrder> listPurchaseOrders(TenantContext ctx, int limit) {
    return repo.findPurchaseOrders(ctx.requireTenantId(), limit);
  }

  /**
   * Reads one purchase order.
   *
   * <p>Also the tenant-scoping guard the other purchase-order methods lean on: they call this first
   * so an order from another tenant reads as absent rather than being operated on.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the purchase order to read
   * @return the purchase order
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public PurchaseOrder getPurchaseOrder(TenantContext ctx, UUID id) {
    return repo.findPurchaseOrder(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_PO_NOT_FOUND", "Purchase order not found: " + id));
  }

  /**
   * Appends a line to a draft purchase order and restates the order's totals (SJ-D22).
   *
   * <p>The totals were the defect. {@code total_net}, {@code total_vat} and {@code total_gross}
   * were inserted as zero by {@link #createPurchaseOrder} and no code anywhere ever updated them,
   * so every purchase order in the product reported a value of zero — on the API, and on the two
   * places the procurement screen renders it. That is not a dormant column: it is a commitment
   * figure a buyer reads before approving, and the spend authority built on top of it would have
   * been authorising against nothing.
   *
   * <p>The VAT table is fetched before the transaction opens rather than inside it, so a slow
   * pricing-svc holds no database transaction open. Its absence is not fatal — see {@link
   * Totals#of} for why an unresolvable VAT code rates at zero instead of refusing the line.
   */
  public PurchaseOrderLine addPurchaseOrderLine(
      TenantContext ctx, UUID poId, AddPurchaseOrderLineRequest req) {
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_DRAFT.equals(po.status()))
      throw ApiException.badRequest(
          "PURCHASE_PO_NOT_DRAFT", "Lines can only be added to DRAFT purchase orders");
    PurchaseOrderLine line =
        new PurchaseOrderLine(
            Ids.newId(),
            ctx.requireTenantId(),
            poId,
            req.variantId(),
            req.qty(),
            req.unitPrice(),
            req.vatCode() != null ? req.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1",
            Instant.now());
    return repo.addPurchaseOrderLine(
        line, po.currency(), pricing.findVatRates(ctx.requireTenantId()));
  }

  /**
   * Lists a purchase order's lines.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order whose lines to list
   * @return the order's lines
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist in this
   *     tenant
   */
  public List<PurchaseOrderLine> listPurchaseOrderLines(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findPurchaseOrderLines(ctx.requireTenantId(), poId);
  }

  /**
   * Submits a draft purchase order, routing it for approval when it is above the submitter's own
   * spend authority.
   *
   * <p>Before this, any staff role could commit the business to any amount: {@code
   * /purchase-orders} is not under {@code /admin/}, so the authorisation filter asked only for
   * "some staff role", and a cashier could submit an order for a million pounds. The order now
   * lands in {@code SUBMITTED} if the submitter's authority covers it and {@code PENDING_APPROVAL}
   * if it does not — and either way the submission is recorded in the append-only trail, so a
   * question about who committed what has an answer.
   *
   * <p><b>Separation of duties falls out of this rather than being bolted on.</b> An order only
   * reaches PENDING_APPROVAL because it exceeded the submitter's ceiling — so by construction that
   * same person cannot approve it, because {@link #approvePurchaseOrder} applies the identical
   * check. There is deliberately no separate "you may not approve your own order" rule: it would be
   * redundant here, and it would deadlock a single-owner shop where one person legitimately raises
   * and approves everything within their unlimited authority.
   *
   * @throws ApiException 400 {@code PURCHASE_PO_NOT_DRAFT} if the order is not DRAFT; 409 if it
   *     stopped being DRAFT between the read and the write
   */
  public PurchaseOrder submitPurchaseOrder(TenantContext ctx, UUID poId) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_DRAFT.equals(po.status()))
      throw ApiException.badRequest("PURCHASE_PO_NOT_DRAFT", "Only DRAFT orders can be submitted");

    SpendAuthority authority =
        SpendAuthority.decide(po.totalNet(), po.currency(), ctx.roles(), config.approvalLimits());
    String landing = authority.authorised() ? Domain.PO_SUBMITTED : Domain.PO_PENDING_APPROVAL;

    boolean submitted =
        repo.submitPurchaseOrder(
            tenantId,
            poId,
            landing,
            trailRow(ctx, po, Domain.APPROVAL_REQUESTED, authority, authority.reason()));
    if (!submitted)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_DRAFT", "The order stopped being DRAFT before it could be submitted");
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * Approves an order that was above its submitter's authority.
   *
   * <p>The approver's own authority is checked against the same figure by the same function — an
   * approval by someone who could not have submitted the order themselves would defeat the entire
   * control, and is the obvious way to get this wrong.
   *
   * <p>The order's total is re-read here rather than taken from the request, and stamped onto the
   * trail row: an order can be edited after a rejection, so approving against a figure the caller
   * supplied would let the amount change between the review and the decision.
   *
   * @throws ApiException 404 if no such order; 409 {@code PURCHASE_PO_NOT_PENDING_APPROVAL} if it
   *     is not awaiting a decision; 403 {@code PURCHASE_APPROVAL_EXCEEDS_AUTHORITY} if the
   *     approver's own ceiling does not cover it
   */
  public PurchaseOrder approvePurchaseOrder(
      TenantContext ctx, UUID poId, DecidePurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = requirePendingApproval(ctx, poId);

    SpendAuthority authority =
        SpendAuthority.decide(po.totalNet(), po.currency(), ctx.roles(), config.approvalLimits());
    if (!authority.authorised())
      throw ApiException.forbidden("PURCHASE_APPROVAL_EXCEEDS_AUTHORITY", authority.reason());

    boolean decided =
        repo.decidePurchaseOrder(
            tenantId,
            poId,
            true,
            trailRow(
                ctx,
                po,
                Domain.APPROVAL_APPROVED,
                authority,
                req == null ? null : trimmed(req.reason())));
    if (!decided)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_PENDING_APPROVAL",
          "The order was decided by someone else before this approval landed");
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * Rejects an order awaiting approval, returning it to DRAFT so it can be corrected and
   * resubmitted.
   *
   * <p>A reason is required, and an approval's is not, because only the rejection leaves somebody
   * with work to do and no idea what to change.
   *
   * <p>Rejecting needs no spend authority. Refusing to commit money is not itself a commitment, and
   * requiring authority to say no would mean an order too large for anyone configured could never
   * be cleared out of the queue at all.
   *
   * @throws ApiException 404 if no such order; 409 if it is not awaiting a decision; 400 {@code
   *     PURCHASE_APPROVAL_REASON_REQUIRED} if no reason is given
   */
  public PurchaseOrder rejectPurchaseOrder(
      TenantContext ctx, UUID poId, DecidePurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = requirePendingApproval(ctx, poId);
    String reason = req == null ? null : trimmed(req.reason());
    if (reason == null)
      throw ApiException.badRequest(
          "PURCHASE_APPROVAL_REASON_REQUIRED",
          "A rejection must say why, so the buyer knows what to change");

    SpendAuthority authority =
        SpendAuthority.decide(po.totalNet(), po.currency(), ctx.roles(), config.approvalLimits());
    boolean decided =
        repo.decidePurchaseOrder(
            tenantId, poId, false, trailRow(ctx, po, Domain.APPROVAL_REJECTED, authority, reason));
    if (!decided)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_PENDING_APPROVAL",
          "The order was decided by someone else before this rejection landed");
    return getPurchaseOrder(ctx, poId);
  }

  /**
   * The order's complete approval history — every submission and every decision.
   *
   * @throws ApiException 404 if the order does not exist for this tenant
   */
  public List<Domain.PurchaseOrderApproval> purchaseOrderApprovals(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId); // 404s another tenant's order before reading its trail
    return repo.findApprovals(ctx.requireTenantId(), poId);
  }

  /**
   * Whether spend authority is configured at all; false means submission is never routed.
   *
   * @return {@code true} when at least one approval limit is configured
   */
  public boolean approvalEnabled() {
    return config.approvalEnabled();
  }

  /**
   * The caller's own spend ceiling in one currency.
   *
   * <p>Asks "what is my ceiling", not "may I spend this" — the same decision routine answers both,
   * given a null total.
   *
   * @param ctx caller context; supplies the tenant and the roles the ceiling is derived from
   * @param currency ISO-4217 code; limits are configured per currency because Shelf-J does no FX
   * @return the caller's authority in that currency
   * @throws ApiException {@code 400} when the currency is not an ISO-4217 code
   */
  public SpendAuthority spendAuthority(TenantContext ctx, String currency) {
    ctx.requireTenantId();
    // A null total asks "what is my ceiling", not "may I spend this", and decide() answers both.
    return SpendAuthority.decide(
        null, Money.requireIso4217(currency), ctx.roles(), config.approvalLimits());
  }

  private PurchaseOrder requirePendingApproval(TenantContext ctx, UUID poId) {
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    if (!Domain.PO_PENDING_APPROVAL.equals(po.status()))
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_PENDING_APPROVAL",
          "Only an order awaiting approval can be decided (status: " + po.status() + ")");
    return po;
  }

  /**
   * Builds one append-only trail row, capturing the figure and the authority as they stand at this
   * moment rather than leaving either to be re-derived later from data that can change.
   */
  private Domain.PurchaseOrderApproval trailRow(
      TenantContext ctx,
      PurchaseOrder po,
      String decision,
      SpendAuthority authority,
      String reason) {
    return new Domain.PurchaseOrderApproval(
        Ids.newId(),
        po.tenantId(),
        po.id(),
        decision,
        po.totalNet(),
        po.currency(),
        authority.ceiling(),
        ctx.userId(),
        authority.role(),
        reason,
        Instant.now());
  }

  private static String trimmed(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
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

  /**
   * What is still outstanding on a purchase order, line by line.
   *
   * <p>The reason partial receipt needs a screen and not only a status: a buyer chasing a supplier
   * has to know <em>what</em> is missing, and "PARTIALLY_RECEIVED" does not say.
   */
  public List<Domain.PurchaseOrderLineProgress> purchaseOrderProgress(
      TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId); // 404s for another tenant's order before reading any quantity
    return repo.findLineProgress(ctx.requireTenantId(), poId);
  }

  /**
   * Short-closes a partially received order: the balance is never arriving and we have stopped
   * waiting for it.
   *
   * <p>Without this a partially received order that the supplier never completes sits in
   * PARTIALLY_RECEIVED forever — the same "stuck for good" shape SJ-D3 fixed for DRAFT and
   * SUBMITTED, which is why building partial receipt without building this would have traded one
   * dead end for another.
   *
   * <p>CLOSED rather than RECEIVED because "we got it all" and "we gave up on the rest" are
   * different facts, and a supplier scorecard that cannot tell them apart is worthless. CLOSED
   * rather than CANCELLED because stock is booked against this order — SJ-D3's own reason for
   * refusing to cancel a received one.
   */
  public PurchaseOrder closePurchaseOrderShort(
      TenantContext ctx, UUID poId, CancelPurchaseOrderRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, poId);
    boolean closed = repo.closePurchaseOrderShort(tenantId, poId, req.reason().trim());
    if (!closed)
      throw ApiException.conflict(
          "PURCHASE_PO_NOT_CLOSEABLE",
          "only a PARTIALLY_RECEIVED order can be short-closed — this one is "
              + po.status()
              + ". Nothing delivered? Cancel it. Everything delivered? It is already RECEIVED.");
    return getPurchaseOrder(ctx, poId);
  }

  // ── Supplier invoices (three-way match) ───────────────────────────────────────

  /**
   * Records a supplier's invoice against a purchase order and matches it three ways.
   *
   * <p>The invoice is stored whether or not it matches. Flagging never blocks capture: an invoice
   * that arrived is a fact, and refusing to record one that disagrees with the order destroys the
   * evidence of the disagreement — which is exactly what somebody needs in order to argue with the
   * supplier.
   *
   * <p>The one thing that <em>is</em> refused is a currency the order was not placed in. That is
   * not a variance to flag; it is a different document, and matching a JPY invoice against a GBP
   * order would compare two numbers that share nothing but a decimal point (SJ-D24, SJ-D25).
   *
   * @throws ApiException 404 if the order does not exist for this tenant; 400 {@code
   *     PURCHASE_CURRENCY_MISMATCH} for the wrong currency; 409 {@code PURCHASE_INVOICE_DUPLICATE}
   *     if this supplier's invoice number was already captured
   */
  public Domain.SupplierInvoice captureSupplierInvoice(
      TenantContext ctx, CaptureSupplierInvoiceRequest req) {
    UUID tenantId = ctx.requireTenantId();
    PurchaseOrder po = getPurchaseOrder(ctx, req.poId());

    if (req.lines() == null || req.lines().isEmpty())
      throw ApiException.badRequest(
          "PURCHASE_INVOICE_NO_LINES", "an invoice with no lines has nothing to match");

    String currency = po.currency();
    if (req.currency() != null) {
      String asked = Money.requireIso4217(req.currency());
      if (!asked.equals(currency))
        throw ApiException.badRequest(
            "PURCHASE_CURRENCY_MISMATCH",
            "invoice currency " + asked + " does not match the order's " + currency);
    }

    // Matched against the order and every receipt AND every earlier invoice on it — see
    // findMatchPositions for why the invoiced leg has to be cumulative.
    List<ThreeWayMatch.MatchLine> matched =
        ThreeWayMatch.match(
            req.lines().stream()
                .map(l -> new ThreeWayMatch.InvoicedLine(l.variantId(), l.qty(), l.unitPrice()))
                .toList(),
            repo.findMatchPositions(tenantId, req.poId()),
            config.matchTolerance());

    BigDecimal net = BigDecimal.ZERO;
    for (var l : req.lines()) {
      net = net.add(Money.round(l.qty().multiply(l.unitPrice()), currency));
    }
    net = Money.round(net, currency);
    BigDecimal vat =
        Money.round(req.vatAmount() == null ? BigDecimal.ZERO : req.vatAmount(), currency);

    boolean allMatched = matched.stream().allMatch(ThreeWayMatch.MatchLine::matched);
    UUID invoiceId = Ids.newId();
    Domain.SupplierInvoice invoice =
        new Domain.SupplierInvoice(
            invoiceId,
            tenantId,
            po.id(),
            po.supplierId(),
            req.invoiceNumber().trim(),
            Parsing.date(req.invoiceDate(), "invoiceDate"),
            currency,
            net,
            vat,
            net.add(vat),
            allMatched ? Domain.INVOICE_MATCHED : Domain.INVOICE_FLAGGED,
            Instant.now(),
            ctx.userId(),
            Instant.now());

    List<Domain.SupplierInvoiceLine> lines = new ArrayList<>(req.lines().size());
    for (int i = 0; i < req.lines().size(); i++) {
      var in = req.lines().get(i);
      lines.add(
          new Domain.SupplierInvoiceLine(
              Ids.newId(),
              tenantId,
              invoiceId,
              in.variantId(),
              in.qty(),
              in.unitPrice(),
              in.vatCode() != null ? in.vatCode().toUpperCase(java.util.Locale.ROOT) : "T1",
              String.join(",", matched.get(i).variances()),
              Instant.now()));
    }
    return repo.captureSupplierInvoice(invoice, lines);
  }

  /**
   * Lists the supplier invoices captured against a purchase order.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order whose invoices to list
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the supplier invoices
   */
  public List<Domain.SupplierInvoice> listSupplierInvoices(
      TenantContext ctx, UUID poId, int limit) {
    return repo.findSupplierInvoices(ctx.requireTenantId(), poId, limit);
  }

  /**
   * Reads one supplier invoice.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the invoice to read
   * @return the supplier invoice
   * @throws ApiException {@code PURCHASE_INVOICE_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public Domain.SupplierInvoice getSupplierInvoice(TenantContext ctx, UUID id) {
    return repo.findSupplierInvoice(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PURCHASE_INVOICE_NOT_FOUND", "Supplier invoice not found: " + id));
  }

  /**
   * The invoice's lines with all three documents' figures beside them.
   *
   * <p>The variances come from the stored line rather than being recomputed, because they are the
   * figures the decision was made against: the purchase order can be amended after an invoice is
   * flagged, and re-matching on read would silently erase the disagreement it was flagged for. The
   * ordered and received columns beside them are read live, so the screen can show both what was
   * true then and what is true now.
   */
  public List<Domain.SupplierInvoiceLine> supplierInvoiceLines(TenantContext ctx, UUID invoiceId) {
    getSupplierInvoice(ctx, invoiceId);
    return repo.findSupplierInvoiceLines(ctx.requireTenantId(), invoiceId);
  }

  /**
   * What the order and the receipts say about each variant, before any new invoice is applied.
   *
   * <p>The baseline a three-way match is computed against.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order to read positions for
   * @return one position per ordered variant
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist in this
   *     tenant
   */
  public List<ThreeWayMatch.OrderPosition> matchPositions(TenantContext ctx, UUID poId) {
    getPurchaseOrder(ctx, poId);
    return repo.findMatchPositions(ctx.requireTenantId(), poId);
  }

  // ── Goods Receipts ────────────────────────────────────────────────────────────

  /**
   * Books a delivery against a purchase order, moving it to PARTIALLY_RECEIVED or RECEIVED.
   *
   * <p>A partially received order is still receivable — that is the point of the state. The status
   * check here only fails a hopeless request early with a clear message; the authoritative
   * over-receipt check runs inside the repository transaction under a row lock.
   *
   * @param req the purchase order and the quantities received per variant
   * @param ctx caller context; supplies the tenant
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retried delivery is not booked
   *     twice
   * @return the recorded goods receipt
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist; {@code
   *     PURCHASE_PO_NOT_RECEIVABLE} (400) when it is not SUBMITTED or PARTIALLY_RECEIVED
   */
  public GoodsReceipt receiveGoods(
      CreateGoodsReceiptRequest req, TenantContext ctx, String idempotencyKey) {
    PurchaseOrder po = getPurchaseOrder(ctx, req.poId());
    // A partially received order is still receivable — that is the whole point of the state. The
    // authoritative check is inside the repository transaction, under a row lock; this one exists
    // to fail a hopeless request early with a clearer message than a rolled-back transaction.
    if (!Domain.PO_SUBMITTED.equals(po.status())
        && !Domain.PO_PARTIALLY_RECEIVED.equals(po.status()))
      throw ApiException.badRequest(
          "PURCHASE_PO_NOT_RECEIVABLE",
          "a purchase order can only be received while SUBMITTED or PARTIALLY_RECEIVED — this one"
              + " is "
              + po.status());
    if (req.lines() == null || req.lines().isEmpty())
      throw ApiException.badRequest("PURCHASE_GRN_EMPTY", "GRN must have at least one line");

    GoodsReceipt gr =
        new GoodsReceipt(
            Ids.newId(),
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
                        Ids.newId(),
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

  /**
   * Lists the deliveries booked against a purchase order.
   *
   * @param ctx caller context; supplies the tenant
   * @param poId the purchase order whose receipts to list
   * @return the goods receipts
   * @throws ApiException {@code PURCHASE_PO_NOT_FOUND} (404) when the order does not exist in this
   *     tenant
   */
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
    // Intercompany invoicing is store-to-store inside one tenant, so the tenant's own currency is
    // the right default here — unlike a purchase order, where the counterparty is an outside
    // supplier who may invoice in their own (SJ-D23/SJ-D24).
    String currency =
        req.currency() != null
            ? Money.requireIso4217(req.currency())
            : resolveTenantCurrency(tenantId);
    LocalDate today = LocalDate.now();
    LocalDate dueDate = today.plusDays(BACS_TERMS_DAYS);

    UUID arId = Ids.newId();
    UUID apId = Ids.newId();

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

  /**
   * Reads one intercompany invoice.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the invoice to read
   * @return the invoice, either the AR or the AP side of a pair
   * @throws ApiException {@code PURCHASE_INVOICE_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public IntercompanyInvoice getIntercompanyInvoice(TenantContext ctx, UUID id) {
    return repo.findIntercompanyInvoice(ctx.requireTenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_INVOICE_NOT_FOUND", "Invoice not found: " + id));
  }

  /**
   * Lists the tenant's intercompany invoices, both AR and AP sides.
   *
   * @param ctx caller context; supplies the tenant
   * @param limit maximum rows; the caller is expected to have clamped this
   * @return the invoices
   */
  public List<IntercompanyInvoice> listIntercompanyInvoices(TenantContext ctx, int limit) {
    return repo.findIntercompanyInvoices(ctx.requireTenantId(), limit);
  }

  /**
   * Settles an intercompany invoice, posting the matching nominal-ledger entries.
   *
   * <p>Which entries depends on the side: an AR invoice debits Bank and credits Debtors, an AP one
   * the mirror image, so the two books stay in agreement.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the invoice to settle
   * @throws ApiException {@code PURCHASE_INVOICE_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
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
        Ids.newId(), tenantId, date, code, name, debit, credit, desc, sourceRef, Instant.now());
  }
}
