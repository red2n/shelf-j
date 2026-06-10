package com.shelfj.pricing.service;

import com.shelfj.pricing.domain.Domain.CustomerVatStatus;
import com.shelfj.pricing.domain.Domain.PriceList;
import com.shelfj.pricing.domain.Domain.PriceListItem;
import com.shelfj.pricing.domain.Domain.ProductVatCategory;
import com.shelfj.pricing.domain.Domain.Promotion;
import com.shelfj.pricing.domain.Domain.PromotionItem;
import com.shelfj.pricing.domain.Domain.ResolvedPrice;
import com.shelfj.pricing.domain.Domain.TaxTransaction;
import com.shelfj.pricing.domain.Domain.VatRate;
import com.shelfj.pricing.domain.Domain.VatReturn;
import com.shelfj.pricing.dto.Dtos.AddPromotionItemRequest;
import com.shelfj.pricing.dto.Dtos.BatchUpsertPriceListItemsRequest;
import com.shelfj.pricing.dto.Dtos.BatchUpsertResult;
import com.shelfj.pricing.dto.Dtos.CreatePriceListRequest;
import com.shelfj.pricing.dto.Dtos.CreatePromotionRequest;
import com.shelfj.pricing.dto.Dtos.CreateVatRateRequest;
import com.shelfj.pricing.dto.Dtos.RecordTaxTransactionRequest;
import com.shelfj.pricing.dto.Dtos.ResolvePriceRequest;
import com.shelfj.pricing.dto.Dtos.UpsertCustomerVatStatusRequest;
import com.shelfj.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.shelfj.pricing.dto.Dtos.UpsertProductVatCategoryRequest;
import com.shelfj.pricing.repo.PricingRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Business logic for pricing-svc. Controllers call this; no HTTP types here. */
@ApplicationScoped
public class PricingService {

  @Inject PricingRepository repo;

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  public VatRate createVatRate(CreateVatRateRequest req, TenantContext ctx) {
    if (req.rate().compareTo(BigDecimal.ONE) > 0)
      throw ApiException.badRequest("PRICING_INVALID_RATE", "VAT rate must be between 0 and 1");
    VatRate r =
        new VatRate(
            UUID.randomUUID(),
            ctx.tenantId(),
            req.code().toUpperCase(java.util.Locale.ROOT),
            req.name(),
            req.rate(),
            req.exempt(),
            req.description(),
            Instant.parse(req.effectiveFrom()),
            null,
            Instant.now());
    return repo.createVatRate(r);
  }

  public List<VatRate> listVatRates(TenantContext ctx) {
    return repo.findVatRates(ctx.tenantId());
  }

  public VatRate getVatRate(TenantContext ctx, String code) {
    return repo.findVatRate(ctx.tenantId(), code.toUpperCase(java.util.Locale.ROOT))
        .orElseThrow(
            () ->
                ApiException.notFound("PRICING_VAT_CODE_NOT_FOUND", "VAT code not found: " + code));
  }

  public VatRate updateVatRate(TenantContext ctx, String code, CreateVatRateRequest req) {
    VatRate existing = getVatRate(ctx, code);
    if (req.rate().compareTo(BigDecimal.ONE) > 0)
      throw ApiException.badRequest("PRICING_INVALID_RATE", "VAT rate must be between 0 and 1");
    Instant effectiveTo =
        req.effectiveFrom() != null ? Instant.parse(req.effectiveFrom()) : existing.effectiveFrom();
    VatRate updated =
        new VatRate(
            existing.id(),
            ctx.tenantId(),
            code.toUpperCase(java.util.Locale.ROOT),
            req.name(),
            req.rate(),
            req.exempt(),
            req.description(),
            effectiveTo,
            null,
            existing.createdAt());
    return repo.updateVatRate(updated);
  }

  // ── Product VAT Categories ────────────────────────────────────────────────

  public ProductVatCategory upsertProductVatCategory(
      UpsertProductVatCategoryRequest req, TenantContext ctx) {
    String vatCode = req.vatCode().toUpperCase(java.util.Locale.ROOT);
    repo.findVatRate(ctx.tenantId(), vatCode)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_VAT_CODE_NOT_FOUND", "VAT code not found: " + vatCode));
    ProductVatCategory pvc =
        new ProductVatCategory(
            UUID.randomUUID(),
            ctx.tenantId(),
            UUID.fromString(req.variantId()),
            vatCode,
            Instant.now(),
            null,
            Instant.now());
    return repo.upsertProductVatCategory(pvc);
  }

