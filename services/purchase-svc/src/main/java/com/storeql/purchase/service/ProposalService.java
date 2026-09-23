package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.InventoryClient;
import com.storeql.purchase.client.InventoryClient.ForecastGlance;
import com.storeql.purchase.client.PricingClient;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.ProposalRun;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.SkippedItem;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.OrderProposal;
import com.storeql.purchase.domain.OrderProposal.Order;
import com.storeql.purchase.domain.OrderProposal.Plan;
import com.storeql.purchase.domain.OrderProposal.Position;
import com.storeql.purchase.domain.OrderProposal.Skipped;
import com.storeql.purchase.domain.Totals;
import com.storeql.purchase.repo.ProposalRepository;
import com.storeql.purchase.repo.ProposalRepository.SupplierChoice;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The automatic order proposal (06.x): the store's stock position read from inventory-svc, what is
 * already on order read from this service's own books, and for every item at or below its reorder
 * point a DRAFT purchase order on the supplier the business last bought it from — one order per
 * supplier, every line carrying the arithmetic that produced it. A person submits the draft; a
 * machine never commits money to a supplier.
 *
 * <p>Refusals fail closed where a wrong proposal would cost money: with no stock position there is
 * no proposal (503), and while a proposed order for the store is still a draft there is no second
 * one (409), so a run never doubles up on itself. The forecast alone fails open — without it the
 * plan's average daily demand stands in, and the line says so.
 */
@ApplicationScoped
public class ProposalService {

  public static final int DEFAULT_COVER_DAYS = 28;
  public static final int MAX_COVER_DAYS = 365;

  /** The forecast the client reads is the next twenty-eight days; a cover period scales it. */
  private static final BigDecimal FORECAST_DAYS = BigDecimal.valueOf(28);

  @Inject ProposalRepository repo;
  @Inject PurchaseRepository purchases;
  @Inject InventoryClient inventory;
  @Inject PricingClient pricing;

  /** One draft order a run raised, described for the reply. */
  public record ProposedOrder(
      UUID poId,
      UUID supplierId,
      String supplierName,
      String currency,
      int lines,
      BigDecimal totalNet) {}

  /** A run and its orders, described. */
  public record RunResult(ProposalRun run, List<ProposedOrder> orders) {
    public RunResult {
      orders = List.copyOf(orders);
    }
  }

  private record ProposedLine(
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      String reason,
      int leadTimeDays) {}

