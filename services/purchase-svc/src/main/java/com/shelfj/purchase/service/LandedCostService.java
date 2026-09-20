package com.shelfj.purchase.service;

import com.shelfj.ids.Ids;
import com.shelfj.purchase.domain.Domain;
import com.shelfj.purchase.domain.Domain.GoodsReceipt;
import com.shelfj.purchase.domain.Domain.GoodsReceiptLine;
import com.shelfj.purchase.domain.Domain.NominalLedgerEntry;
import com.shelfj.purchase.domain.Domain.PurchaseOrder;
import com.shelfj.purchase.domain.Domain.PurchaseOrderLine;
import com.shelfj.purchase.domain.LandedCost;
import com.shelfj.purchase.domain.LandedCost.Charge;
import com.shelfj.purchase.domain.LandedCost.Line;
import com.shelfj.purchase.domain.LandedCost.Weighed;
import com.shelfj.purchase.domain.LedgerPosting;
import com.shelfj.purchase.domain.Money;
import com.shelfj.purchase.dto.Dtos.ApplyLandedCostRequest;
import com.shelfj.purchase.dto.Dtos.ReverseLandedCostRequest;
import com.shelfj.purchase.repo.LandedCostRepository;
import com.shelfj.purchase.repo.PurchaseRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Landed cost apportionment (07.x): freight, duty, insurance and the like, charged against a goods
 * receipt after the goods and spread over its lines.
 *
 * <p>What the goods cost to buy is the order's price; what they cost to land is that plus the
 * carrier, the customs broker and the insurer, who each bill separately and later. A charge here is
 * spread over the receipt's lines — by the lines' value at the order's prices or by their units —
 * posted <b>Dr Stock / Cr Landed Costs Accrued</b>, and announced so inventory-svc lifts the cost
 * of the batches that receipt created. The carrier's own bill is settled against the accrual
 * through the ledger; it is not a supplier invoice against the order, because the three-way match
 * would rightly refuse it.
 *
 * <p>A charge is never edited: what the lines were apportioned is what the stock was revalued by. A
 * mistake is reversed, with a reason, and applied again.
 */
@ApplicationScoped
public class LandedCostService {

  /** Who lands a cost: the buyer's roles, never the till. */
  private static final String[] ROLES = {"PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER"};

  private static final int MAX_LIST = 200;

  @Inject LandedCostRepository repo;
  @Inject PurchaseRepository purchases;
  @Inject PurchaseService purchase;