  public ProductVatCategory getProductVatCategory(TenantContext ctx, UUID variantId) {
    return repo.findProductVatCategory(ctx.tenantId(), variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_VAT_CATEGORY_NOT_FOUND",
                    "no VAT category assigned for variant " + variantId));
  }

  // ── Customer VAT Status ───────────────────────────────────────────────────

  public CustomerVatStatus upsertCustomerVatStatus(
      UpsertCustomerVatStatusRequest req, TenantContext ctx) {
    CustomerVatStatus cvs =
        new CustomerVatStatus(
            UUID.randomUUID(),
            ctx.tenantId(),
            UUID.fromString(req.customerId()),
            req.vatNumber(),
            req.vatRegistered(),
            req.reverseChargeEligible(),
            req.countryCode() != null ? req.countryCode() : "GB",
            Instant.now(),
            Instant.now());
    return repo.upsertCustomerVatStatus(cvs);
  }

  public CustomerVatStatus getCustomerVatStatus(TenantContext ctx, UUID customerId) {
    return repo.findCustomerVatStatus(ctx.tenantId(), customerId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_CUSTOMER_VAT_NOT_FOUND", "no VAT status for customer " + customerId));
  }

  // ── Price Lists ───────────────────────────────────────────────────────────

  public PriceList createPriceList(CreatePriceListRequest req, TenantContext ctx) {
    PriceList pl =
        new PriceList(
            UUID.randomUUID(),
            ctx.tenantId(),
            req.name(),
            req.channel() != null ? req.channel() : PriceList.CHANNEL_ALL,
            req.currency() != null ? req.currency() : "GBP",
            Instant.parse(req.effectiveFrom()),
            req.effectiveTo() != null ? Instant.parse(req.effectiveTo()) : null,
            true,
            Instant.now());
    return repo.createPriceList(pl);
  }

  public List<PriceList> listPriceLists(TenantContext ctx) {
    return repo.findPriceLists(ctx.tenantId());
  }

  public PriceList getPriceList(TenantContext ctx, UUID id) {
    return repo.findPriceList(ctx.tenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PRICING_LIST_NOT_FOUND", "price list not found: " + id));
  }

  // ── Price List Items ──────────────────────────────────────────────────────

  public PriceListItem upsertPriceListItem(
      TenantContext ctx, UUID priceListId, UpsertPriceListItemRequest req) {
    getPriceList(ctx, priceListId);
    PriceListItem item =
        new PriceListItem(
            UUID.randomUUID(),
            ctx.tenantId(),
            priceListId,
            UUID.fromString(req.variantId()),
            req.price(),
            req.minQty() != null ? req.minQty() : BigDecimal.ONE,
            Instant.now(),
            Instant.now());
    return repo.upsertPriceListItem(item, Events.priceChanged(ctx.tenantId(), priceListId));
  }

  public BatchUpsertResult batchUpsertPriceListItems(
      TenantContext ctx, UUID priceListId, BatchUpsertPriceListItemsRequest req) {
    getPriceList(ctx, priceListId);
    int upserted = 0;
    var errors = new java.util.ArrayList<String>();
    for (var r : req.items()) {
      try {
        upsertPriceListItem(ctx, priceListId, r);
        upserted++;
      } catch (Exception e) {
        errors.add("variantId=" + r.variantId() + ": " + e.getMessage());
      }
    }
    return new BatchUpsertResult(upserted, errors);
  }

  public List<PriceListItem> listPriceListItems(TenantContext ctx, UUID priceListId) {
    getPriceList(ctx, priceListId);
    return repo.findPriceListItems(ctx.tenantId(), priceListId);
  }

  // ── Price Resolution ──────────────────────────────────────────────────────

  public ResolvedPrice resolvePrice(ResolvePriceRequest req, TenantContext ctx) {
    UUID tenantId = ctx.tenantId();
    UUID variantId = UUID.fromString(req.variantId());
    BigDecimal qty = req.qty() != null ? req.qty() : BigDecimal.ONE;
    String channel =
        req.channel() != null
            ? req.channel().toUpperCase(java.util.Locale.ROOT)
            : PriceList.CHANNEL_ALL;

    PriceListItem baseItem =
        repo.resolveBasePrice(tenantId, variantId, channel, qty)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "PRICING_PRICE_NOT_FOUND",
                        "no active price configured for variant " + req.variantId()));

    BigDecimal unitPrice = baseItem.price();
    String promoApplied = null;
    var promos = repo.findActivePromotions(tenantId, variantId, channel, Instant.now());
    if (!promos.isEmpty()) {
      Promotion promo = promos.get(0);
      if (Promotion.TYPE_PERCENT.equals(promo.type())) {
        BigDecimal pct = promo.value().divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
        unitPrice =
            unitPrice.multiply(BigDecimal.ONE.subtract(pct)).setScale(2, RoundingMode.HALF_UP);
      } else {
        unitPrice = unitPrice.subtract(promo.value()).max(BigDecimal.ZERO);
      }
      promoApplied = promo.name();
    }

    String vatCode =
        repo.findProductVatCategory(tenantId, variantId)
            .map(ProductVatCategory::vatCode)
            .orElse(VatRate.T1);

    VatRate vatRate =
        repo.findVatRate(tenantId, vatCode)
            .orElse(
                new VatRate(
                    null,
                    tenantId,
                    VatRate.T1,
                    "Standard Rate",
                    new BigDecimal("0.20"),
                    false,
                    null,
                    Instant.now(),
                    null,
                    Instant.now()));

    BigDecimal vatAmount =
        vatRate.exempt()
            ? BigDecimal.ZERO
            : unitPrice.multiply(vatRate.rate()).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalWithVat = unitPrice.add(vatAmount);

    String currency = "GBP";
    var pl = repo.findPriceList(tenantId, baseItem.priceListId());
    if (pl.isPresent()) currency = pl.get().currency();

    return new ResolvedPrice(
        variantId,
        unitPrice,
        vatCode,
        vatRate.rate(),
        vatAmount,
        totalWithVat,
        currency,
        baseItem.priceListId(),
        promoApplied);
  }

  // ── Promotions ────────────────────────────────────────────────────────────

  public Promotion createPromotion(CreatePromotionRequest req, TenantContext ctx) {
    Promotion p =
        new Promotion(
            UUID.randomUUID(),
            ctx.tenantId(),
            req.storeId() != null ? UUID.fromString(req.storeId()) : null,
            req.name(),
            req.type().toUpperCase(java.util.Locale.ROOT),
            req.value(),
            req.minOrderAmount(),
            req.channel() != null
                ? req.channel().toUpperCase(java.util.Locale.ROOT)
                : Promotion.TYPE_FLAT,
            true,
            Instant.parse(req.startsAt()),
            req.endsAt() != null ? Instant.parse(req.endsAt()) : null,
            Instant.now());
    return repo.createPromotion(p, Events.promotionActivated(ctx.tenantId(), p.id()));
  }

  public List<Promotion> listActivePromotions(TenantContext ctx) {
    return repo.findAllActivePromotions(ctx.tenantId());
  }

  public PromotionItem addPromotionItem(
      TenantContext ctx, UUID promotionId, AddPromotionItemRequest req) {
    String scopeType = req.scopeType().toUpperCase(java.util.Locale.ROOT);
    UUID scopeId = req.scopeId() != null ? UUID.fromString(req.scopeId()) : null;
    PromotionItem pi =
        new PromotionItem(
            UUID.randomUUID(), ctx.tenantId(), promotionId, scopeType, scopeId, Instant.now());
    return repo.addPromotionItem(pi);
  }

  // ── Tax Transactions ──────────────────────────────────────────────────────

  public TaxTransaction recordTaxTransaction(RecordTaxTransactionRequest req, TenantContext ctx) {
    TaxTransaction tt =
        new TaxTransaction(
            UUID.randomUUID(),
            ctx.tenantId(),
            req.orderId(),
            req.orderLineId(),
            req.variantId(),
            req.storeId(),
            req.vatCode().toUpperCase(java.util.Locale.ROOT),
            req.vatRate(),
            req.netAmount(),
            req.vatAmount(),
            req.grossAmount(),
            req.exempt(),
            Instant.parse(req.taxPointDate()),
            req.invoiceRef(),
            Instant.now());
    return repo.recordTaxTransaction(tt);
  }

  public List<TaxTransaction> listTaxTransactionsByOrder(TenantContext ctx, UUID orderId) {
    return repo.findTaxTransactionsByOrder(ctx.tenantId(), orderId);
  }

  // ── MTD VAT Return ────────────────────────────────────────────────────────

  /**
   * Compute HMRC MTD VAT return boxes 1-9. Box 4 (input VAT on purchases) and boxes 7-9 remain zero
   * until purchase-svc is built (Gap #20).
   */
  public VatReturn computeVatReturn(TenantContext ctx, String fromStr, String toStr) {
    UUID tenantId = ctx.tenantId();
    Instant from = Instant.parse(fromStr);
    Instant to = Instant.parse(toStr);
    if (!from.isBefore(to))
      throw ApiException.badRequest("PRICING_INVALID_PERIOD", "from must be before to");

    BigDecimal box1 = repo.sumOutputVat(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box2 = BigDecimal.ZERO;
    BigDecimal box3 = box1.add(box2);
    BigDecimal box4 = BigDecimal.ZERO;
    BigDecimal box5 = box3.subtract(box4).abs();
    BigDecimal box6 = repo.sumNetSales(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box7 = BigDecimal.ZERO;
    BigDecimal box8 = BigDecimal.ZERO;
    BigDecimal box9 = BigDecimal.ZERO;

    return new VatReturn(box1, box2, box3, box4, box5, box6, box7, box8, box9, fromStr, toStr);
  }
}