  /**
   * Proposes orders for a store.
   *
   * @param ctx the caller, held to their stores
   * @param storeId the store
   * @param coverDays days an order without an EOQ should cover; null for 28
   * @return the run and the drafts it raised
   * @throws ApiException 400 PURCHASE_PROPOSAL_COVER_INVALID, 409 PURCHASE_PROPOSAL_OPEN, 503
   *     PURCHASE_PROPOSAL_STOCK_UNAVAILABLE
   */
  public RunResult run(TenantContext ctx, UUID storeId, Integer coverDays) {
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    int cover = coverDays == null ? DEFAULT_COVER_DAYS : coverDays;
    if (cover < 1 || cover > MAX_COVER_DAYS) {
      throw ApiException.badRequest(
          "PURCHASE_PROPOSAL_COVER_INVALID", "coverDays must be between 1 and " + MAX_COVER_DAYS);
    }
    if (repo.openProposalDrafts(tenantId, storeId) > 0) {
      throw ApiException.conflict(
          "PURCHASE_PROPOSAL_OPEN",
          "A proposed order for this store is still a draft — submit or cancel it before proposing again");
    }
    List<Plan> plans =
        inventory.reorderPlans(tenantId, storeId).orElseThrow(ProposalService::stockUnavailable);
    Map<UUID, BigDecimal> available =
        inventory
            .availableByVariant(tenantId, storeId)
            .orElseThrow(ProposalService::stockUnavailable);
    Map<UUID, ForecastGlance> forecast =
        inventory.forecastGlances(tenantId, storeId).orElse(Map.of());
    Map<UUID, BigDecimal> onOrder = repo.onOrderByVariant(tenantId, storeId);
    List<UUID> variants = plans.stream().map(Plan::variantId).toList();
    Map<UUID, SupplierChoice> lastBought = repo.lastSupplierByVariant(tenantId, variants);
    Map<UUID, UUID> coded = repo.itemCodeSupplierByVariant(tenantId, variants);

    Map<UUID, List<ProposedLine>> bySupplier = new LinkedHashMap<>();
    List<SkippedItem> skipped = new ArrayList<>();
    for (Plan plan : plans) {
      UUID v = plan.variantId();
      ForecastGlance glance = forecast.get(v);
      Integer shelfLife = glance == null ? null : glance.maxCoverDays();
      // The cover this line will get: what was asked for, or the shelf life when that is shorter.
      int lineCover = shelfLife != null && shelfLife < cover ? shelfLife : cover;
      BigDecimal expected = glance == null ? null : glance.next28();
      if (expected != null && lineCover != FORECAST_DAYS.intValue()) {
        expected =
            expected
                .multiply(BigDecimal.valueOf(lineCover))
                .divide(FORECAST_DAYS, 3, RoundingMode.HALF_UP);
      }
      OrderProposal.Result result =
          OrderProposal.propose(
              plan,
              new Position(
                  available.getOrDefault(v, BigDecimal.ZERO),
                  onOrder.getOrDefault(v, BigDecimal.ZERO),
                  expected,
                  shelfLife),
              cover);
      if (result instanceof Skipped s) {
        skipped.add(new SkippedItem(v, s.reason()));
        continue;
      }
      if (!(result instanceof Order order)) {
        continue;
      }
      SupplierChoice choice = lastBought.get(v);
      if (choice == null && coded.containsKey(v)) {
        choice = new SupplierChoice(coded.get(v), null, null);
      }
      if (choice == null) {
        skipped.add(
            new SkippedItem(
                v,
                "no supplier: the business has not bought this item on the platform and no supplier's item code names it"));
        continue;
      }
      bySupplier
          .computeIfAbsent(choice.supplierId(), k -> new ArrayList<>())
          .add(
              new ProposedLine(
                  v,
                  order.qty(),
                  choice.unitPrice() == null ? BigDecimal.ZERO : choice.unitPrice(),
                  choice.vatCode() == null ? "T1" : choice.vatCode(),
                  order.reason(),
                  plan.leadTimeDays()));
    }

    Instant now = Instant.now();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Map<String, BigDecimal> vatRates =
        bySupplier.isEmpty() ? Map.of() : pricing.findVatRates(tenantId);
    List<UUID> orderIds = new ArrayList<>();
    List<ProposedOrder> orders = new ArrayList<>();
    int lines = 0;
    for (Map.Entry<UUID, List<ProposedLine>> e : bySupplier.entrySet()) {
      Supplier supplier =
          purchases
              .findSupplier(tenantId, e.getKey())
              .orElseThrow(
                  () ->
                      ApiException.notFound(
                          "PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found: " + e.getKey()));
      int lead = e.getValue().stream().mapToInt(ProposedLine::leadTimeDays).max().orElse(7);
      PurchaseOrder po =
          new PurchaseOrder(
              Ids.newId(),
              tenantId,
              supplier.id(),
              storeId,
              Domain.PO_DRAFT,
              supplier.currency(),
              Totals.zero(supplier.currency()).net(),
              Totals.zero(supplier.currency()).vat(),
              Totals.zero(supplier.currency()).gross(),
              today.plusDays(lead),
              now,
              now,
              null,
              null,
              null,
              null,
              ctx.userId(),
              null,
              null,
              Domain.PO_SOURCE_PROPOSAL);
      purchases.createPurchaseOrder(po, Events.purchaseOrderCreated(tenantId, po.id()));
      for (ProposedLine l : e.getValue()) {
        purchases.addPurchaseOrderLine(
            new PurchaseOrderLine(
                Ids.newId(),
                tenantId,
                po.id(),
                l.variantId(),
                l.qty(),
                l.unitPrice(),
                l.vatCode(),
                now,
                l.reason()),
            supplier.currency(),
            vatRates);
        lines++;
      }
      PurchaseOrder restated = purchases.findPurchaseOrder(tenantId, po.id()).orElse(po);
      orderIds.add(po.id());
      orders.add(
          new ProposedOrder(
              po.id(),
              supplier.id(),
              supplier.name(),
              supplier.currency(),
              e.getValue().size(),
              restated.totalNet()));
    }
    ProposalRun run =
        new ProposalRun(
            Ids.newId(),
            tenantId,
            storeId,
            ctx.userId(),
            now,
            cover,
            plans.size(),
            orderIds.size(),
            lines,
            orderIds,
            skipped);
    repo.insertRun(run);
    return new RunResult(run, orders);
  }

  /**
   * The store's runs, latest first, each with its orders as they stand now.
   *
   * @param ctx the caller, held to their stores
   * @param storeId the store
   * @param limit at most this many runs
   * @return the runs
   */
  public List<RunResult> list(TenantContext ctx, UUID storeId, int limit) {
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    List<RunResult> out = new ArrayList<>();
    for (ProposalRun run : repo.listRuns(tenantId, storeId, limit)) {
      List<ProposedOrder> orders = new ArrayList<>();
      for (UUID poId : run.orderIds()) {
        purchases
            .findPurchaseOrder(tenantId, poId)
            .ifPresent(
                po ->
                    orders.add(
                        new ProposedOrder(
                            po.id(),
                            po.supplierId(),
                            purchases
                                .findSupplier(tenantId, po.supplierId())
                                .map(Supplier::name)
                                .orElse(null),
                            po.currency(),
                            purchases.findPurchaseOrderLines(tenantId, po.id()).size(),
                            po.totalNet())));
      }
      out.add(new RunResult(run, orders));
    }
    return out;
  }

  private static ApiException stockUnavailable() {
    return new ApiException(
        503,
        "PURCHASE_PROPOSAL_STOCK_UNAVAILABLE",
        "inventory-svc could not be read; nothing is proposed without the stock position",
        List.of(),
        null);
  }
}