  /**
   * Applies a charge to a receipt.
   *
   * @throws ApiException 400 {@code PURCHASE_LANDED_INVALID}, {@code
   *     PURCHASE_LANDED_CURRENCY_MISMATCH}; 404 {@code PURCHASE_GRN_NOT_FOUND}, {@code
   *     PURCHASE_SUPPLIER_NOT_FOUND}; 409 {@code PURCHASE_PERIOD_CLOSED}; 422 {@code
   *     PURCHASE_LANDED_NOTHING_RECEIVED}, {@code PURCHASE_LANDED_NO_BASIS}
   */
  public Charge apply(ApplyLandedCostRequest req, TenantContext ctx, String idempotencyKey) {
    ctx.requireAnyRole(ROLES);
    UUID tenantId = ctx.requireTenantId();
    String type = upper(req.chargeType());
    String basis = upper(req.basis());
    String why = LandedCost.problem(type, basis, req.amount());
    if (why != null) {
      throw ApiException.badRequest("PURCHASE_LANDED_INVALID", why);
    }
    GoodsReceipt gr = receipt(tenantId, req.grId());
    ctx.requireStoreAccess(gr.storeId());
    PurchaseOrder po =
        purchases
            .findPurchaseOrder(tenantId, gr.poId())
            .orElseThrow(
                () -> ApiException.notFound("PURCHASE_PO_NOT_FOUND", "Purchase order not found"));
    String currency =
        req.currency() == null || req.currency().isBlank() ? po.currency() : upper(req.currency());
    if (!currency.equals(po.currency())) {
      throw ApiException.badRequest(
          "PURCHASE_LANDED_CURRENCY_MISMATCH",
          "the charge must be in the order's currency "
              + po.currency()
              + " — got "
              + currency
              + "; convert it at the rate you paid and keep the rate in the notes");
    }
    if (req.chargedBy() != null && purchases.findSupplier(tenantId, req.chargedBy()).isEmpty()) {
      throw ApiException.notFound(
          "PURCHASE_SUPPLIER_NOT_FOUND", "No such supplier to charge it to: " + req.chargedBy());
    }
    BigDecimal amount = Money.round(req.amount(), currency);
    List<Weighed> weighed = weighed(tenantId, po, gr, currency);
    if (weighed.isEmpty()) {
      throw ApiException.unprocessable(
          "PURCHASE_LANDED_NOTHING_RECEIVED", "this receipt has no lines to spread a charge over");
    }
    UUID id = Ids.newId();
    List<Line> lines;
    try {
      lines = LandedCost.apportion(id, tenantId, basis, amount, currency, weighed);
    } catch (IllegalArgumentException e) {
      throw new ApiException(422, "PURCHASE_LANDED_NO_BASIS", e.getMessage(), List.of(), e);
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    purchase.requireOpenPeriod(tenantId, gr.storeId(), today);
    Charge c =
        new Charge(
            id,
            tenantId,
            gr.id(),
            po.id(),
            gr.storeId(),
            type,
            basis,
            currency,
            amount,
            trimmed(req.reference()),
            req.chargedBy(),
            trimmed(req.notes()),
            LandedCost.STATUS_APPLIED,
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            ctx.userId(),
            null,
            null,
            null,
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
    List<NominalLedgerEntry> posting =
        LedgerPosting.of(
                tenantId,
                today,
                describe(type) + " landed on receipt " + gr.id(),
                Domain.SOURCE_LANDED_COST,
                id,
                gr.storeId())
            .debit(purchase.stockCodeFor(tenantId, gr.storeId()), Domain.NAME_STOCK, amount)
            .credit(Domain.CODE_LANDED_ACCRUAL, Domain.NAME_LANDED_ACCRUAL, amount)
            .build();
    return repo.apply(
        c, lines, posting, Events.landedCost(Events.LANDED_COST_APPLIED, c, lines, id));
  }

  /**
   * Reverses an applied charge: the mirror posting, and inventory told to take the uplift back.
   *
   * @throws ApiException 400 {@code PURCHASE_LANDED_REASON_REQUIRED}; 404; 409 {@code
   *     PURCHASE_LANDED_REVERSED}, {@code PURCHASE_PERIOD_CLOSED}
   */
  public Charge reverse(UUID id, ReverseLandedCostRequest req, TenantContext ctx) {
    ctx.requireAnyRole(ROLES);
    UUID tenantId = ctx.requireTenantId();
    String reason = trimmed(req == null ? null : req.reason());
    if (reason == null) {
      throw ApiException.badRequest(
          "PURCHASE_LANDED_REASON_REQUIRED", "say why the charge is being reversed");
    }
    Charge c = get(ctx, id);
    ctx.requireStoreAccess(c.storeId());
    if (!c.applied()) {
      throw ApiException.conflict(
          "PURCHASE_LANDED_REVERSED", "this charge was already reversed; apply a new one");
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    purchase.requireOpenPeriod(tenantId, c.storeId(), today);
    List<Line> lines = repo.lines(tenantId, id);
    List<NominalLedgerEntry> posting =
        LedgerPosting.of(
                tenantId,
                today,
                describe(c.chargeType()) + " on receipt " + c.grId() + " reversed: " + reason,
                Domain.SOURCE_LANDED_COST_REVERSAL,
                id,
                c.storeId())
            .debit(Domain.CODE_LANDED_ACCRUAL, Domain.NAME_LANDED_ACCRUAL, c.amount())
            .credit(purchase.stockCodeFor(tenantId, c.storeId()), Domain.NAME_STOCK, c.amount())
            .build();
    // A retried reversal is the same event: its id is derived from the charge, not minted.
    UUID eventId = Ids.derived(id, "landed-cost-reversal");
    return repo.reverse(
        tenantId,
        id,
        ctx.userId(),
        reason,
        Instant.now().truncatedTo(ChronoUnit.MICROS),
        posting,
        Events.landedCost(Events.LANDED_COST_REVERSED, c, lines, eventId));
  }

  /**
   * The charges on a receipt, on an order, or in the tenant, newest first.
   *
   * @throws ApiException 404 when the receipt or order named is not this tenant's
   */
  public List<Charge> list(TenantContext ctx, UUID grId, UUID poId) {
    UUID tenantId = ctx.requireTenantId();
    if (grId != null) receipt(tenantId, grId);
    if (poId != null) purchase.getPurchaseOrder(ctx, poId);
    return repo.list(tenantId, grId, poId, MAX_LIST);
  }

  /**
   * @throws ApiException 404 {@code PURCHASE_LANDED_NOT_FOUND}
   */
  public Charge get(TenantContext ctx, UUID id) {
    return repo.find(ctx.requireTenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_LANDED_NOT_FOUND", "No such landed cost"));
  }

  /** The lines of a charge this tenant owns. */
  public List<Line> lines(TenantContext ctx, UUID id) {
    get(ctx, id);
    return repo.lines(ctx.requireTenantId(), id);
  }

  private GoodsReceipt receipt(UUID tenantId, UUID grId) {
    return repo.findGoodsReceipt(tenantId, grId)
        .orElseThrow(
            () ->
                ApiException.notFound("PURCHASE_GRN_NOT_FOUND", "No such goods receipt: " + grId));
  }

  /** The receipt's lines with their weights: quantity, and value at the order's price. */
  private List<Weighed> weighed(UUID tenantId, PurchaseOrder po, GoodsReceipt gr, String currency) {
    Map<UUID, BigDecimal> price = new HashMap<>();
    for (PurchaseOrderLine l : purchases.findPurchaseOrderLines(tenantId, po.id())) {
      price.putIfAbsent(l.variantId(), l.unitPrice());
    }
    return purchases.findGoodsReceiptLines(tenantId, gr.id()).stream()
        .map(
            (GoodsReceiptLine l) ->
                new Weighed(
                    l.id(),
                    l.variantId(),
                    l.qtyReceived(),
                    Money.round(
                        l.qtyReceived()
                            .multiply(price.getOrDefault(l.variantId(), BigDecimal.ZERO)),
                        currency)))
        .toList();
  }

  private static String describe(String chargeType) {
    return switch (chargeType) {
      case "FREIGHT" -> "Freight";
      case "DUTY" -> "Duty";
      case "INSURANCE" -> "Insurance";
      case "HANDLING" -> "Handling";
      default -> "Landed cost";
    };
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }

  private static String trimmed(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
