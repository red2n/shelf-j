package com.shelfj.pricing.service;

import com.shelfj.ids.Ids;
import com.shelfj.pricing.domain.Domain;
import com.shelfj.pricing.domain.Domain.BasketLine;
import com.shelfj.pricing.domain.Domain.CustomerVatStatus;
import com.shelfj.pricing.domain.Domain.PriceList;
import com.shelfj.pricing.domain.Domain.PriceListItem;
import com.shelfj.pricing.domain.Domain.PriceOverride;
import com.shelfj.pricing.domain.Domain.ProductVatCategory;
import com.shelfj.pricing.domain.Domain.Promotion;
import com.shelfj.pricing.domain.Domain.PromotionItem;
import com.shelfj.pricing.domain.Domain.ResolvedPrice;
import com.shelfj.pricing.domain.Domain.TaxGrouping;
import com.shelfj.pricing.domain.Domain.TaxSummary;
import com.shelfj.pricing.domain.Domain.TaxSummaryRow;
import com.shelfj.pricing.domain.Domain.TaxSummaryTotals;
import com.shelfj.pricing.domain.Domain.TaxTransaction;
import com.shelfj.pricing.domain.Domain.VatRate;
import com.shelfj.pricing.domain.Domain.VatReturn;
import com.shelfj.pricing.dto.Dtos.AddPromotionItemRequest;
import com.shelfj.pricing.dto.Dtos.AppliedPromotionResponse;
import com.shelfj.pricing.dto.Dtos.BatchUpsertPriceListItemsRequest;
import com.shelfj.pricing.dto.Dtos.BatchUpsertResult;
import com.shelfj.pricing.dto.Dtos.CreatePriceListRequest;
import com.shelfj.pricing.dto.Dtos.CreatePriceOverrideRequest;
import com.shelfj.pricing.dto.Dtos.CreatePromotionRequest;
import com.shelfj.pricing.dto.Dtos.CreateVatRateRequest;
import com.shelfj.pricing.dto.Dtos.QuoteBasketRequest;
import com.shelfj.pricing.dto.Dtos.QuoteBasketResponse;
import com.shelfj.pricing.dto.Dtos.QuoteLineResponse;
import com.shelfj.pricing.dto.Dtos.RecordTaxTransactionRequest;
import com.shelfj.pricing.dto.Dtos.ResolvePriceRequest;
import com.shelfj.pricing.dto.Dtos.SetActiveRequest;
import com.shelfj.pricing.dto.Dtos.UpsertCustomerVatStatusRequest;
import com.shelfj.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.shelfj.pricing.dto.Dtos.UpsertProductVatCategoryRequest;
import com.shelfj.pricing.repo.PricingRepository;
import com.shelfj.pricing.repo.TaxReportRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Cursor;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business logic for pricing-svc. Controllers call this; no HTTP types here. */
@ApplicationScoped
public class PricingService {

  @Inject PricingRepository repo;
  @Inject PromotionEngine engine;
  @Inject TaxReportRepository taxReportRepo;

  // ── VAT Rates ─────────────────────────────────────────────────────────────

  /**
   * Creates a VAT rate for the tenant.
   *
   * @param req the code, name, rate as a fraction (0.20 = 20%), exempt flag and effective date
   * @param ctx caller context; supplies the tenant
   * @return the created rate, its code upper-cased
   * @throws ApiException {@code PRICING_INVALID_RATE} (400) when the rate exceeds 1
   */
  public VatRate createVatRate(CreateVatRateRequest req, TenantContext ctx) {
    if (req.rate().compareTo(BigDecimal.ONE) > 0)
      throw ApiException.badRequest("PRICING_INVALID_RATE", "VAT rate must be between 0 and 1");
    VatRate r =
        new VatRate(
            Ids.newId(),
            ctx.tenantId(),
            req.code().toUpperCase(java.util.Locale.ROOT),
            req.name(),
            req.rate(),
            req.exempt(),
            req.description(),
            Parsing.instant(req.effectiveFrom(), "effectiveFrom"),
            null,
            Instant.now());
    return repo.createVatRate(r);
  }

  /**
   * Lists the tenant's VAT rates.
   *
   * @param ctx caller context; supplies the tenant
   * @return the configured rates
   */
  public List<VatRate> listVatRates(TenantContext ctx) {
    return repo.findVatRates(ctx.tenantId());
  }

  /**
   * Reads one VAT rate by code.
   *
   * @param ctx caller context; supplies the tenant
   * @param code the VAT code, matched case-insensitively
   * @return the rate
   * @throws ApiException {@code PRICING_VAT_CODE_NOT_FOUND} (404) when no such code exists
   */
  public VatRate getVatRate(TenantContext ctx, String code) {
    return repo.findVatRate(ctx.tenantId(), code.toUpperCase(java.util.Locale.ROOT))
        .orElseThrow(
            () ->
                ApiException.notFound("PRICING_VAT_CODE_NOT_FOUND", "VAT code not found: " + code));
  }

  /**
   * Updates a VAT rate in place.
   *
   * <p>Overwrites the rate rather than superseding it, so historical tax transactions already
   * recorded against this code keep the figures they were stamped with, while future ones use the
   * new value.
   *
   * @param ctx caller context; supplies the tenant
   * @param code the VAT code to update
   * @param req the new name, rate, exempt flag and optional effective date
   * @return the updated rate
   * @throws ApiException {@code PRICING_VAT_CODE_NOT_FOUND} (404) when no such code exists; {@code
   *     PRICING_INVALID_RATE} (400) when the rate exceeds 1
   */
  public VatRate updateVatRate(TenantContext ctx, String code, CreateVatRateRequest req) {
    VatRate existing = getVatRate(ctx, code);
    if (req.rate().compareTo(BigDecimal.ONE) > 0)
      throw ApiException.badRequest("PRICING_INVALID_RATE", "VAT rate must be between 0 and 1");
    Instant newEffectiveFrom =
        req.effectiveFrom() != null
            ? Parsing.instant(req.effectiveFrom(), "effectiveFrom")
            : existing.effectiveFrom();
    VatRate updated =
        new VatRate(
            existing.id(),
            ctx.tenantId(),
            code.toUpperCase(java.util.Locale.ROOT),
            req.name(),
            req.rate(),
            req.exempt(),
            req.description(),
            newEffectiveFrom,
            existing.effectiveTo(),
            existing.createdAt());
    return repo.updateVatRate(updated);
  }

  // ── Product VAT Categories ────────────────────────────────────────────────

  /**
   * Assigns a variant to a VAT code.
   *
   * <p>The code is checked against the tenant's own rates first, so a typo cannot leave a product
   * pointing at a band that does not exist and silently falling back to standard rate at checkout.
   *
   * @param req the variant and the VAT code to assign it
   * @param ctx caller context; supplies the tenant
   * @return the stored assignment
   * @throws ApiException {@code PRICING_VAT_CODE_NOT_FOUND} (404) when the code is not configured
   */
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
            Ids.newId(),
            ctx.tenantId(),
            UUID.fromString(req.variantId()),
            vatCode,
            Instant.now(),
            null,
            Instant.now());
    return repo.upsertProductVatCategory(pvc);
  }

  /**
   * Reads a variant's VAT assignment.
   *
   * <p>Unlike price resolution, which falls back to the standard rate, this reports the absence:
   * the admin screen needs to know a product was never categorised.
   *
   * @param ctx caller context; supplies the tenant
   * @param variantId the variant to look up
   * @return the assignment
   * @throws ApiException {@code PRICING_VAT_CATEGORY_NOT_FOUND} (404) when none is assigned
   */
  public ProductVatCategory getProductVatCategory(TenantContext ctx, UUID variantId) {
    return repo.findProductVatCategory(ctx.tenantId(), variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_VAT_CATEGORY_NOT_FOUND",
                    "no VAT category assigned for variant " + variantId));
  }

  // ── Customer VAT Status ───────────────────────────────────────────────────

  /**
   * Records a customer's VAT registration and reverse-charge eligibility.
   *
   * @param req the customer, VAT number, registration and reverse-charge flags, and country
   *     (defaulting to {@code GB})
   * @param ctx caller context; supplies the tenant
   * @return the stored status
   */
  public CustomerVatStatus upsertCustomerVatStatus(
      UpsertCustomerVatStatusRequest req, TenantContext ctx) {
    CustomerVatStatus cvs =
        new CustomerVatStatus(
            Ids.newId(),
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

  /**
   * Reads a customer's VAT status.
   *
   * @param ctx caller context; supplies the tenant
   * @param customerId the customer to look up
   * @return the stored status
   * @throws ApiException {@code PRICING_CUSTOMER_VAT_NOT_FOUND} (404) when none is recorded
   */
  public CustomerVatStatus getCustomerVatStatus(TenantContext ctx, UUID customerId) {
    return repo.findCustomerVatStatus(ctx.tenantId(), customerId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "PRICING_CUSTOMER_VAT_NOT_FOUND", "no VAT status for customer " + customerId));
  }

  // ── Price Lists ───────────────────────────────────────────────────────────

  /**
   * Creates a price list, active from the moment it is created.
   *
   * @param req the name, channel (defaulting to ALL), currency (defaulting to GBP) and effective
   *     window
   * @param ctx caller context; supplies the tenant
   * @return the created price list
   */
  public PriceList createPriceList(CreatePriceListRequest req, TenantContext ctx) {
    PriceList pl =
        new PriceList(
            Ids.newId(),
            ctx.tenantId(),
            req.name(),
            req.channel() != null ? req.channel() : PriceList.CHANNEL_ALL,
            req.currency() != null ? req.currency() : "GBP",
            Parsing.instant(req.effectiveFrom(), "effectiveFrom"),
            req.effectiveTo() != null ? Parsing.instant(req.effectiveTo(), "effectiveTo") : null,
            true,
            Instant.now());
    return repo.createPriceList(pl);
  }

  /**
   * Cursor-paginated price lists. The cursor wraps the last row's created_at|id keyset.
   *
   * @param ctx caller context; supplies the tenant
   * @param after cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page of price lists
   */
  public Cursor.Page<PriceList> listPriceLists(TenantContext ctx, String after, int limit) {
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<PriceList> rows =
        repo.findPriceLists(
            ctx.tenantId(),
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, pl -> pl.createdAt() + "|" + pl.id());
  }

  /**
   * Reads one price list.
   *
   * <p>Also the tenant-scoping guard the price-list-item methods call first.
   *
   * @param ctx caller context; supplies the tenant
   * @param id the price list to read
   * @return the price list
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when it does not exist in this tenant
   */
  public PriceList getPriceList(TenantContext ctx, UUID id) {
    return repo.findPriceList(ctx.tenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PRICING_LIST_NOT_FOUND", "price list not found: " + id));
  }

  // ── Price List Items ──────────────────────────────────────────────────────

  /**
   * Sets a variant's price on a price list, publishing {@code PriceChanged}.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list to write to
   * @param req the variant, price and optional minimum quantity (defaulting to 1)
   * @return the stored item
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant
   */
  public PriceListItem upsertPriceListItem(
      TenantContext ctx, UUID priceListId, UpsertPriceListItemRequest req) {
    getPriceList(ctx, priceListId);
    PriceListItem item =
        new PriceListItem(
            Ids.newId(),
            ctx.tenantId(),
            priceListId,
            UUID.fromString(req.variantId()),
            req.price(),
            req.minQty() != null ? req.minQty() : BigDecimal.ONE,
            Instant.now(),
            Instant.now());
    return repo.upsertPriceListItem(item, Events.priceChanged(ctx.tenantId(), priceListId));
  }

  /**
   * Sets many prices on one price list in a single call.
   *
   * <p>Deliberately not atomic: each item is attempted independently and a failure is collected
   * rather than rolled back, so one bad row in a bulk price upload does not discard the rest. The
   * caller must read {@code errors} — a partial success still returns 200.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list to write to
   * @param req the items to upsert
   * @return how many succeeded, and one message per failure
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant
   */
  public BatchUpsertResult batchUpsertPriceListItems(
      TenantContext ctx, UUID priceListId, BatchUpsertPriceListItemsRequest req) {
    getPriceList(ctx, priceListId);
    int upserted = 0;
    var errors = new java.util.ArrayList<String>();
    for (var r : req.items()) {
      try {
        com.shelfj.web.Validations.validate(r);
        upsertPriceListItem(ctx, priceListId, r);
        upserted++;
      } catch (Exception e) {
        errors.add("variantId=" + r.variantId() + ": " + e.getMessage());
      }
    }
    return new BatchUpsertResult(upserted, errors);
  }

  /**
   * Lists the priced variants on one price list.
   *
   * @param ctx caller context; supplies the tenant
   * @param priceListId the price list whose items to list
   * @return the items
   * @throws ApiException {@code PRICING_LIST_NOT_FOUND} (404) when the price list does not exist in
   *     this tenant
   */
  public List<PriceListItem> listPriceListItems(TenantContext ctx, UUID priceListId) {
    getPriceList(ctx, priceListId);
    return repo.findPriceListItems(ctx.tenantId(), priceListId);
  }

  // ── Price Resolution ──────────────────────────────────────────────────────

  /**
   * Prices one variant for a product page: base price, item-level promotions, and VAT.
   *
   * <p>Basket-level and coupon promotions are excluded on purpose — a spend-threshold price shown
   * against a single item advertises a total the shopper will not be charged, and a coupon they
   * have not presented is not theirs yet. {@link #quoteBasket} is where those can be tested.
   *
   * <p>A variant with no VAT category falls back to the standard rate rather than failing.
   *
   * @param req the variant, optional quantity (defaulting to 1), channel and store
   * @param ctx caller context; supplies the tenant
   * @return the resolved unit price with its VAT code, rate, amount and gross
   * @throws ApiException {@code PRICING_PRICE_NOT_FOUND} (404) when no active price is configured
   *     for the variant
   */
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

    // A single-variant quote runs the same engine as the basket path, on a basket of one, with
    // the basket-level promotions filtered out. They are excluded deliberately rather than for
    // convenience: this endpoint answers "what does this item cost" for a product page, and
    // showing a spend-threshold price against one item advertises a total the shopper will not be
    // charged. The checkout path calls quoteBasket, where the threshold can actually be tested.
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank()
            ? null
            : Parsing.uuid(req.storeId(), "storeId");
    List<Promotion> candidates =
        repo.findCandidatePromotions(tenantId, storeId, channel, Instant.now()).stream()
            .filter(p -> !p.isBasketLevel())
            // A coupon promotion is not applied to a browsing price: the customer has not
            // presented it, and this endpoint takes no codes.
            .filter(p -> !p.requiresCoupon())
            .toList();
    if (!candidates.isEmpty()) {
      var scopes =
          repo.findPromotionVariantScopes(
              tenantId, candidates.stream().map(Promotion::id).toList());
      var outcome =
          engine.apply(
              List.of(new BasketLine(variantId, qty, unitPrice)),
              candidates,
              scopes,
              List.of(),
              Map.of());
      BigDecimal off = outcome.totalDiscount();
      if (off.signum() > 0) {
        BigDecimal lineTotal = unitPrice.multiply(qty);
        unitPrice =
            lineTotal.subtract(off).max(BigDecimal.ZERO).divide(qty, 2, RoundingMode.HALF_UP);
        promoApplied =
            outcome.lineDiscounts().stream()
                .map(com.shelfj.pricing.domain.Domain.LineDiscount::promotionName)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" + "));
      }
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

  /**
   * Resolves many lines in one call. Each line is still resolved independently (same DB reads as
   * {@link #resolvePrice}), but collapsing this into one service call removes the per-line HTTP
   * round trip (and circuit-breaker/retry overhead) a caller like order-svc's checkout would
   * otherwise pay once per order line.
   *
   * <p>Independent resolution also means this cannot see the basket: it applies no basket-level or
   * coupon promotion, exactly as {@link #resolvePrice} does not.
   *
   * @param reqs the lines to price
   * @param ctx caller context; supplies the tenant
   * @return one resolved price per request, in the order supplied
   * @throws ApiException {@code PRICING_PRICE_NOT_FOUND} (404) as soon as any line has no active
   *     price — the whole call fails rather than returning a partial list
   */
  public java.util.List<ResolvedPrice> resolvePrices(
      java.util.List<ResolvePriceRequest> reqs, TenantContext ctx) {
    return reqs.stream().map(r -> resolvePrice(r, ctx)).toList();
  }

  // ── Basket quoting ────────────────────────────────────────────────────────

  /**
   * One basket line's share of the discount the engine computed for its variant.
   *
   * <p>Split by line value, with the variant's last line taking the remainder, so the shares sum to
   * exactly the engine's figure. A variant appearing on a single line — the ordinary case — takes
   * the whole thing and this behaves exactly as it did before.
   *
   * @param variantId the line's variant
   * @param index this line's position in the basket
   * @param lineTotal this line's value before any discount
   * @param perVariantDiscount the engine's total discount per variant
   * @param variantLineValue total value of all lines carrying each variant
   * @param lastLineOfVariant index of the final line carrying each variant
   * @param taken running total already apportioned per variant; updated here
   * @return this line's share
   */
  private static BigDecimal shareOfVariantDiscount(
      UUID variantId,
      int index,
      BigDecimal lineTotal,
      Map<UUID, BigDecimal> perVariantDiscount,
      Map<UUID, BigDecimal> variantLineValue,
      Map<UUID, Integer> lastLineOfVariant,
      Map<UUID, BigDecimal> taken) {
    BigDecimal variantDiscount = perVariantDiscount.getOrDefault(variantId, BigDecimal.ZERO);
    if (variantDiscount.signum() == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal variantValue = variantLineValue.getOrDefault(variantId, BigDecimal.ZERO);
    boolean last = Integer.valueOf(index).equals(lastLineOfVariant.get(variantId));
    if (last || variantValue.signum() == 0) {
      // The remainder, so rounding never loses or invents a penny. A zero-value variant cannot be
      // split by value at all, so its whole discount lands on the last line.
      return variantDiscount.subtract(taken.getOrDefault(variantId, BigDecimal.ZERO));
    }
    BigDecimal share =
        variantDiscount.multiply(lineTotal).divide(variantValue, 2, RoundingMode.HALF_UP);
    taken.merge(variantId, share, BigDecimal::add);
    return share;
  }

  /**
   * Prices a whole basket, promotions and VAT included.
   *
   * <p>This is the method the old engine could not have had. {@code resolvePrices} was {@code
   * lines.stream().map(resolvePrice)} — each line priced in isolation — so a rule that needed to
   * see the order total had nowhere to stand. Spend thresholds, basket percentages and
   * buy-one-get-one were not merely unimplemented; there was no object for them to be about.
   *
   * <p>VAT is computed per line on the discounted amount, and the basket-level discount is
   * apportioned across the lines by value before that happens — otherwise a £10-off-the-basket
   * promotion would be VAT-free money, which it is not. Apportionment is by value rather than
   * evenly because lines can sit at different rates, and the zero-rated line must not absorb a
   * share of relief that belongs to the standard-rated one.
   *
   * @param req the lines, channel, store, customer and any coupon codes the customer presented;
   *     codes that do not apply come back in {@code rejectedCoupons} with a reason rather than
   *     being silently dropped
   * @param ctx caller context; supplies the tenant
   * @return the priced lines with subtotal, discounts, VAT, total and the promotions applied
   * @throws ApiException {@code PRICING_INVALID_QTY} (400) when a line's quantity is not positive;
   *     {@code PRICING_PRICE_NOT_FOUND} (404) when a variant has no active price
   */
  public QuoteBasketResponse quoteBasket(QuoteBasketRequest req, TenantContext ctx) {
    UUID tenantId = ctx.tenantId();
    String channel =
        req.channel() != null
            ? req.channel().toUpperCase(java.util.Locale.ROOT)
            : PriceList.CHANNEL_ALL;
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank()
            ? null
            : Parsing.uuid(req.storeId(), "storeId");
    UUID customerId =
        req.customerId() == null || req.customerId().isBlank()
            ? null
            : Parsing.uuid(req.customerId(), "customerId");

    // 1. Base prices, one resolution per line.
    List<BasketLine> basket = new java.util.ArrayList<>();
    List<String> vatCodes = new java.util.ArrayList<>();
    String currency = null;
    for (var l : req.lines()) {
      UUID variantId = Parsing.uuid(l.variantId(), "variantId");
      BigDecimal qty = l.qty() != null ? l.qty() : BigDecimal.ONE;
      if (qty.signum() <= 0)
        throw ApiException.badRequest(
            "PRICING_INVALID_QTY", "qty must be greater than zero for variant " + l.variantId());
      var baseItem =
          repo.resolveBasePrice(tenantId, variantId, channel, qty)
              .orElseThrow(
                  () ->
                      ApiException.notFound(
                          "PRICING_PRICE_NOT_FOUND",
                          "no active price configured for variant " + l.variantId()));
      basket.add(new BasketLine(variantId, qty, baseItem.price()));
      vatCodes.add(
          repo.findProductVatCategory(tenantId, variantId)
              .map(ProductVatCategory::vatCode)
              .orElse(VatRate.T1));
      if (currency == null)
        currency =
            repo.findPriceList(tenantId, baseItem.priceListId())
                .map(PriceList::currency)
                .orElse(null);
    }

    // 2. Promotions, over the whole basket.
    List<Promotion> candidates =
        repo.findCandidatePromotions(tenantId, storeId, channel, Instant.now());
    var scopes =
        repo.findPromotionVariantScopes(tenantId, candidates.stream().map(Promotion::id).toList());
    var exhausted = repo.findExhaustedPromotions(tenantId, candidates, customerId);
    var outcome = engine.apply(basket, candidates, scopes, req.couponCodes(), exhausted);

    // 3. Fold the line discounts back onto their lines.
    //
    // Both BasketLine and LineDiscount are keyed by variantId, so the engine cannot tell two basket
    // lines of the same variant apart and returns one combined figure for them. Applying that
    // figure to each line — which is what getOrDefault(variantId) does — charged the discount once
    // per line: the response's own lines then contradicted its subtotal and totalDiscount, and
    // order-svc, which derives the stored unit price from lineTotal minus discount, undercharged.
    //
    // The variant's discount is therefore split across its lines by value, with the last line of
    // that variant taking the rounding remainder — the same apportionment this method already uses
    // for the basket discount below, and for the same reason: the parts must sum to exactly the
    // whole rather than a penny either side.
    Map<UUID, BigDecimal> perLineDiscount = new java.util.LinkedHashMap<>();
    for (var d : outcome.lineDiscounts())
      perLineDiscount.merge(d.variantId(), d.amount(), BigDecimal::add);

    Map<UUID, BigDecimal> variantLineValue = new java.util.LinkedHashMap<>();
    Map<UUID, Integer> lastLineOfVariant = new java.util.LinkedHashMap<>();
    for (int i = 0; i < basket.size(); i++) {
      BasketLine b = basket.get(i);
      variantLineValue.merge(
          b.variantId(),
          b.unitPrice().multiply(b.qty()).setScale(2, RoundingMode.HALF_UP),
          BigDecimal::add);
      lastLineOfVariant.put(b.variantId(), i);
    }
    Map<UUID, BigDecimal> variantDiscountTaken = new java.util.LinkedHashMap<>();

    BigDecimal subtotal =
        basket.stream()
            .map(b -> b.unitPrice().multiply(b.qty()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    BigDecimal basketDiscount =
        outcome.basketDiscounts().stream()
            .map(com.shelfj.pricing.domain.Domain.LineDiscount::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal afterLine =
        subtotal.subtract(
            perLineDiscount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));

    // 4. Per-line VAT, on each line's share of what is left.
    List<QuoteLineResponse> lineResponses = new java.util.ArrayList<>();
    BigDecimal vatTotal = BigDecimal.ZERO;
    BigDecimal apportioned = BigDecimal.ZERO;
    for (int i = 0; i < basket.size(); i++) {
      BasketLine b = basket.get(i);
      BigDecimal lineTotal = b.unitPrice().multiply(b.qty()).setScale(2, RoundingMode.HALF_UP);
      BigDecimal lineDisc =
          shareOfVariantDiscount(
                  b.variantId(),
                  i,
                  lineTotal,
                  perLineDiscount,
                  variantLineValue,
                  lastLineOfVariant,
                  variantDiscountTaken)
              .min(lineTotal);
      BigDecimal net = lineTotal.subtract(lineDisc);

      // The basket discount is shared by value. The last line takes the rounding remainder, so
      // the apportioned parts always sum to exactly the discount rather than a penny either side.
      BigDecimal share;
      if (basketDiscount.signum() == 0 || afterLine.signum() <= 0) {
        share = BigDecimal.ZERO;
      } else if (i == basket.size() - 1) {
        share = basketDiscount.subtract(apportioned);
      } else {
        share = basketDiscount.multiply(net).divide(afterLine, 2, RoundingMode.HALF_UP);
        apportioned = apportioned.add(share);
      }
      net = net.subtract(share).max(BigDecimal.ZERO);

      VatRate rate = vatRateFor(tenantId, vatCodes.get(i));
      BigDecimal vat =
          rate.exempt()
              ? BigDecimal.ZERO
              : net.multiply(rate.rate()).setScale(2, RoundingMode.HALF_UP);
      vatTotal = vatTotal.add(vat);

      lineResponses.add(
          new QuoteLineResponse(
              b.variantId(),
              b.qty(),
              b.unitPrice(),
              lineTotal,
              lineDisc,
              net,
              vat,
              vatCodes.get(i)));
    }

    BigDecimal totalDiscount = outcome.totalDiscount();
    BigDecimal total = subtotal.subtract(totalDiscount).add(vatTotal);

    List<AppliedPromotionResponse> appliedResponses = new java.util.ArrayList<>();
    for (var d :
        java.util.stream.Stream.concat(
                outcome.lineDiscounts().stream(), outcome.basketDiscounts().stream())
            .toList()) {
      appliedResponses.add(
          new AppliedPromotionResponse(
              d.promotionId(), d.promotionName(), d.variantId(), d.amount()));
    }

    return new QuoteBasketResponse(
        lineResponses,
        subtotal,
        totalDiscount,
        basketDiscount,
        vatTotal,
        total,
        currency != null ? currency : "GBP",
        appliedResponses,
        outcome.rejectedCoupons());
  }

  /**
   * The tenant's rate for a VAT code, falling back to the standard rate the same way resolvePrice
   * does.
   */
  private VatRate vatRateFor(UUID tenantId, String vatCode) {
    return repo.findVatRate(tenantId, vatCode)
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
  }

  /**
   * Records that the promotions on a quote were used by an order.
   *
   * <p>Separate from quoting on purpose: a basket is quoted many times as a shopper adds items, and
   * a coupon must not be spent by looking at it. Only the checkout calls this, once the order
   * exists to attribute the redemption to.
   *
   * @param ctx caller context; supplies the tenant
   * @param orderId the order the redemptions are attributed to
   * @param customerId the redeeming customer, or {@code null} for a guest
   * @param applied the promotions the quote applied, summed per promotion here
   * @param currency the order's currency
   * @return how many redemptions this call actually recorded; a replay records none
   */
  public int recordRedemptions(
      TenantContext ctx,
      UUID orderId,
      UUID customerId,
      List<AppliedPromotionResponse> applied,
      String currency) {
    Map<UUID, BigDecimal> perPromotion = new java.util.LinkedHashMap<>();
    for (var a : applied) perPromotion.merge(a.promotionId(), a.amount(), BigDecimal::add);
    int recorded = 0;
    for (var e : perPromotion.entrySet()) {
      if (repo.recordRedemption(
          ctx.tenantId(), e.getKey(), orderId, customerId, e.getValue(), currency)) recorded++;
    }
    return recorded;
  }

  // ── Promotions ────────────────────────────────────────────────────────────

  /**
   * Creates a promotion, validating the shape its type requires.
   *
   * <p>The database enforces the same rules with CHECK constraints, deliberately: a half-configured
   * BOGO is a promotion that silently discounts nothing, which is the failure mode this whole
   * rebuild exists to end, and it must not be reachable however the row is written. What this adds
   * is the message — a constraint violation tells a caller only that something was wrong.
   *
   * @param req the promotion's type, value, window, scope-affecting settings and optional coupon
   * @param ctx caller context; supplies the tenant
   * @return the created promotion, active immediately
   * @throws ApiException {@code PRICING_INVALID_PROMOTION_TYPE}, {@code PRICING_INCOMPLETE_BOGO},
   *     {@code PRICING_INVALID_PROMOTION_SHAPE}, {@code PRICING_MISSING_THRESHOLD} or {@code
   *     PRICING_INVALID_PERCENT} (all 400) when the shape does not match the type
   */
  public Promotion createPromotion(CreatePromotionRequest req, TenantContext ctx) {
    String type = req.type().toUpperCase(java.util.Locale.ROOT);
    validatePromotionShape(type, req);

    Promotion p =
        new Promotion(
            Ids.newId(),
            ctx.tenantId(),
            req.storeId() != null ? UUID.fromString(req.storeId()) : null,
            req.name(),
            type,
            req.value(),
            req.minOrderAmount(),
            req.channel() != null
                ? req.channel().toUpperCase(java.util.Locale.ROOT)
                : PriceList.CHANNEL_ALL,
            true,
            Parsing.instant(req.startsAt(), "startsAt"),
            req.endsAt() != null ? Parsing.instant(req.endsAt(), "endsAt") : null,
            Instant.now(),
            req.priority() != null ? req.priority() : 100,
            Boolean.TRUE.equals(req.exclusive()),
            req.couponCode() == null || req.couponCode().isBlank() ? null : req.couponCode().trim(),
            req.maxRedemptions(),
            req.maxPerCustomer(),
            req.buyQty(),
            req.getQty(),
            req.getDiscountPct());
    return repo.createPromotion(p, Events.promotionActivated(ctx.tenantId(), p.id()));
  }

  private static final java.util.Set<String> PROMOTION_TYPES =
      java.util.Set.of(
          Promotion.TYPE_PERCENT,
          Promotion.TYPE_FLAT,
          Promotion.TYPE_BASKET_PERCENT,
          Promotion.TYPE_BASKET_FLAT,
          Promotion.TYPE_SPEND_THRESHOLD,
          Promotion.TYPE_BOGO);

  private static void validatePromotionShape(String type, CreatePromotionRequest req) {
    if (!PROMOTION_TYPES.contains(type))
      throw ApiException.badRequest(
          "PRICING_INVALID_PROMOTION_TYPE",
          "type must be one of " + PROMOTION_TYPES + " — got: " + type);

    boolean bogo = Promotion.TYPE_BOGO.equals(type);
    if (bogo) {
      if (req.buyQty() == null || req.getQty() == null || req.getDiscountPct() == null)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_BOGO",
            "BOGO requires buyQty, getQty and getDiscountPct — a partial one would apply to every"
                + " basket and discount nothing");
      if (req.buyQty().signum() <= 0 || req.getQty().signum() <= 0)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_BOGO", "buyQty and getQty must both be greater than zero");
      if (req.getDiscountPct().signum() <= 0
          || req.getDiscountPct().compareTo(new BigDecimal("100")) > 0)
        throw ApiException.badRequest(
            "PRICING_INCOMPLETE_BOGO", "getDiscountPct must be between 0 and 100 (100 = free)");
    } else if (req.buyQty() != null || req.getQty() != null || req.getDiscountPct() != null) {
      throw ApiException.badRequest(
          "PRICING_INVALID_PROMOTION_SHAPE",
          "buyQty / getQty / getDiscountPct belong to a BOGO — got type " + type);
    }

    if (Promotion.TYPE_SPEND_THRESHOLD.equals(type) && req.minOrderAmount() == null)
      throw ApiException.badRequest(
          "PRICING_MISSING_THRESHOLD",
          "SPEND_THRESHOLD requires minOrderAmount — without one it discounts every basket");

    boolean percent =
        Promotion.TYPE_PERCENT.equals(type) || Promotion.TYPE_BASKET_PERCENT.equals(type);
    if (percent && req.value().compareTo(new BigDecimal("100")) > 0)
      throw ApiException.badRequest(
          "PRICING_INVALID_PERCENT",
          "a percentage promotion cannot exceed 100 — got " + req.value());
  }

  /**
   * Stops a promotion or a price list, or starts it again (SJ-D33).
   *
   * <p><code>active</code> has existed on both tables since V1 and the engine has always filtered
   * on it. Nothing ever wrote it, so a promotion created with no end date ran forever and could
   * only be stopped by reaching into the database. A discount nobody can switch off is the most
   * expensive version of the "declared column with no writer" shape this branch keeps finding.
   *
   * <p>A reason is required in both directions, not just for stopping. Turning a promotion back on
   * is the change more likely to be questioned later, and a trail that records why something was
   * stopped but not why it was restarted answers the easier half of the question.
   *
   * <p>Already-in-that-state is a 409 rather than a silent success: two people stopping the same
   * runaway promotion should not both be told they did it, and the trail must not gain a row for a
   * switch that did not move.
   *
   * @param ctx caller context; supplies the tenant and the user recorded against the change
   * @param subjectType {@code PROMOTION} or {@code PRICE_LIST}
   * @param id the promotion or price list to switch
   * @param active {@code true} to start it, {@code false} to stop it
   * @param req the reason, required in both directions
   * @return the recorded status change
   * @throws ApiException 400 if no reason is given; 404 if there is no such subject for this
   *     tenant; 409 {@code PRICING_ALREADY_IN_STATE} if it is already on or off as requested
   */
  public Domain.StatusChange setActive(
      TenantContext ctx, String subjectType, UUID id, boolean active, SetActiveRequest req) {
    UUID tenantId = ctx.requireTenantId();
    String table = Domain.StatusChange.PROMOTION.equals(subjectType) ? "promotions" : "price_lists";
    String reason = req == null || req.reason() == null ? null : req.reason().trim();
    if (reason == null || reason.isEmpty())
      throw ApiException.badRequest(
          "PRICING_REASON_REQUIRED",
          "say why — a promotion that stopped with no recorded reason is a discount that vanished"
              + " from the shop floor with nobody accountable");

    Boolean current = repo.findActive(table, tenantId, id);
    if (current == null)
      throw ApiException.notFound(
          "PRICING_SUBJECT_NOT_FOUND",
          subjectType.toLowerCase(java.util.Locale.ROOT) + " not found: " + id);

    Domain.StatusChange change =
        new Domain.StatusChange(
            Ids.newId(), tenantId, subjectType, id, active, reason, ctx.userId(), Instant.now());
    if (!repo.setActive(table, change))
      throw ApiException.conflict(
          "PRICING_ALREADY_IN_STATE",
          "already " + (active ? "active" : "inactive") + " — nothing to change");
    return change;
  }

  /**
   * The on/off history for one promotion or price list.
   *
   * @param ctx caller context; supplies the tenant
   * @param subjectType {@code PROMOTION} or {@code PRICE_LIST}
   * @param id the promotion or price list whose history to read
   * @return the recorded status changes
   */
  public List<Domain.StatusChange> statusChanges(TenantContext ctx, String subjectType, UUID id) {
    return repo.findStatusChanges(subjectType, ctx.requireTenantId(), id);
  }

  /**
   * Every currently active promotion in the tenant.
   *
   * <p>Filters on the {@code active} flag only — a promotion whose window has not opened, or has
   * closed, still appears here. The engine applies the date test when quoting.
   *
   * @param ctx caller context; supplies the tenant
   * @return the active promotions
   */
  public List<Promotion> listActivePromotions(TenantContext ctx) {
    return repo.findAllActivePromotions(ctx.tenantId());
  }

  /**
   * Scopes a promotion to a variant, or to everything.
   *
   * <p><b>CATEGORY is rejected, and that is a change in behaviour rather than a restriction.</b> It
   * has been accepted since V1 — it is in the CHECK constraint, the domain constants, the request
   * schema and the API guide — and the matching query never handled it, so a category promotion was
   * stored and never fired. Rejecting it says so at the point the mistake is made. Honouring it
   * needs the variant→category mapping, which product-svc owns and publishes on no topic; that
   * projection is the same one sales-by-category is blocked on, and is written up in
   * docs/reporting-api-gap-analysis.md.
   *
   * @param ctx caller context; supplies the tenant
   * @param promotionId the promotion to scope
   * @param req the scope type ({@code VARIANT} or {@code ALL}) and, for VARIANT, the variant id
   * @return the stored scope row
   * @throws ApiException {@code PRICING_CATEGORY_SCOPE_UNSUPPORTED} or {@code
   *     PRICING_INVALID_SCOPE} (both 400) when the scope is a category, unknown, or a VARIANT scope
   *     with no variant named
   */
  public PromotionItem addPromotionItem(
      TenantContext ctx, UUID promotionId, AddPromotionItemRequest req) {
    String scopeType = req.scopeType().toUpperCase(java.util.Locale.ROOT);
    if (PromotionItem.SCOPE_CATEGORY.equals(scopeType))
      throw ApiException.badRequest(
          "PRICING_CATEGORY_SCOPE_UNSUPPORTED",
          "category-scoped promotions cannot be honoured yet: pricing-svc has no variant→category"
              + " mapping, because product-svc publishes no catalogue event. Scope to VARIANT or"
              + " ALL. Previously such a promotion was accepted and silently never applied.");
    if (!PromotionItem.SCOPE_VARIANT.equals(scopeType)
        && !PromotionItem.SCOPE_ALL.equals(scopeType))
      throw ApiException.badRequest(
          "PRICING_INVALID_SCOPE", "scopeType must be VARIANT or ALL — got: " + scopeType);
    if (PromotionItem.SCOPE_VARIANT.equals(scopeType)
        && (req.scopeId() == null || req.scopeId().isBlank()))
      throw ApiException.badRequest(
          "PRICING_INVALID_SCOPE", "a VARIANT scope needs a scopeId naming the variant");
    UUID scopeId = req.scopeId() != null ? UUID.fromString(req.scopeId()) : null;
    PromotionItem pi =
        new PromotionItem(
            Ids.newId(), ctx.tenantId(), promotionId, scopeType, scopeId, Instant.now());
    return repo.addPromotionItem(pi);
  }

  // ── Tax Transactions ──────────────────────────────────────────────────────

  /**
   * Records one line's tax position against an order, for the VAT return and tax summary.
   *
   * <p>Append-only: the figures are stamped as they stood at the tax point, so a later rate change
   * does not rewrite what was charged.
   *
   * @param req the order, line, variant, store, VAT code and rate, net/VAT/gross amounts, exempt
   *     flag, tax point and invoice reference
   * @param ctx caller context; supplies the tenant
   * @return the recorded transaction
   */
  public TaxTransaction recordTaxTransaction(RecordTaxTransactionRequest req, TenantContext ctx) {
    TaxTransaction tt =
        new TaxTransaction(
            Ids.newId(),
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
            Parsing.instant(req.taxPointDate(), "taxPointDate"),
            req.invoiceRef(),
            Instant.now());
    return repo.recordTaxTransaction(tt);
  }

  /**
   * The tax lines recorded against one order.
   *
   * @param ctx caller context; supplies the tenant
   * @param orderId the order whose tax lines to read
   * @return the transactions, empty when none were recorded
   */
  public List<TaxTransaction> listTaxTransactionsByOrder(TenantContext ctx, UUID orderId) {
    return repo.findTaxTransactionsByOrder(ctx.tenantId(), orderId);
  }

  // ── MTD VAT Return ────────────────────────────────────────────────────────

  // ── Gap #41: Price overrides ──────────────────────────────────────────────

  /**
   * Records a manual price override against a store, and optionally an order.
   *
   * <p>An audit row, not a price change: it captures that someone sold at a different figure and
   * why. Nothing here alters the price list the override departed from.
   *
   * @param ctx caller context; supplies the tenant
   * @param req the variant, store, original and override prices, reason and who authorised it
   * @return the recorded override
   */
  public PriceOverride createPriceOverride(TenantContext ctx, CreatePriceOverrideRequest req) {
    UUID tenantId = ctx.requireTenantId();
    var override =
        new PriceOverride(
            Ids.newId(),
            tenantId,
            req.orderId() != null ? UUID.fromString(req.orderId()) : null,
            UUID.fromString(req.variantId()),
            UUID.fromString(req.storeId()),
            req.originalPrice(),
            req.overridePrice(),
            req.overrideReason(),
            req.overriddenBy() != null ? UUID.fromString(req.overriddenBy()) : null,
            java.time.Instant.now());
    return repo.insertPriceOverride(override);
  }

  /**
   * Cursor-paginated price overrides (admin audit log).
   *
   * @param ctx caller context; supplies the tenant
   * @param storeIdStr restrict to one store, or {@code null} for all
   * @param variantIdStr restrict to one variant, or {@code null} for all
   * @param after cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page of overrides
   */
  public Cursor.Page<PriceOverride> listPriceOverrides(
      TenantContext ctx, String storeIdStr, String variantIdStr, String after, int limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = storeIdStr != null ? UUID.fromString(storeIdStr) : null;
    UUID variantId = variantIdStr != null ? UUID.fromString(variantIdStr) : null;
    Cursor.CreatedAtId key = Cursor.decodeCreatedAtId(after);
    List<PriceOverride> rows =
        repo.listPriceOverrides(
            tenantId,
            storeId,
            variantId,
            key == null ? null : key.createdAt(),
            key == null ? null : key.id(),
            limit + 1);
    return Cursor.page(rows, limit, o -> o.createdAt() + "|" + o.id());
  }

  /**
   * The tax summary report: the working behind the VAT return's single figures.
   *
   * <p>Box 1 and Box 6 are each one number computed over the same rows this groups. An accountant
   * filing the return needs to see which rate bands, sites or months make them up — both to sanity
   * check the figure and to explain it if HMRC asks.
   *
   * <p>Totals are folded from the returned rows rather than queried separately, so the summary can
   * never disagree with its own detail. They also expose one thing the return hides: Box 1 filters
   * to non-exempt supplies, so VAT sitting on a row marked exempt vanishes from it silently. Here
   * that shows up as {@code vatAmount} differing from {@code outputVat}.
   *
   * @param ctx caller context; tenant comes from the verified JWT, never the request
   * @param fromStr inclusive ISO-8601 lower bound on the tax point
   * @param toStr exclusive ISO-8601 upper bound
   * @param storeIdStr restrict to one store, or null/blank for all
   * @param groupByStr CODE, STORE or MONTH; defaults to CODE
   * @return the grouped rows with folded totals and the period they cover
   * @throws ApiException 400 when the period is malformed or not strictly increasing, or {@code
   *     groupBy} is not one of the three groupings
   */
  public TaxSummary taxSummary(
      TenantContext ctx, String fromStr, String toStr, String storeIdStr, String groupByStr) {
    Instant from = Parsing.instant(fromStr, "from");
    Instant to = Parsing.instant(toStr, "to");
    if (!from.isBefore(to))
      throw ApiException.badRequest("PRICING_INVALID_PERIOD", "from must be before to");

    List<TaxSummaryRow> rows =
        taxReportRepo.aggregate(
            ctx.tenantId(),
            Parsing.optionalUuid(storeIdStr, "storeId"),
            from,
            to,
            grouping(groupByStr));

    BigDecimal net = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    BigDecimal outputVat = BigDecimal.ZERO;
    BigDecimal gross = BigDecimal.ZERO;
    long transactions = 0;
    for (TaxSummaryRow r : rows) {
      net = net.add(r.netAmount());
      vat = vat.add(r.vatAmount());
      if (!r.exempt()) outputVat = outputVat.add(r.vatAmount());
      gross = gross.add(r.grossAmount());
      transactions += r.transactions();
    }

    return new TaxSummary(
        rows,
        new TaxSummaryTotals(
            net.setScale(2, RoundingMode.HALF_UP),
            vat.setScale(2, RoundingMode.HALF_UP),
            outputVat.setScale(2, RoundingMode.HALF_UP),
            gross.setScale(2, RoundingMode.HALF_UP),
            transactions),
        fromStr,
        toStr);
  }

  /** Defaults to CODE — "which rate bands is my VAT made of" is what this is opened for. */
  private static TaxGrouping grouping(String raw) {
    if (raw == null || raw.isBlank()) return TaxGrouping.CODE;
    try {
      return TaxGrouping.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400,
          "PRICING_INVALID_GROUPING",
          "groupBy must be CODE, STORE or MONTH — got: " + raw,
          List.of(),
          e);
    }
  }

  /**
   * Computes HMRC MTD VAT return boxes 1-9 for a period.
   *
   * <p>Box 4 (input VAT on purchases) and boxes 7-9 are still zero: they need purchase-side figures
   * this service does not yet consume (Gap #20), so a return filed from this is incomplete for a
   * business that reclaims input VAT.
   *
   * @param ctx caller context; supplies the tenant
   * @param fromStr inclusive ISO-8601 lower bound on the tax point
   * @param toStr exclusive ISO-8601 upper bound
   * @return the nine box figures with the period they cover
   * @throws ApiException {@code PRICING_INVALID_PERIOD} (400) when the period is malformed or not
   *     strictly increasing
   */
  public VatReturn computeVatReturn(TenantContext ctx, String fromStr, String toStr) {
    UUID tenantId = ctx.tenantId();
    Instant from = Parsing.instant(fromStr, "from");
    Instant to = Parsing.instant(toStr, "to");
    if (!from.isBefore(to))
      throw ApiException.badRequest("PRICING_INVALID_PERIOD", "from must be before to");

    BigDecimal box1 = repo.sumOutputVat(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box2 = BigDecimal.ZERO;
    BigDecimal box3 = box1.add(box2);
    // SJ-D39: box 4 and box 7 from the supplier invoices purchase-svc captured, by invoice date.
    BigDecimal box4 = repo.sumInputVat(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box5 = box3.subtract(box4).abs();
    BigDecimal box6 = repo.sumNetSales(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box7 = repo.sumNetPurchases(tenantId, from, to).setScale(2, RoundingMode.HALF_UP);
    BigDecimal box8 = BigDecimal.ZERO;
    BigDecimal box9 = BigDecimal.ZERO;

    return new VatReturn(box1, box2, box3, box4, box5, box6, box7, box8, box9, fromStr, toStr);
  }
}
