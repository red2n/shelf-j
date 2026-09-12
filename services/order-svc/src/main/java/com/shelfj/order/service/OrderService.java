package com.shelfj.order.service;

import com.shelfj.ids.Ids;
import com.shelfj.order.domain.Domain;
import com.shelfj.order.domain.Domain.AgeVerification;
import com.shelfj.order.domain.Domain.AgeVerificationSummary;
import com.shelfj.order.domain.Domain.ExceptionGrouping;
import com.shelfj.order.domain.Domain.ExceptionRow;
import com.shelfj.order.domain.Domain.GiftCard;
import com.shelfj.order.domain.Domain.GiftCardTransaction;
import com.shelfj.order.domain.Domain.Layaway;
import com.shelfj.order.domain.Domain.LayawayDeposit;
import com.shelfj.order.domain.Domain.LayawayItem;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.domain.Domain.OrderDiscount;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.OrderReceipt;
import com.shelfj.order.domain.Domain.OrderStatusHistory;
import com.shelfj.order.domain.Domain.PosLogEntry;
import com.shelfj.order.domain.Domain.PosVoidLog;
import com.shelfj.order.domain.Domain.Return;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.order.domain.Domain.SalesByHourRow;
import com.shelfj.order.domain.Domain.SalesByStaffRow;
import com.shelfj.order.domain.Domain.SpecialOrder;
import com.shelfj.order.domain.Domain.SpecialOrderItem;
import com.shelfj.order.dto.Dtos.AddDepositRequest;
import com.shelfj.order.dto.Dtos.CreateLayawayRequest;
import com.shelfj.order.dto.Dtos.CreateReturnRequest;
import com.shelfj.order.dto.Dtos.CreateSpecialOrderRequest;
import com.shelfj.order.dto.Dtos.GenerateReceiptRequest;
import com.shelfj.order.dto.Dtos.IssueGiftCardRequest;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
import com.shelfj.order.dto.Dtos.RecordAgeCheckRequest;
import com.shelfj.order.dto.Dtos.RedeemGiftCardRequest;
import com.shelfj.order.dto.Dtos.ReloadGiftCardRequest;
import com.shelfj.order.dto.Dtos.VoidRequest;
import com.shelfj.order.repo.OrderRepository;
import com.shelfj.service.StoreStatusRepository;
import com.shelfj.service.TenantStatusRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Business logic for order-svc. Thin resource → this service → repository. */
@ApplicationScoped
public class OrderService {

  private static final System.Logger LOG = System.getLogger(OrderService.class.getName());

  @Inject OrderRepository repo;
  @Inject com.shelfj.order.repo.FiscalReceiptRepository receiptRepo;
  @Inject com.shelfj.order.repo.SalesAnalyticsRepository salesAnalyticsRepo;
  @Inject TenantStatusRepository tenantStatusRepo;
  @Inject StoreStatusRepository storeStatusRepo;
  @Inject com.shelfj.order.config.ServiceConfig config;
  @Inject com.shelfj.order.client.PricingClient pricing;
  @Inject com.shelfj.order.client.CustomerLinkClient customerLink;
  @Inject com.shelfj.order.client.InventoryClient inventory;
  @Inject com.shelfj.order.client.NotificationClient notifications;
  @Inject com.shelfj.order.client.TenantClient tenants;
  @Inject FiscalService fiscal;

  // ── Orders ────────────────────────────────────────────────────────────────

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * Resolves the currency to stamp on a money-bearing row (SJ-D2).
   *
   * <p>Previously three call sites each picked their own literal — {@code "USD"} for orders and
   * gift cards, {@code "GBP"} for special orders — while pricing-svc resolved every line in the
   * price list's own currency. A GBP tenant could therefore end up with GBP-priced lines on a
   * USD-stamped order, and a USD-stamped POSLog entry underneath it. The tenant's currency has been
   * captured at onboarding since tenant-svc V1 and published on {@code TenantCreated}; nothing read
   * it.
   *
   * <p>Precedence: the tenant's projected currency wins. A request that names a different one is
   * rejected rather than silently overridden — a client asking to be billed in a currency the
   * tenant does not trade in is a bug on the caller's side, and silently correcting it would hide a
   * mispriced basket. When the projection has no row yet (a tenant onboarded before this projection
   * existed, or event-delivery lag) the request's currency is honoured if given, else the
   * configured platform default — the same fail-open convention the status projection uses.
   *
   * @param tenantId the tenant the row belongs to
   * @param requested the client-supplied currency, or {@code null} when the request omitted it
   * @return the ISO-4217 code to persist, upper-cased
   * @throws ApiException 400 {@code ORDER_CURRENCY_MISMATCH} if {@code requested} contradicts the
   *     tenant's own currency
   */
  private String resolveCurrency(UUID tenantId, String requested) {
    String asked = isBlank(requested) ? null : requested.trim().toUpperCase(Locale.ROOT);
    String tenantCurrency = tenantStatusRepo.findCurrency(tenantId).orElse(null);

    if (tenantCurrency == null) {
      return asked != null ? asked : config.defaultCurrency().toUpperCase(Locale.ROOT);
    }
    if (asked != null && !asked.equals(tenantCurrency)) {
      throw ApiException.badRequest(
          "ORDER_CURRENCY_MISMATCH",
          "currency " + asked + " does not match the tenant's currency " + tenantCurrency);
    }
    return tenantCurrency;
  }

  /**
   * Authorises a manual discount and builds its audit row (SJ-D6).
   *
   * <p>Three checks, in the order that gives the caller the most useful failure. A non-staff caller
   * is refused outright -- an online or guest checkout self-applying a discount would let the buyer
   * name their own price. A staff caller must give a reason, because a discount with no stated
   * reason is unauditable and the discount is the most common internal-theft vector at a till.
   * Finally the amount must sit within the caller's own authority: every staff role could
   * previously have taken 100% off, with only the subtotal as a ceiling.
   *
   * @return the audit row to commit alongside the order; never null (callers skip a zero discount)
   * @throws ApiException 403 {@code ORDER_DISCOUNT_NOT_ALLOWED} for a non-staff caller or a staff
   *     role with no configured ceiling; 400 {@code ORDER_DISCOUNT_REASON_REQUIRED} when no reason
   *     is given; 403 {@code ORDER_DISCOUNT_EXCEEDS_AUTHORITY} when it is above the ceiling
   */
  private OrderDiscount authorizeDiscount(
      TenantContext ctx,
      UUID orderId,
      UUID storeId,
      BigDecimal subtotal,
      BigDecimal disc,
      PlaceOrderRequest req) {
    var ceilings = config.discountCeilings();
    String bestRole = null;
    BigDecimal bestCeiling = null;
    for (String role : ctx.roles()) {
      BigDecimal ceiling = ceilings.get(role.toUpperCase(Locale.ROOT));
      if (ceiling != null && (bestCeiling == null || ceiling.compareTo(bestCeiling) > 0)) {
        bestCeiling = ceiling;
        bestRole = role.toUpperCase(Locale.ROOT);
      }
    }
    if (bestRole == null)
      throw ApiException.forbidden(
          "ORDER_DISCOUNT_NOT_ALLOWED", "discounts can only be applied by authorised staff");

    if (isBlank(req.discountReason()))
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_REASON_REQUIRED", "discountReason is required when applying a discount");

    // Percentage of subtotal, not of total: tax follows the discounted price, so measuring against
    // the post-tax figure would let the same cash discount pass or fail depending on the VAT rate.
    BigDecimal pct = disc.multiply(HUNDRED).divide(subtotal, 3, java.math.RoundingMode.HALF_UP);
    if (pct.compareTo(bestCeiling) > 0)
      throw ApiException.forbidden(
          "ORDER_DISCOUNT_EXCEEDS_AUTHORITY",
          "discount of "
              + pct
              + "% exceeds the "
              + bestCeiling
              + "% limit for role "
              + bestRole
              + " — a more senior member of staff must authorise it");

    return new OrderDiscount(
        Ids.newId(),
        ctx.requireTenantId(),
        orderId,
        storeId,
        subtotal,
        disc,
        pct,
        req.discountReason().trim(),
        ctx.userId(),
        bestRole,
        Instant.now());
  }

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /**
   * Places an order — the entry point for both online checkout and POS.
   *
   * <p>The same method serves both channels, so inventory, payments and reporting behave
   * identically across them; only {@code channel} and {@code fulfilmentType} differ. A till sale
   * that is paid at the counter is confirmed immediately, while an online order stays PENDING until
   * payment is captured.
   *
   * @param req the store, channel, fulfilment type, lines and customer details
   * @param ctx caller context; supplies the tenant and the acting identity
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retried checkout returns the
   *     original order rather than placing a second one
   * @return the placed order
   * @throws ApiException {@code ORDER_NO_ITEMS} (400) when the order has no lines; a conflict when
   *     the tenant or store is not trading
   */
  public Order placeOrder(PlaceOrderRequest req, TenantContext ctx, String idempotencyKey) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest("ORDER_NO_ITEMS", "order must have at least one item");

    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");

    if (!tenantStatusRepo.isActive(tenantId))
      throw ApiException.conflict(
          "TENANT_NOT_OPERATIONAL",
          "Tenant is suspended or blocked — orders cannot be placed at this time");
    // A signed-in storefront customer is bound to their own order from the authenticated identity —
    // never from the (untrusted) request body. Staff placing a POS order may still attach a
    // customer explicitly via the body.
    //
    // SJ-D44: the two ids are not the same id. A login is global; the shop's customer record is
    // per-tenant. Stamping the login into customer_id made every customer-keyed path — loyalty,
    // the confirmation email, erasure — miss every online order. The login is recorded as a login,
    // and the shop's record of that person is resolved from customer-svc, which creates one the
    // first time. If that call fails the order still stands with its login id: a sale is never
    // lost over a link, and the next order makes it.
    UUID loginId = null;
    UUID customerId;
    if (ctx.hasRole("CUSTOMER") && ctx.userId() != null) {
      loginId = ctx.userId();
      customerId = customerLink.customerIdFor(tenantId, loginId, ctx.email()).orElse(null);
    } else {
      customerId = req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    }
    String currency = resolveCurrency(tenantId, req.currency());
    String fulfilment =
        req.fulfilmentType() != null ? req.fulfilmentType() : Order.FULFILMENT_INSTORE;
    boolean delivery = Order.FULFILMENT_DELIVERY.equals(fulfilment);
    if (delivery) {
      if (isBlank(req.deliveryLine1())
          || isBlank(req.deliveryCity())
          || isBlank(req.deliveryPostalCode())
          || isBlank(req.deliveryRecipientName())
          || isBlank(req.deliveryRecipientPhone()))
        throw ApiException.badRequest(
            "ORDER_DELIVERY_ADDRESS_REQUIRED",
            "deliveryLine1, deliveryCity, deliveryPostalCode, deliveryRecipientName and"
                + " deliveryRecipientPhone are required when fulfilmentType is DELIVERY");
      // Server-side fulfilling-store resolve (pincode → store). When delivery areas are mapped,
      // this overrides the client storeId so stock is reserved at the correct warehouse.
      // When none are configured, tenant-svc falls back to default/first store; if tenant-svc
      // is down we keep the client storeId (fail-open for routing only).
      var resolved = tenants.resolveFulfilment(tenantId, req.deliveryPostalCode().trim());
      if (resolved.isPresent()) {
        storeId = resolved.get().storeId();
      }
    }
    ctx.requireStoreAccess(storeId);
    if (!storeStatusRepo.isActive(tenantId, storeId))
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL",
          "Store is closed or suspended — orders cannot be placed at this location");
    String paymentMethod = null;
    if (req.paymentMethod() != null && !req.paymentMethod().isBlank()) {
      paymentMethod = req.paymentMethod().trim().toUpperCase(java.util.Locale.ROOT);
      if (!java.util.Set.of("CASH", "CARD", "UPI", "WALLET").contains(paymentMethod))
        throw ApiException.badRequest(
            "ORDER_PAYMENT_METHOD_INVALID",
            "paymentMethod must be one of CASH, CARD, UPI, WALLET — got: " + req.paymentMethod());
    }

    boolean enforcePricing = config.pricingEnforce();

    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal serverTax = BigDecimal.ZERO;
    List<OrderItem> items = new ArrayList<>();
    UUID orderId = Ids.newId();

    List<UUID> variantIds =
        req.items().stream().map(ir -> Parsing.uuid(ir.variantId(), "variantId")).toList();
    // Gap #63: when enforcement is on, the price comes from pricing-svc — the client-supplied
    // unitPrice is ignored. When off (local dev / unseeded rigs), the client price is trusted.
    // One batched call resolves every line instead of one cross-service HTTP call per line.
    List<com.shelfj.order.client.PricingClient.QuotedLine> resolvedLines = null;
    com.shelfj.order.client.PricingClient.QuotedBasket quoted = null;
    if (enforcePricing) {
      var lineRequests =
          new ArrayList<com.shelfj.order.client.PricingClient.LineRequest>(variantIds.size());
      for (int i = 0; i < variantIds.size(); i++) {
        lineRequests.add(
            new com.shelfj.order.client.PricingClient.LineRequest(
                variantIds.get(i),
                req.items().get(i).qty(),
                Parsing.optionalUuid(req.items().get(i).markdownId(), "markdownId")));
      }
      // The whole basket in one call, so the promotion engine can see rules that need the order
      // total — a spend threshold, a basket percentage, a buy-one-get-one. resolveLines priced
      // each line independently and gave those nothing to be about.
      try {
        quoted =
            pricing.quoteBasket(
                tenantId, lineRequests, storeId, req.channel(), customerId, req.couponCodes());
      } catch (org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException e) {
        // Thrown by the breaker's interceptor outside the client method, so the client's own
        // catch never sees it; without this the checkout answered 500 for an open breaker.
        throw new ApiException(
            503,
            "ORDER_PRICING_UNAVAILABLE",
            "pricing-svc circuit open — too many recent failures",
            List.of(),
            e);
      }
      resolvedLines = quoted.lines();
    }

    for (int i = 0; i < req.items().size(); i++) {
      var ir = req.items().get(i);
      UUID variantId = variantIds.get(i);
      BigDecimal unitPrice;
      BigDecimal quotedLineNet = null;
      BigDecimal quotedLineVat = null;
      if (enforcePricing) {
        var resolved = resolvedLines.get(i);
        unitPrice = resolved.unitPrice();
        // Kept on the line (18.5): a fiscal file lists the sale by VAT rate, and the order's one
        // tax total cannot be split back into a 19% line and a 7% line.
        quotedLineVat = resolved.lineVat();
        // A quote returns the whole line's VAT, already multiplied out. The per-unit form this
        // used to multiply belongs to /prices/resolve-batch; multiplying a line total by the
        // quantity again put £144 of VAT on an £80 basket (SJ-D20).
        serverTax = serverTax.add(resolved.lineVat());
        // And the line's value comes from the quote too, rather than from unitPrice × qty. The
        // unit price is a rounded division of that same figure, so multiplying it back does not
        // reproduce it: three units of a £100 line quote at 33.33 each and rebuild as 99.99. Taking
        // the quoted figure keeps the order's subtotal equal to the quote the customer was shown.
        quotedLineNet = resolved.lineNet();
      } else {
        if (ir.unitPrice() == null)
          throw ApiException.badRequest(
              "ORDER_PRICE_REQUIRED", "unitPrice is required for variant " + ir.variantId());
        unitPrice = ir.unitPrice();
      }
      BigDecimal line = quotedLineNet != null ? quotedLineNet : unitPrice.multiply(ir.qty());
      subtotal = subtotal.add(line);
      UUID instrumentId =
          ir.weighingInstrumentId() == null || ir.weighingInstrumentId().isBlank()
              ? null
              : Parsing.uuid(ir.weighingInstrumentId(), "weighingInstrumentId");
      items.add(
          new OrderItem(
              Ids.newId(),
              tenantId,
              orderId,
              variantId,
              ir.qty(),
              unitPrice,
              line,
              ir.notes(),
              instrumentId,
              BigDecimal.ZERO,
              quotedLineVat,
              Parsing.optionalUuid(ir.markdownId(), "markdownId")));
    }

    // Hold stock for ONLINE orders before persisting, so a short line rejects the checkout with
    // 409 instead of accepting an order the store can't fulfil (industry-standard reserve →
    // consume-at-fulfilment → release-on-cancel). POS is exempt: it places and fulfils within
    // seconds, and its fulfilment deducts stock directly. Holds are idempotent per line on the
    // client Idempotency-Key, so a retried placement replays the original holds; if createOrder
    // fails below, the holds are released (best effort — the TTL sweeper is the backstop).
    List<UUID> heldReservations = List.of();
    if (Order.CHANNEL_ONLINE.equals(req.channel()) && config.reserveEnforce()) {
      var reserveLines =
          new ArrayList<com.shelfj.order.client.InventoryClient.ReserveLine>(items.size());
      for (OrderItem it : items) {
        reserveLines.add(
            new com.shelfj.order.client.InventoryClient.ReserveLine(it.variantId(), it.qty()));
      }
      String idemBase = idempotencyKey != null ? idempotencyKey : orderId.toString();
      heldReservations =
          inventory.reserveForOrder(
              tenantId, orderId, storeId, reserveLines, config.reservationTtlSeconds(), idemBase);
    }

    BigDecimal tax;
    // A manual discount is honoured under pricing enforcement, not discarded (SJ-D6). Enforcement
    // still owns unit prices -- the resolved price above is authoritative and the client cannot
    // name its own -- but the till's discount is a separate, deliberate staff act on top of it.
    // Zeroing it here meant the till tendered subtotal - discount against an order stored at full
    // price, so paid_amount never covered the total, the order never confirmed, and the sweeper
    // cancelled a sale the customer had already paid for.
    BigDecimal disc = req.discountAmount() != null ? req.discountAmount() : BigDecimal.ZERO;
    if (enforcePricing) {
      tax = serverTax.setScale(2, java.math.RoundingMode.HALF_UP);
    } else {
      tax = req.taxAmount() != null ? req.taxAmount() : BigDecimal.ZERO;
    }
    if (disc.signum() < 0)
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_NEGATIVE", "discountAmount cannot be negative — got " + disc);
    if (disc.compareTo(subtotal) > 0)
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_EXCEEDS_SUBTOTAL",
          "discountAmount " + disc + " exceeds order subtotal " + subtotal);

    OrderDiscount discountAudit =
        disc.signum() == 0 ? null : authorizeDiscount(ctx, orderId, storeId, subtotal, disc, req);

    // The promotion engine's whole-basket reduction. Line-level promotions are already inside the
    // resolved unit prices and therefore inside subtotal; this is the part that belongs to no
    // line. It is deliberately NOT added to disc: that column is the staff discount, and the role
    // ceiling authorizeDiscount enforces must not be spent by an automatic offer.
    BigDecimal promoDiscount =
        quoted == null
            ? BigDecimal.ZERO
            : quoted.basketDiscount().min(subtotal.subtract(disc).max(BigDecimal.ZERO));
    BigDecimal total = subtotal.add(tax).subtract(disc).subtract(promoDiscount);

    boolean taxExempt = req.taxExempt() != null && req.taxExempt();
    // SJ-D41: a catalog-mode till order is placed without prices and waits for a manager; it is
    // not PENDING, so the stranded-order sweeper leaves it alone.
    boolean awaitingPrice = Boolean.TRUE.equals(req.awaitingPrice());
    if (awaitingPrice && !"POS".equalsIgnoreCase(req.channel())) {
      throw ApiException.badRequest(
          "ORDER_AWAITING_PRICE_POS_ONLY", "only a till order can be placed awaiting a price");
    }
    Order order =
        new Order(
            orderId,
            tenantId,
            storeId,
            customerId,
            loginId,
            req.channel(),
            fulfilment,
            awaitingPrice ? Order.STATUS_AWAITING_PRICE : Order.STATUS_PENDING,
            subtotal,
            tax,
            disc,
            total,
            currency,
            req.notes(),
            idempotencyKey,
            Instant.now(),
            Instant.now(),
            taxExempt,
            req.exemptReason(),
            delivery ? req.deliveryLine1() : null,
            delivery ? req.deliveryLine2() : null,
            delivery ? req.deliveryCity() : null,
            delivery ? req.deliveryPostalCode() : null,
            delivery ? req.deliveryRecipientName() : null,
            delivery ? req.deliveryRecipientPhone() : null,
            req.contactPhone(),
            paymentMethod,
            promoDiscount);

    try {
      Order placed =
          repo.createOrder(
              order,
              items,
              Events.orderPlaced(tenantId, orderId, req.channel(), customerId, loginId, storeId),
              discountAudit,
              quoted == null ? List.of() : quoted.applied());
      // Spending the coupon is deliberately the last thing, and deliberately outside the order's
      // transaction. A basket is quoted on every change and must not burn a redemption by being
      // looked at; only a placed order spends one. If this call fails the order still stands — a
      // customer who has paid must not lose their order because a usage counter could not be
      // written — and the redemption is idempotent on the order, so a retry costs nothing.
      if (quoted != null && !quoted.applied().isEmpty()) {
        pricing.recordRedemptionsQuietly(tenantId, orderId, customerId, quoted.applied(), currency);
      }
      // A reduced-price sticker counts down the same way (05.4): after the order, quietly, once.
      if (quoted != null && items.stream().anyMatch(i -> i.markdownId() != null)) {
        pricing.recordMarkdownRedemptionsQuietly(tenantId, orderId, items);
      }
      return placed;
    } catch (ApiException e) {
      // Idempotent replay: a retried checkout with the same key gets the original order back
      // instead of an error (golden rule #11). The stock holds are NOT released here — the
      // reservation replay above already returned the original order's holds, not new ones.
      if ("ORDER_DUPLICATE_KEY".equals(e.code()) && idempotencyKey != null) {
        return repo.findOrderByIdempotencyKey(tenantId, idempotencyKey).orElseThrow(() -> e);
      }
      inventory.releaseQuietly(tenantId, heldReservations);
      throw e;
    } catch (RuntimeException e) {
      inventory.releaseQuietly(tenantId, heldReservations);
      throw e;
    }
  }

  /**
   * One page of orders plus the opaque cursor for the next page (null when exhausted).
   *
   * @param orders the page's rows
   * @param nextCursor cursor for the following page, or {@code null} on the last page
   */
  public record OrderPage(List<Order> orders, String nextCursor) {}

  /**
   * Cursor-paginated order search across the tenant.
   *
   * <p>Keyset paging on {@code (created_at, id)}, fetching one extra row to learn whether a further
   * page exists without a second query. Every filter is optional; passing none lists the tenant's
   * whole order history.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null}
   * @param customerId restrict to one customer, or {@code null}
   * @param loginId restrict to the orders one login placed, or {@code null} — a shopper's own
   *     history filters on this, not on the customer id (SJ-D44)
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null}
   * @param status restrict to one order status, or {@code null}
   * @param from inclusive lower bound on creation time, or {@code null}
   * @param to exclusive upper bound on creation time, or {@code null}
   * @param afterCursor cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page and its next cursor
   * @throws ApiException {@code INVALID_CURSOR} (400) when the cursor is malformed
   */
  public OrderPage listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
      UUID loginId,
      String channel,
      String status,
      Instant from,
      Instant to,
      String afterCursor,
      int limit) {
    Instant afterCreatedAt = null;
    UUID afterId = null;
    String rawKey = com.shelfj.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      // Raw cursor key is "<ISO created_at>|<order id>" — the keyset of the last row served.
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCreatedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = UUID.fromString(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<Order> rows =
        repo.listOrders(
            tenantId,
            storeId,
            customerId,
            loginId,
            channel,
            status,
            from,
            to,
            afterCreatedAt,
            afterId,
            limit + 1);
    if (rows.size() <= limit) {
      return new OrderPage(rows, null);
    }
    List<Order> page = rows.subList(0, limit);
    Order last = page.get(page.size() - 1);
    return new OrderPage(
        page, com.shelfj.web.Cursor.encode(last.createdAt().toString() + "|" + last.id()));
  }

  /** Hard cap on one export, so a data request cannot read an unbounded table into memory. */
  private static final int EXPORT_MAX_ORDERS = 2000;

  /**
   * Every order one person placed at this shop, with their lines — the sales half of a data export
   * (UK GDPR art.20), assembled by customer-svc.
   *
   * @param tenantId owning tenant
   * @param customerId the shop's record of the person, or {@code null}
   * @param loginId the login they sign in with, or {@code null}
   * @return the orders newest first, each with its lines; empty when both ids are null
   */
  public List<OrderWithItems> exportOrdersFor(UUID tenantId, UUID customerId, UUID loginId) {
    if (customerId == null && loginId == null) {
      return List.of();
    }
    return repo.listOrdersForSubject(tenantId, customerId, loginId, EXPORT_MAX_ORDERS).stream()
        .map(o -> new OrderWithItems(o, repo.findOrderItems(tenantId, o.id())))
        .toList();
  }

  /** An order and its lines, as the export needs them together. */
  public record OrderWithItems(Order order, List<com.shelfj.order.domain.Domain.OrderItem> items) {}

  /**
   * Reads an order with tenant scoping but <strong>no</strong> object-level authorization.
   *
   * <p>For internal callers only — anything serving a request should use {@link #getOrder(UUID,
   * UUID, TenantContext)} so one customer cannot read another's order.
   *
   * @param tenantId owning tenant
   * @param orderId the order to read
   * @return the order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists in this tenant
   */
  public Order getOrder(UUID tenantId, UUID orderId) {
    return repo.findOrder(tenantId, orderId)
        .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
  }

  /**
   * Order-by-id read for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param orderId the order to read
   * @param ctx caller context; staff may read any order in the tenant, a customer only their own
   * @return the order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists or the caller may
   *     not read it — denials are 404 so ids cannot be probed for existence
   */
  public Order getOrder(UUID tenantId, UUID orderId, TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    requireReadAccess(order, ctx);
    return order;
  }

  /**
   * Object-level authorization for order-by-id reads (mirrors CartService.requireOwnership): an
   * order id alone is not proof of ownership. Staff may read any order in their tenant; an
   * authenticated customer may only read an order placed against their own customerId. Denials are
   * 404 (not 403) so order ids can't be probed for existence.
   *
   * <p>There is deliberately no exemption for a caller with no principal. That branch existed for
   * service-to-service lookups and assumed the gateway never forwards a tenant here without a
   * verified user — but guest checkout does exactly that, so any order id could be read by anyone
   * holding one. payment-svc's OrderClient stamps a staff role instead.
   */
  private static void requireReadAccess(Order order, TenantContext ctx) {
    if (isStaff(ctx)) return;
    // No service-to-service exemption. This used to return early for a caller with no principal
    // at all, on the reasoning that only the mesh could produce that shape — but a guest storefront
    // request carries a tenant and no principal too, so the shape was reachable from outside and
    // any id could be read by anyone who had one. The internal callers now stamp a staff role
    // (payment-svc OrderClient, notification-svc CustomerClient), so nothing needs the exemption.
    // Matched on the login the order was placed with, not the customer id: they are different ids
    // (SJ-D44), and the login is the one the token carries. An order with no login was not placed
    // by a shopper, so no shopper may read it.
    if (order.loginId() == null || !order.loginId().equals(ctx.userId()))
      throw ApiException.notFound("ORDER_NOT_FOUND", "order not found");
  }

  private static boolean isStaff(TenantContext ctx) {
    return ctx.hasRole("PLATFORM_ADMIN")
        || ctx.hasRole("OWNER")
        || ctx.hasRole("MANAGER")
        || ctx.hasRole("STOREKEEPER")
        || ctx.hasRole("CASHIER");
  }

  /**
   * SIM↔POS projection rows for POS screens (gap #50).
   *
   * <p>Read from order-svc's own projection of inventory events, not from inventory-svc, so the
   * figures are eventually consistent with the owning service.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null}
   * @param variantId restrict to one variant, or {@code null}
   * @param limit maximum rows
   * @return the stock positions
   */
  public List<com.shelfj.order.domain.Domain.PosStockPosition> listStockPositions(
      UUID tenantId, UUID storeId, UUID variantId, int limit) {
    return repo.findStockPositions(tenantId, storeId, variantId, limit);
  }

  /**
   * The lines on an order, with tenant scoping but <strong>no</strong> object-level authorization.
   *
   * <p>Callers serving a request must check access themselves — the resource does so by reading the
   * order through {@link #getOrder(UUID, UUID, TenantContext)} first.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose lines to read
   * @return the order's lines
   */
  public List<OrderItem> getOrderItems(UUID tenantId, UUID orderId) {
    return repo.findOrderItems(tenantId, orderId);
  }

  /**
   * The append-only status history of an order.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose history to read
   * @param ctx caller context, checked against the order before the history is read
   * @return the status transitions, oldest first
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists or the caller may
   *     not read it
   */
  public List<OrderStatusHistory> getOrderHistory(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return repo.findOrderHistory(tenantId, orderId);
  }

  /**
   * A till sale: rung up on the POS channel and handed over at the counter.
   *
   * <p>SJ-D40. inventory-svc deducts stock only on OrderFulfilled, and nothing ever fulfilled a
   * till sale: the till places the order, payment capture confirms it, and there it stopped. Stock
   * moved only if a manager later opened each sale and clicked "Mark fulfilled".
   *
   * <p>PICKUP counts as well as INSTORE because the till sent PICKUP for every tendered sale until
   * this fix, and sales already sitting in offline queues on devices will replay with it. Nothing
   * on the POS channel means "collect later" — special orders and layaways have their own resources
   * for that. DELIVERY is excluded: a till can take payment for goods that go out on a van, and
   * those are handed over when they arrive.
   */
  static boolean isTillSale(String channel, String fulfilmentType) {
    return Order.CHANNEL_POS.equals(channel)
        && (Order.FULFILMENT_INSTORE.equals(fulfilmentType)
            || Order.FULFILMENT_PICKUP.equals(fulfilmentType));
  }

  /**
   * Moves an order to CONFIRMED and publishes {@code OrderConfirmed}.
   *
   * <p>The event carries the buyer and the settled amount because customer-svc accrues loyalty from
   * it; inventory-svc treats confirmation as the point stock is committed.
   *
   * @param tenantId owning tenant
   * @param orderId the order to confirm
   * @param userId the staff member or system actor confirming it
   * @return the confirmed order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; a conflict when
   *     the order is not awaiting confirmation
   */
  public Order confirmOrder(UUID tenantId, UUID orderId, UUID userId) {
    // Load the order so OrderConfirmed can carry the buyer + settled amount (loyalty accrual).
    Order order = getOrder(tenantId, orderId);
    var confirmEvent =
        Events.orderConfirmed(
            tenantId,
            orderId,
            order.storeId(),
            order.channel(),
            order.customerId(),
            order.total(),
            order.currency());
    Order confirmed =
        isTillSale(order.channel(), order.fulfilmentType())
            ? repo.confirmAndFulfil(
                tenantId,
                orderId,
                userId,
                confirmEvent,
                Events.orderFulfilled(
                    tenantId, orderId, order.storeId(), repo.findOrderItems(tenantId, orderId)))
            : repo.transitionOrderStatus(
                tenantId,
                orderId,
                Order.STATUS_PENDING,
                Order.STATUS_CONFIRMED,
                "confirmed",
                userId,
                confirmEvent);

    // The number is taken when a sale completes, not when someone asks for a document — a
    // sequence that only numbers the sales somebody remembered to print is not a sequence. It is
    // outside the status transaction on purpose: a fiscal number is worth having and not worth
    // failing a paid-for sale to get, and the sequence stays gapless either way because the
    // counter only moves when a receipt row is written. POST /admin/orders/{id}/fiscal-receipt
    // issues it later if this fails.
    issueReceiptQuietly(confirmed, userId);
    return confirmed;
  }

  private void issueReceiptQuietly(Order order, UUID userId) {
    try {
      issueReceipt(order, Domain.FiscalReceipt.DEFAULT_SERIES, userId);
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.ERROR,
          () ->
              "Order "
                  + order.id()
                  + " was confirmed but no fiscal receipt could be issued; issue it with POST"
                  + " /admin/orders/{id}/fiscal-receipt",
          e);
    }
  }

  /**
   * Issues (or returns) the numbered receipt for a sale.
   *
   * <p>Only a sale that has actually happened gets a number. A PENDING order has not been paid for
   * and may never be — numbering it would put a hole in the sequence the moment the basket is
   * abandoned, which is the exact thing the sequence must not have.
   */
  public Domain.FiscalReceipt issueReceipt(Order order, String seriesCode, UUID userId) {
    if (Order.STATUS_PENDING.equals(order.status())
        || Order.STATUS_CANCELLED.equals(order.status())) {
      throw ApiException.badRequest(
          "ORDER_NOT_SELLABLE",
          "A receipt is only issued for a completed sale; this order is " + order.status());
    }
    String series =
        seriesCode == null || seriesCode.isBlank()
            ? Domain.FiscalReceipt.DEFAULT_SERIES
            : seriesCode.trim().toUpperCase(java.util.Locale.ROOT);
    // The store's fiscal regime stamps the document as it is numbered (18.5): the fiscal year the
    // numbering restarts on is taken there, in UTC like the rest of the platform.
    return fiscal.issue(order, series, userId);
  }

  /**
   * Issues a fiscal receipt for an order looked up by id.
   *
   * @param tenantId owning tenant
   * @param orderId the completed sale to receipt
   * @param seriesCode the numbering series, or {@code null}/blank for the default
   * @param userId the staff member issuing it
   * @return the issued receipt with its allocated number
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_NOT_SELLABLE} (409) when the sale is not completed
   */
  public Domain.FiscalReceipt issueReceipt(
      UUID tenantId, UUID orderId, String seriesCode, UUID userId) {
    return issueReceipt(getOrder(tenantId, orderId), seriesCode, userId);
  }

  /**
   * The fiscal receipt for a sale, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>For internal callers only — request-serving code should use the {@link TenantContext}
   * overload.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose receipt to read
   * @return the receipt
   * @throws ApiException {@code ORDER_RECEIPT_NOT_ISSUED} (404) when none has been issued
   */
  public Domain.FiscalReceipt receiptOf(UUID tenantId, UUID orderId) {
    return receiptRepo
        .findByOrder(tenantId, orderId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "ORDER_RECEIPT_NOT_ISSUED", "No fiscal receipt has been issued for this sale"));
  }

  /**
   * The receipt for a sale, to whoever may read the sale: any staff member, or the customer who
   * placed it. The till prints the number from here — the admin route is management-only.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose receipt to read
   * @param ctx caller context, checked against the order first
   * @return the receipt
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when the caller may not read the sale;
   *     {@code ORDER_RECEIPT_NOT_ISSUED} (404) when no receipt has been issued
   */
  /**
   * The receipt for a sale, waiting a bounded time for it to be issued. The number is taken when
   * the payment that completes a till sale lands, a Kafka hop after the tender, so the till used to
   * poll sixteen times over eight seconds and print without a number when order-svc was slow. One
   * request that waits here instead costs one round trip, and the wait is capped so a stuck
   * consumer never holds a till.
   *
   * @param tenantId owning tenant
   * @param orderId the sale
   * @param ctx caller identity, for the object-level check
   * @param waitSeconds how long to wait, 0..20
   * @return the receipt
   * @throws ApiException {@code ORDER_RECEIPT_NOT_ISSUED} (404) when it is still not issued
   */
  public Domain.FiscalReceipt awaitReceipt(
      UUID tenantId, UUID orderId, TenantContext ctx, int waitSeconds) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    long deadline = System.nanoTime() + Math.min(Math.max(waitSeconds, 0), 20) * 1_000_000_000L;
    do {
      var found = receiptRepo.findByOrder(tenantId, orderId);
      if (found.isPresent()) {
        return found.get();
      }
    } while (System.nanoTime() < deadline && pauseBriefly());
    throw ApiException.notFound(
        "ORDER_RECEIPT_NOT_ISSUED", "No fiscal receipt has been issued for this sale");
  }

  /** One poll interval; false when the thread was interrupted, which ends the wait. */
  private static boolean pauseBriefly() {
    try {
      Thread.sleep(250);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * The series a store runs: code, period, where the counter has got to, and the prefix.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @return the counters, newest period first
   */
  public List<com.shelfj.order.repo.FiscalReceiptRepository.ReceiptSeries> receiptSeriesConfig(
      UUID tenantId, UUID storeId) {
    return receiptRepo.listSeriesConfig(tenantId, storeId);
  }

  /**
   * Sets the prefix a series prints in front of its numbers, opening the series if it is new.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param series the series code; MAIN when blank
   * @param period the fiscal period, e.g. 2026
   * @param prefix letters, digits and hyphens, at most 16; blank for none
   * @return the counter as it now stands
   * @throws ApiException {@code RECEIPT_PREFIX_INVALID} (400)
   */
  public com.shelfj.order.repo.FiscalReceiptRepository.ReceiptSeries setReceiptSeriesPrefix(
      UUID tenantId, UUID storeId, String series, String period, String prefix) {
    String p = prefix == null || prefix.isBlank() ? null : prefix.trim().toUpperCase(Locale.ROOT);
    if (p != null && !p.matches("^[A-Z0-9][A-Z0-9-]{0,15}$")) {
      throw ApiException.badRequest(
          "RECEIPT_PREFIX_INVALID", "a prefix is letters, digits and hyphens, at most 16");
    }
    if (period == null || !period.trim().matches("^[0-9]{4}(-[0-9]{2})?$")) {
      throw ApiException.badRequest("RECEIPT_PERIOD_INVALID", "period is a year, e.g. 2026");
    }
    return receiptRepo.setSeriesPrefix(
        tenantId, storeId, seriesOrDefault(series), period.trim(), p);
  }

  public Domain.FiscalReceipt receiptOf(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return receiptOf(tenantId, orderId);
  }

  /**
   * The receipts in one numbering series, for a store and fiscal period.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose series to read
   * @param series the numbering series, or {@code null}/blank for the default
   * @param period the fiscal period, normally the year
   * @param limit maximum rows; clamped to 1..500
   * @return the receipts in the series
   */
  public List<Domain.FiscalReceipt> receiptSeries(
      UUID tenantId, UUID storeId, String series, String period, int limit) {
    return receiptRepo.listSeries(
        tenantId, storeId, seriesOrDefault(series), period, Math.min(Math.max(limit, 1), 500));
  }

  /**
   * The gap audit: bounds, count, and every hole. An empty gap list is the proof.
   *
   * <p>What a tax inspector asks for: a fiscal series must be unbroken, so the absence of gaps is
   * the evidence. {@code expected} is the span rather than the count, so a series holding 400
   * receipts numbered 1..500 reads as 100 missing without anyone subtracting.
   *
   * @param tenantId owning tenant
   * @param storeId the store whose series to audit
   * @param series the numbering series, or {@code null}/blank for the default
   * @param period the fiscal period, normally the year
   * @return first and last number, issued and expected counts, an {@code intact} flag, and every
   *     gap as a from/to pair
   */
  public java.util.Map<String, Object> receiptAudit(
      UUID tenantId, UUID storeId, String series, String period) {
    String s = seriesOrDefault(series);
    long[] bounds = receiptRepo.seriesBounds(tenantId, storeId, s, period);
    var gaps = receiptRepo.findGaps(tenantId, storeId, s, period);
    var out = new java.util.LinkedHashMap<String, Object>();
    out.put("storeId", storeId.toString());
    out.put("seriesCode", s);
    out.put("period", period);
    out.put("firstNumber", bounds[0]);
    out.put("lastNumber", bounds[1]);
    out.put("issued", bounds[2]);
    // Expected is the span, so a series with 400 receipts numbered 1..500 reads as 100 missing
    // without anyone having to subtract.
    out.put("expected", bounds[1] == 0 ? 0 : bounds[1] - bounds[0] + 1);
    out.put("intact", gaps.isEmpty());
    out.put(
        "gaps", gaps.stream().map(g -> java.util.Map.of("from", g.from(), "to", g.to())).toList());
    // 18.4: a second, independent verdict — not whether a number is missing, but whether any
    // document's stored figures still match the hash written when it was issued.
    var chain = receiptRepo.verifyChain(tenantId, storeId, s, period);
    out.put("chainIntact", chain.intact());
    out.put("chainFrom", chain.from());
    out.put("chainBrokenAt", chain.brokenAt());
    return out;
  }

  /**
   * The register as a file (18.4): every document in a series with its hashes, as CSV rows or as
   * JSON with the order lines behind each document. Management-only at the resource.
   *
   * @param format {@code csv} or {@code json}
   * @return the CSV text, or the JSON-shaped map
   */
  public Object exportRegister(
      UUID tenantId, UUID storeId, String series, String period, String format) {
    String s = seriesOrDefault(series);
    var docs = receiptRepo.listSeries(tenantId, storeId, s, period, 1_000_000);
    if ("json".equalsIgnoreCase(format)) {
      var lines = new java.util.HashMap<Long, List<Map<String, Object>>>();
      for (var l : receiptRepo.linesInSeries(tenantId, storeId, s, period)) {
        var line = new LinkedHashMap<String, Object>();
        line.put("variantId", l.variantId().toString());
        line.put("qty", l.qty());
        line.put("unitPrice", l.unitPrice());
        line.put("lineTotal", l.lineTotal());
        lines.computeIfAbsent(l.number(), k -> new java.util.ArrayList<>()).add(line);
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("storeId", storeId.toString());
      out.put("seriesCode", s);
      out.put("period", period);
      out.put("generatedAt", java.time.Instant.now().toString());
      out.put(
          "documents",
          docs.stream()
              .map(
                  d -> {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("number", d.number());
                    m.put("fullNumber", d.fullNumber());
                    m.put("issuedAt", d.issuedAt().toString());
                    m.put("orderId", d.orderId().toString());
                    m.put("currency", d.currency());
                    m.put("grossTotal", d.grossTotal());
                    m.put("taxTotal", d.taxTotal());
                    m.put("voidedAt", d.voidedAt() == null ? null : d.voidedAt().toString());
                    m.put("voidReason", d.voidReason());
                    m.put("prevHash", d.prevHash());
                    m.put("hash", d.hash());
                    m.put("lines", lines.getOrDefault(d.number(), List.of()));
                    return m;
                  })
              .toList());
      return out;
    }
    StringBuilder csv =
        new StringBuilder(
            "number,fullNumber,issuedAt,orderId,currency,grossTotal,taxTotal,voidedAt,voidReason,"
                + "prevHash,hash\n");
    for (var d : docs) {
      csv.append(d.number())
          .append(',')
          .append(csvCell(d.fullNumber()))
          .append(',')
          .append(d.issuedAt())
          .append(',')
          .append(d.orderId())
          .append(',')
          .append(d.currency())
          .append(',')
          .append(d.grossTotal().toPlainString())
          .append(',')
          .append(d.taxTotal().toPlainString())
          .append(',')
          .append(d.voidedAt() == null ? "" : d.voidedAt().toString())
          .append(',')
          .append(csvCell(d.voidReason()))
          .append(',')
          .append(csvCell(d.prevHash()))
          .append(',')
          .append(csvCell(d.hash()))
          .append('\n');
    }
    return csv.toString();
  }

  private static String csvCell(String v) {
    if (v == null) {
      return "";
    }
    return v.contains(",") || v.contains("\"") || v.contains("\n")
        ? "\"" + v.replace("\"", "\"\"") + "\""
        : v;
  }

  private static String seriesOrDefault(String series) {
    return series == null || series.isBlank()
        ? Domain.FiscalReceipt.DEFAULT_SERIES
        : series.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * Cancels a PENDING or CONFIRMED order, publishing {@code OrderCancelled}.
   *
   * <p>Both states must be cancellable so their stock holds are released — inventory-svc reacts to
   * the event. A fulfilled order is returned rather than cancelled.
   *
   * @param tenantId owning tenant
   * @param orderId the order to cancel
   * @param reason free-text reason recorded on the status transition
   * @param userId the staff member cancelling it
   * @return the cancelled order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_CANNOT_CANCEL} (409) when it is not PENDING or CONFIRMED
   */
  public Order cancelOrder(UUID tenantId, UUID orderId, String reason, UUID userId) {
    // PENDING covers pay-later online orders awaiting confirmation; both states must be
    // cancellable so their stock holds get released (inventory-svc reacts to OrderCancelled).
    Order order = getOrder(tenantId, orderId);
    if (Order.STATUS_PARTIALLY_FULFILLED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_PARTLY_FULFILLED",
          "some of the goods were handed over; take them back as a return or hand over the rest");
    if (!Order.STATUS_PENDING.equals(order.status())
        && !Order.STATUS_AWAITING_PRICE.equals(order.status())
        && !Order.STATUS_CONFIRMED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_CANNOT_CANCEL",
          "only PENDING, AWAITING_PRICE or CONFIRMED orders can be cancelled");
    return repo.transitionOrderStatus(
        tenantId,
        orderId,
        order.status(),
        Order.STATUS_CANCELLED,
        reason,
        userId,
        Events.orderCancelled(tenantId, orderId, reason));
  }

  /**
   * Marks a CONFIRMED order fulfilled, publishing {@code OrderFulfilled} with its lines.
   *
   * <p>The event carries the lines because inventory-svc deducts against them; the transition
   * itself is guarded on CONFIRMED, so fulfilling twice fails rather than deducting twice.
   *
   * @param tenantId owning tenant
   * @param orderId the order to fulfil
   * @param userId the staff member fulfilling it
   * @return the fulfilled order
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; a conflict when
   *     the order is not CONFIRMED
   */
  public Order fulfillOrder(UUID tenantId, UUID orderId, UUID userId) {
    return fulfilOrder(tenantId, orderId, null, userId, null);
  }

  /**
   * Prices a catalog-mode till order (SJ-D41): a manager gives every line its unit price, the
   * totals are recomputed, and the order becomes PENDING — payable at the till or in Admin → Orders
   * → Collect payment, and swept as stranded only if it then sits unpaid.
   *
   * @throws ApiException {@code ORDER_NOT_FOUND} (404); {@code ORDER_NOT_AWAITING_PRICE} (409);
   *     {@code ORDER_PRICE_LINE_MISSING} / {@code ORDER_PRICE_LINE_UNKNOWN} (400)
   */
  public Order priceOrder(
      UUID tenantId,
      UUID orderId,
      com.shelfj.order.dto.Dtos.PriceOrderRequest req,
      UUID userId,
      TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    ctx.requireStoreAccess(order.storeId());
    Map<UUID, BigDecimal> prices = new LinkedHashMap<>();
    for (var line : req.lines()) {
      if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
        throw ApiException.badRequest("ORDER_PRICE_INVALID", "a unit price cannot be negative");
      }
      prices.put(Parsing.uuid(line.variantId(), "variantId"), line.unitPrice());
    }
    if (prices.isEmpty()) {
      throw ApiException.badRequest("ORDER_PRICE_LINE_MISSING", "no prices given");
    }
    BigDecimal tax = req.taxAmount() == null ? BigDecimal.ZERO : req.taxAmount();
    return repo.priceOrder(tenantId, orderId, prices, tax, userId);
  }

  /**
   * Hands over some or all of an order (SJ-D35). With lines, only those quantities leave the store
   * now and the order is PARTIALLY_FULFILLED until every line is complete; without, everything
   * still outstanding is handed over, which for an untouched order is the old all-or-nothing
   * fulfilment. Each call emits one OrderFulfilled carrying only this call's quantities.
   *
   * @param tenantId owning tenant
   * @param orderId the order
   * @param req the lines and quantities handed over now; null or empty for everything outstanding
   * @param userId the staff member
   * @param ctx caller context, checked for access to the order's store; null for internal callers
   * @return the order as it now stands
   * @throws ApiException {@code ORDER_NOT_FOUND} (404); {@code ORDER_NOT_FULFILLABLE} (409) unless
   *     CONFIRMED or PARTIALLY_FULFILLED; {@code ORDER_FULFIL_LINE_UNKNOWN} (400); {@code
   *     ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING} (409); {@code ORDER_NOTHING_OUTSTANDING} (409)
   */
  public Order fulfilOrder(
      UUID tenantId,
      UUID orderId,
      com.shelfj.order.dto.Dtos.FulfilRequest req,
      UUID userId,
      TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    if (ctx != null) {
      ctx.requireStoreAccess(order.storeId());
    }
    Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
    if (req != null && req.lines() != null) {
      for (var line : req.lines()) {
        if (line.qty() == null || line.qty().signum() <= 0) {
          throw ApiException.badRequest(
              "ORDER_FULFIL_QTY_INVALID", "a handed-over quantity must be greater than zero");
        }
        wanted.merge(Parsing.uuid(line.variantId(), "variantId"), line.qty(), BigDecimal::add);
      }
    }
    return repo.fulfilLines(
        tenantId,
        orderId,
        wanted,
        userId,
        now ->
            Events.orderFulfilled(
                tenantId,
                orderId,
                order.storeId(),
                now.stream()
                    .map(
                        l ->
                            new OrderItem(
                                null,
                                tenantId,
                                orderId,
                                l.variantId(),
                                l.qty(),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                null,
                                null))
                    .toList()));
  }

  // ── Returns ───────────────────────────────────────────────────────────────

  /**
   * Records a return against a fulfilled order.
   *
   * <p>Only a fulfilled or partly refunded order can be returned: goods can come back only once
   * they were handed over. Returning a PENDING or CONFIRMED order would record a refund for goods,
   * and often money, that were never exchanged — cancel it instead.
   *
   * @param tenantId owning tenant
   * @param orderId the order being returned against
   * @param req the lines and quantities coming back, and the reason
   * @param ctx caller context, checked for access to the order's store
   * @return the recorded return
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_CANNOT_RETURN} (409) when it is not FULFILLED or PARTIALLY_REFUNDED
   */
  public Return createReturn(
      UUID tenantId, UUID orderId, CreateReturnRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    ctx.requireStoreAccess(order.storeId());

    // Goods can only come back once they were handed over. An order still PENDING or CONFIRMED
    // never left the store — cancel it instead; returning it recorded a refund for goods, and
    // often money, that were never exchanged. A fully REFUNDED order has nothing left to refund.
    if (!Order.STATUS_FULFILLED.equals(order.status())
        && !Order.STATUS_PARTIALLY_FULFILLED.equals(order.status())
        && !Order.STATUS_PARTIALLY_REFUNDED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_CANNOT_RETURN",
          "only a fulfilled order can be returned; this one is " + order.status());

    List<OrderItem> orderItems = repo.findOrderItems(tenantId, orderId);
    UUID returnId = Ids.newId();
    BigDecimal totalRefund = BigDecimal.ZERO;
    List<ReturnItem> returnItems = new ArrayList<>();
    String method = req.refundMethod() != null ? req.refundMethod() : Return.METHOD_ORIGINAL;

    for (var ri : req.items()) {
      UUID variantId = Parsing.uuid(ri.variantId(), "variantId");
      OrderItem matched =
          orderItems.stream()
              .filter(oi -> oi.variantId().equals(variantId))
              .findFirst()
              .orElseThrow(
                  () ->
                      ApiException.notFound(
                          "ITEM_NOT_IN_ORDER", "variant " + ri.variantId() + " not in order"));
      BigDecimal refundAmt = matched.unitPrice().multiply(ri.qty());
      totalRefund = totalRefund.add(refundAmt);
      returnItems.add(
          new ReturnItem(
              Ids.newId(), tenantId, returnId, variantId, ri.qty(), refundAmt, ri.condition()));
    }

    Return ret =
        new Return(
            returnId,
            tenantId,
            orderId,
            order.storeId(),
            req.reason(),
            totalRefund,
            method,
            Return.STATUS_COMPLETED,
            Instant.now(),
            Instant.now());

    return repo.createReturn(
        ret,
        returnItems,
        Events.orderReturned(
            tenantId,
            orderId,
            returnId,
            order.storeId(),
            returnItems,
            totalRefund,
            method,
            order.currency()));
  }

  /**
   * The returns recorded against one order.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose returns to read
   * @param ctx caller context, checked against the order first
   * @return the returns, empty when nothing has come back
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists or the caller may
   *     not read it
   */
  public List<Return> getReturns(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return repo.findReturns(tenantId, orderId);
  }

  /**
   * The lines on one return, with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>Callers serving a request must check access to the owning order themselves.
   *
   * @param tenantId owning tenant
   * @param returnId the return whose lines to read
   * @return the returned lines, with their per-line refund amounts
   */
  public List<ReturnItem> getReturnItems(UUID tenantId, UUID returnId) {
    return repo.findReturnItems(tenantId, returnId);
  }

  // ── Post-void ─────────────────────────────────────────────────────────────

  /**
   * Voids a POS sale, restocking its lines and marking its receipt.
   *
   * <p>The receipt keeps its number and gains a reason rather than being removed: closing the hole
   * in the sequence is the trick a till fraud relies on — ring the sale, take the cash, void the
   * receipt, and a balancing till hides the theft. Here the document stays, numbered.
   *
   * <p>POS only: an online order is cancelled or returned instead.
   *
   * @param tenantId owning tenant
   * @param orderId the sale to void
   * @param req the reason, recorded on both the void log and the receipt
   * @param ctx caller context, checked for access to the sale's store
   * @return the recorded void log entry
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     ORDER_VOID_ONLY_POS} (409) when the order is not a POS sale
   */
  public PosVoidLog voidOrder(UUID tenantId, UUID orderId, VoidRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    ctx.requireStoreAccess(order.storeId());
    if (!Order.CHANNEL_POS.equals(order.channel()))
      throw ApiException.conflict("ORDER_VOID_ONLY_POS", "void is only allowed on POS orders");
    PosVoidLog log =
        repo.voidOrder(
            tenantId,
            orderId,
            order.storeId(),
            req.reason(),
            ctx.userId(),
            restock -> Events.orderVoided(tenantId, orderId, order.storeId(), restock));

    // The receipt keeps its number and gains a reason. Removing it would close the hole in the
    // sequence, and closing the hole is the whole trick: ring the sale, take the cash, void the
    // receipt, and a till that balances hides a theft. Here the document stays, numbered.
    receiptRepo.markVoided(tenantId, orderId, req.reason());
    return log;
  }

  // ── Layaway ───────────────────────────────────────────────────────────────

  /**
   * Opens a layaway: goods set aside against a deposit, collected once paid off.
   *
   * @param req the store, customer, items and initial deposit
   * @param ctx caller context; supplies the tenant and is checked for store access
   * @return the opened layaway
   * @throws ApiException {@code LAYAWAY_NO_ITEMS} (400) when no items are supplied
   */
  public Layaway createLayaway(CreateLayawayRequest req, TenantContext ctx) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest("LAYAWAY_NO_ITEMS", "layaway must have at least one item");

    // requireTenantId (not the nullable tenantId()) so a request that somehow reached this
    // financial write path without a tenant fails 401 instead of persisting a null-tenant row.
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID customerId =
        req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    UUID layawayId = Ids.newId();

    BigDecimal total = BigDecimal.ZERO;
    List<LayawayItem> items = new ArrayList<>();
    for (var li : req.items()) {
      BigDecimal line = li.unitPrice().multiply(li.qty());
      total = total.add(line);
      items.add(
          new LayawayItem(
              Ids.newId(),
              tenantId,
              layawayId,
              Parsing.uuid(li.variantId(), "variantId"),
              li.qty(),
              li.unitPrice(),
              line));
    }

    BigDecimal balance = total.subtract(req.initialDeposit());
    if (balance.compareTo(BigDecimal.ZERO) < 0)
      throw ApiException.conflict(
          "DEPOSIT_EXCEEDS_TOTAL", "initial deposit cannot exceed total amount");

    Instant dueDate = req.dueDate() != null ? Parsing.instant(req.dueDate(), "dueDate") : null;
    Layaway layaway =
        new Layaway(
            layawayId,
            tenantId,
            storeId,
            customerId,
            total,
            req.initialDeposit(),
            balance,
            Layaway.STATUS_ACTIVE,
            req.notes(),
            Instant.now(),
            dueDate,
            null,
            null);

    LayawayDeposit deposit =
        new LayawayDeposit(
            Ids.newId(),
            tenantId,
            layawayId,
            req.initialDeposit(),
            req.paymentMethod(),
            null,
            Instant.now());

    return repo.createLayaway(layaway, items, deposit, Events.layawayCreated(tenantId, layawayId));
  }

  /**
   * Reads one layaway.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway to read
   * @return the layaway with its total and outstanding balance
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists in this tenant
   */
  public Layaway getLayaway(UUID tenantId, UUID layawayId) {
    return repo.findLayaway(tenantId, layawayId)
        .orElseThrow(() -> ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found"));
  }

  /**
   * The goods set aside on one layaway.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway whose items to read
   * @return the reserved lines with their prices
   */
  public List<LayawayItem> getLayawayItems(UUID tenantId, UUID layawayId) {
    return repo.findLayawayItems(tenantId, layawayId);
  }

  /**
   * The payments made against one layaway.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway whose deposits to read
   * @return the deposits, which together with the total give the balance still owed
   */
  public List<LayawayDeposit> getLayawayDeposits(UUID tenantId, UUID layawayId) {
    return repo.findLayawayDeposits(tenantId, layawayId);
  }

  /**
   * Takes a further payment against a layaway, reducing its balance.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway being paid down
   * @param req the amount, payment method and reference
   * @param ctx caller context
   * @return the layaway with its new balance
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists
   */
  public Layaway addDeposit(
      UUID tenantId, UUID layawayId, AddDepositRequest req, TenantContext ctx) {
    LayawayDeposit deposit =
        new LayawayDeposit(
            Ids.newId(),
            tenantId,
            layawayId,
            req.amount(),
            req.paymentMethod(),
            req.reference(),
            Instant.now());
    return repo.addDeposit(tenantId, layawayId, deposit);
  }

  /**
   * Closes a fully paid layaway and hands the goods over, publishing {@code LayawayCompleted}.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway to complete
   * @param ctx caller context
   * @return the completed layaway
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists; a conflict
   *     when a balance is still owed
   */
  public Layaway completeLayaway(UUID tenantId, UUID layawayId, TenantContext ctx) {
    return repo.completeLayaway(tenantId, layawayId, Events.layawayCompleted(tenantId, layawayId));
  }

  /**
   * Cancels a layaway, releasing the goods held against it and publishing {@code LayawayCancelled}.
   *
   * <p>Refunding deposits already taken is a separate decision, handled through payment-svc.
   *
   * @param tenantId owning tenant
   * @param layawayId the layaway to cancel
   * @param reason free-text reason recorded against it
   * @param ctx caller context
   * @return the cancelled layaway
   * @throws ApiException {@code LAYAWAY_NOT_FOUND} (404) when no such layaway exists
   */
  public Layaway cancelLayaway(UUID tenantId, UUID layawayId, String reason, TenantContext ctx) {
    return repo.cancelLayaway(
        tenantId, layawayId, reason, Events.layawayCancelled(tenantId, layawayId));
  }

  // ── Gift cards ────────────────────────────────────────────────────────────

  /**
   * Issues a gift card with a server-generated code, recording the opening transaction.
   *
   * <p>The code is minted here, never supplied by the caller: it is bearer stored value, so a
   * guessable or client-chosen code would be spendable by whoever guessed it.
   *
   * @param req the store, amount, optional currency and optional expiry
   * @param ctx caller context; supplies the tenant and is checked for store access
   * @return the issued card, including its code
   */
  public GiftCard issueGiftCard(IssueGiftCardRequest req, TenantContext ctx) {
    // requireTenantId (not the nullable tenantId()) so issuing a gift card without a tenant in
    // context fails 401 rather than minting stored value against a null-tenant row.
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID gcId = Ids.newId();
    String code = generateGiftCardCode();
    String currency = resolveCurrency(tenantId, req.currency());
    Instant expiresAt =
        req.expiresAt() != null ? Parsing.instant(req.expiresAt(), "expiresAt") : null;

    GiftCard gc =
        new GiftCard(
            gcId,
            tenantId,
            storeId,
            code,
            req.amount(),
            req.amount(),
            GiftCard.STATUS_ACTIVE,
            currency,
            Instant.now(),
            expiresAt);

    GiftCardTransaction tx =
        new GiftCardTransaction(
            Ids.newId(),
            tenantId,
            gcId,
            GiftCardTransaction.TX_ISSUE,
            req.amount(),
            BigDecimal.ZERO,
            req.amount(),
            null,
            null,
            Instant.now());

    return repo.issueGiftCard(gc, tx);
  }

  /**
   * Looks a gift card up by its code, to check the balance at the till.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @return the card with its current balance
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists in this tenant
   */
  public GiftCard getGiftCard(UUID tenantId, String code) {
    return repo.findGiftCardByCode(tenantId, code)
        .orElseThrow(() -> ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found"));
  }

  /**
   * Adds value to an existing gift card.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @param req the amount to add and a reference for the transaction log
   * @return the card with its new balance
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists; a conflict
   *     when the card is not active
   */
  public GiftCard reloadGiftCard(UUID tenantId, String code, ReloadGiftCardRequest req) {
    return repo.reloadGiftCard(tenantId, code, req.amount(), req.reference());
  }

  /**
   * Spends against a gift card, optionally attributing it to an order.
   *
   * <p>The balance check happens in the repository, inside the transaction that writes the
   * transaction row, so two tills cannot together overspend one card.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @param req the amount, the order being paid towards, and a reference
   * @return the card with its new balance
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists; a conflict
   *     when the balance is insufficient or the card is not active
   */
  public GiftCard redeemGiftCard(UUID tenantId, String code, RedeemGiftCardRequest req) {
    UUID orderId = req.orderId() != null ? Parsing.uuid(req.orderId(), "orderId") : null;
    return repo.redeemGiftCard(tenantId, code, req.amount(), orderId, req.reference());
  }

  /**
   * The append-only transaction history of one gift card.
   *
   * @param tenantId owning tenant
   * @param code the card's code
   * @return every issue, reload and redemption against the card
   * @throws ApiException {@code GIFT_CARD_NOT_FOUND} (404) when no such card exists in this tenant
   */
  public List<GiftCardTransaction> getGiftCardTransactions(UUID tenantId, String code) {
    GiftCard gc =
        repo.findGiftCardByCode(tenantId, code)
            .orElseThrow(() -> ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found"));
    return repo.findGiftCardTransactions(tenantId, gc.id());
  }

  // ── Payment event handlers (called by PaymentEventHandler) ───────────────

  /**
   * Accumulates a captured tender toward the order's total and confirms the order once tenders
   * cover it. Split tenders (e.g. POS cash+card, each individually below the order total) each call
   * this once and accumulate, instead of the order only confirming on a single full-amount tender.
   */
  /** A capture whose event carried no tender method (older producers). */
  public void handlePaymentCaptured(
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.util.UUID paymentId,
      java.math.BigDecimal amount) {
    handlePaymentCaptured(tenantId, orderId, paymentId, amount, null);
  }

  public void handlePaymentCaptured(
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.util.UUID paymentId,
      java.math.BigDecimal amount,
      String method) {
    if (amount == null || paymentId == null) {
      LOG.log(
          java.lang.System.Logger.Level.WARNING,
          "PaymentCaptured for order {0} ignored: missing paymentId or amount",
          orderId);
      return;
    }
    // Load the order so OrderConfirmed can carry the buyer + settled amount (loyalty accrual). The
    // event is only written when this capture fully covers the total (applyPaymentCaptured), so a
    // partial split-tender builds the row but never emits it.
    Order order = repo.findOrder(tenantId, orderId).orElse(null);
    if (order == null) {
      LOG.log(
          java.lang.System.Logger.Level.WARNING,
          "PaymentCaptured for order {0} ignored: order not found",
          orderId);
      return;
    }
    // A till sale is handed over the moment it is paid for, so the capture that completes it also
    // fulfils it, in the same transaction (SJ-D40). The event is built for every tender but only
    // written by the one that completes the sale; a partial tender or a redelivery writes nothing.
    var fulfilEvent =
        isTillSale(order.channel(), order.fulfilmentType())
            ? Events.orderFulfilled(
                tenantId, orderId, order.storeId(), repo.findOrderItems(tenantId, orderId))
            : null;
    boolean completed =
        repo.applyPaymentCaptured(
            tenantId,
            orderId,
            paymentId,
            amount,
            method,
            Events.orderConfirmed(
                tenantId,
                orderId,
                order.storeId(),
                order.channel(),
                order.customerId(),
                order.total(),
                order.currency()),
            fulfilEvent);

    // Till sales are confirmed here, not in confirmOrder, so this is where most receipts are
    // numbered. The first version of the sequence hooked only confirmOrder — which the till never
    // calls — and so numbered the sales a manager confirmed by hand and almost none of the ones
    // rung up at a till, which are the ones fiscal law is written about.
    if (completed) {
      repo.findOrder(tenantId, orderId).ifPresent(o -> issueReceiptQuietly(o, null));
    }
  }

  /**
   * A customer this shop erased (SJ-D43). Settled orders lose what identifies the customer now;
   * open ones keep their delivery details until they finish, then {@link #sweepErasures} takes
   * them.
   *
   * @param tenantId the shop that erased the customer
   * @param customerId the erased customer record
   * @param loginId the login that record was linked to, or {@code null} for a walk-in — the orders
   *     that shopper placed online are filed under it (SJ-D44)
   * @param eventId the event's id, which makes this idempotent
   * @return false when the event had already been applied
   */
  public boolean handleCustomerErased(UUID tenantId, UUID customerId, UUID loginId, UUID eventId) {
    return repo.applyCustomerErasure(
        tenantId, customerId, loginId, eventId, "order-svc/customer-erased");
  }

  // ── age verification: the due-diligence record ──────────────────────────────

  /**
   * Records one age check as the till made it (Licensing Act 2003 s.139: the defence is that all
   * reasonable precautions were taken — and a precaution nobody can show was taken is none).
   *
   * <p>The rule fields come from the request because they are product-svc's answer at the moment of
   * the check, and the record must say what the rule was then. The cashier comes from the token,
   * never the body. Store access is enforced: a cashier records checks at their own store.
   *
   * @param req the check
   * @param ctx caller identity
   * @return the record as stored
   * @throws ApiException {@code AGE_CHECK_REASON_REQUIRED} (400) for a refusal with no reason,
   *     {@code AGE_CHECK_REASON_ON_PASS} (400) for a pass carrying one, {@code
   *     AGE_CHECK_OUTCOME_UNKNOWN} / {@code AGE_CHECK_REASON_UNKNOWN} / {@code
   *     AGE_CHECK_ID_TYPE_UNKNOWN} (400) for values outside the vocabulary; {@code
   *     STORE_ACCESS_DENIED} (403) for another store
   */
  public AgeVerification recordAgeCheck(RecordAgeCheckRequest req, TenantContext ctx) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    // The same projection placeOrder consults: a store another tenant owns is refused outright,
    // whatever role the caller holds in their own — a record against someone else's store would
    // be a record in the wrong shop's register.
    if (!storeStatusRepo.isActive(tenantId, storeId))
      throw ApiException.conflict(
          "STORE_NOT_OPERATIONAL", "Store is closed, suspended or not this business's");
    String outcome = req.outcome().trim().toUpperCase(Locale.ROOT);
    if (!AgeVerification.OUTCOME_PASSED.equals(outcome)
        && !AgeVerification.OUTCOME_REFUSED.equals(outcome)) {
      throw ApiException.badRequest(
          "AGE_CHECK_OUTCOME_UNKNOWN", "outcome is PASSED or REFUSED, not " + req.outcome());
    }
    String reason = blankToNull(req.reason());
    String idType = blankToNull(req.idType());
    if (reason != null) {
      reason = reason.toUpperCase(Locale.ROOT);
      if (!AgeVerification.REASONS.contains(reason)) {
        throw ApiException.badRequest("AGE_CHECK_REASON_UNKNOWN", "unknown reason: " + reason);
      }
    }
    if (idType != null) {
      idType = idType.toUpperCase(Locale.ROOT);
      if (!AgeVerification.ID_TYPES.contains(idType)) {
        throw ApiException.badRequest("AGE_CHECK_ID_TYPE_UNKNOWN", "unknown id type: " + idType);
      }
    }
    boolean refused = AgeVerification.OUTCOME_REFUSED.equals(outcome);
    if (refused && reason == null) {
      throw ApiException.badRequest(
          "AGE_CHECK_REASON_REQUIRED", "a refusal records why: the reason is the record");
    }
    if (!refused && reason != null) {
      throw ApiException.badRequest(
          "AGE_CHECK_REASON_ON_PASS", "a sale that went ahead has no refusal reason");
    }
    if (refused && idType != null) {
      throw ApiException.badRequest(
          "AGE_CHECK_ID_TYPE_ON_REFUSAL", "an id type is recorded on a sale that went ahead");
    }
    var record =
        new AgeVerification(
            Ids.newId(),
            tenantId,
            storeId,
            ctx.userId(),
            req.posSessionId() == null ? null : Parsing.uuid(req.posSessionId(), "posSessionId"),
            Parsing.uuid(req.variantId(), "variantId"),
            req.category().trim().toUpperCase(Locale.ROOT),
            req.minimumAge(),
            req.country().trim().toUpperCase(Locale.ROOT),
            Boolean.TRUE.equals(req.storePolicy()),
            outcome,
            reason,
            idType,
            req.orderId() == null ? null : Parsing.uuid(req.orderId(), "orderId"),
            Instant.now());
    return repo.recordAgeVerification(record);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  /** A page of age checks and the cursor for the next one. */
  public record AgeVerificationPage(List<AgeVerification> items, String nextCursor) {}

  /**
   * The age-check register, newest first, for a licensing officer or a manager.
   *
   * @param tenantId owning tenant
   * @param storeIdStr one store, or {@code null}
   * @param outcome PASSED, REFUSED, or {@code null}
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @param afterCursor cursor from the previous page, or {@code null}
   * @param limit page size
   * @return the page
   * @throws ApiException {@code INVALID_CURSOR} (400) for a malformed cursor; {@code
   *     AGE_CHECK_OUTCOME_UNKNOWN} (400) for an outcome outside the vocabulary
   */
  public AgeVerificationPage listAgeChecks(
      UUID tenantId,
      String storeIdStr,
      String outcome,
      Instant from,
      Instant to,
      String afterCursor,
      int limit) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "store") : null;
    String outcomeFilter = null;
    if (outcome != null && !outcome.isBlank()) {
      outcomeFilter = outcome.trim().toUpperCase(Locale.ROOT);
      if (!AgeVerification.OUTCOME_PASSED.equals(outcomeFilter)
          && !AgeVerification.OUTCOME_REFUSED.equals(outcomeFilter)) {
        throw ApiException.badRequest(
            "AGE_CHECK_OUTCOME_UNKNOWN", "outcome is PASSED or REFUSED, not " + outcome);
      }
    }
    Instant afterCheckedAt = null;
    UUID afterId = null;
    String rawKey = com.shelfj.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCheckedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = UUID.fromString(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    List<AgeVerification> rows =
        repo.listAgeVerifications(
            tenantId, storeId, outcomeFilter, from, to, afterCheckedAt, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new AgeVerificationPage(rows, null);
    }
    List<AgeVerification> page = rows.subList(0, limit);
    AgeVerification last = page.get(page.size() - 1);
    return new AgeVerificationPage(
        page, com.shelfj.web.Cursor.encode(last.checkedAt().toString() + "|" + last.id()));
  }

  /**
   * The counts a licensing officer asks for first: how many checks, how many refusals, and why.
   *
   * @param tenantId owning tenant
   * @param storeIdStr one store, or {@code null} for the tenant
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @return the summary
   */
  public AgeVerificationSummary summariseAgeChecks(
      UUID tenantId, String storeIdStr, Instant from, Instant to) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "store") : null;
    return repo.summariseAgeVerifications(tenantId, storeId, from, to);
  }

  /** Redacts orders that have finished since their customer was erased. */
  public int sweepErasures() {
    return repo.sweepErasures();
  }

  /**
   * Cancels a still-pending order whose payment failed, releasing its stock hold.
   *
   * <p>Only acts on a PENDING order: a failure arriving after the order was confirmed by another
   * tender must not cancel a sale that has since been paid.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose payment failed
   */
  public void handlePaymentFailed(java.util.UUID tenantId, java.util.UUID orderId) {
    repo.findOrder(tenantId, orderId)
        .ifPresent(
            o -> {
              if (Order.STATUS_PENDING.equals(o.status())) {
                repo.transitionOrderStatus(
                    tenantId,
                    orderId,
                    Order.STATUS_PENDING,
                    Order.STATUS_CANCELLED,
                    "payment failed",
                    null,
                    Events.orderCancelled(tenantId, orderId, "payment failed"));
              }
            });
  }

  /**
   * Apply a refund reported by payment-svc (PaymentRefunded) to the order: accumulate the refunded
   * total and flip a sold order (CONFIRMED/FULFILLED) to PARTIALLY_REFUNDED / REFUNDED. Idempotent
   * on the payment event's {@code eventId}.
   */
  public void applyRefund(
      java.util.UUID eventId,
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.math.BigDecimal amount) {
    if (eventId == null || amount == null || amount.signum() <= 0) {
      return;
    }
    repo.applyRefundOnce(eventId, tenantId, orderId, amount);
  }

  /**
   * Cancel PENDING orders older than {@code ttlHours} — stranded pay-later orders that were never
   * paid (a paid one would have confirmed). Each cancellation emits OrderCancelled, which releases
   * the inventory hold (inventory-svc) and is a payment no-op (nothing captured). An order
   * confirmed concurrently between the scan and the update is left alone. Returns the count
   * cancelled. Driven by {@code PendingOrderSweeper}.
   */
  public int sweepExpiredPendingOrders(int ttlHours, int batchLimit) {
    int cancelled = 0;
    for (var ref : repo.findExpiredPendingOrders(ttlHours, batchLimit)) {
      try {
        repo.transitionOrderStatus(
            ref.tenantId(),
            ref.orderId(),
            Order.STATUS_PENDING,
            Order.STATUS_CANCELLED,
            "expired: payment not received",
            null,
            Events.orderCancelled(ref.tenantId(), ref.orderId(), "expired: payment not received"));
        cancelled++;
      } catch (ApiException e) {
        // Confirmed/cancelled concurrently between the scan and the conditional update — leave it.
        LOG.log(
            java.lang.System.Logger.Level.DEBUG,
            "Skipped expiring order {0}: {1}",
            ref.orderId(),
            e.getMessage());
      }
    }
    return cancelled;
  }

  // ── Gap #42: Special orders ───────────────────────────────────────────────

  /**
   * Opens a special order: goods a store does not stock, ordered in for a named customer.
   *
   * @param tenantId owning tenant
   * @param req the store, customer, items and optional currency
   * @param ctx caller context, checked for access to the store
   * @return the opened special order
   * @throws ApiException {@code SPECIAL_ORDER_NO_ITEMS} (400) when no items are supplied
   */
  public SpecialOrder createSpecialOrder(
      UUID tenantId, CreateSpecialOrderRequest req, TenantContext ctx) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest(
          "SPECIAL_ORDER_NO_ITEMS", "special order must have at least one item");

    UUID soId = Ids.newId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID customerId =
        req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    String currency = resolveCurrency(tenantId, req.currency());

    java.math.BigDecimal subtotal = java.math.BigDecimal.ZERO;
    List<SpecialOrderItem> items = new ArrayList<>();
    for (var ir : req.items()) {
      var line = ir.unitPrice().multiply(ir.qty());
      subtotal = subtotal.add(line);
      items.add(
          new SpecialOrderItem(
              Ids.newId(),
              tenantId,
              soId,
              Parsing.uuid(ir.variantId(), "variantId"),
              ir.qty(),
              ir.unitPrice(),
              line,
              ir.notes()));
    }

    java.time.LocalDate delivDate = null;
    if (req.requestedDeliveryDate() != null && !req.requestedDeliveryDate().isBlank())
      delivDate = Parsing.date(req.requestedDeliveryDate(), "requestedDeliveryDate");

    var so =
        new SpecialOrder(
            soId,
            tenantId,
            storeId,
            customerId,
            req.customerName(),
            req.customerPhone(),
            req.customerEmail(),
            req.deliveryAddress(),
            delivDate,
            req.notes(),
            SpecialOrder.STATUS_PENDING,
            subtotal,
            subtotal,
            currency,
            req.idempotencyKey(),
            Instant.now(),
            Instant.now());

    return repo.createSpecialOrder(so, items);
  }

  /** One page of special orders plus the opaque cursor for the next page (null when exhausted). */
  public record SpecialOrderPage(List<SpecialOrder> orders, String nextCursor) {}

  /**
   * Cursor-paginated list of the tenant's special orders.
   *
   * @param tenantId owning tenant
   * @param storeId restrict to one store, or {@code null}
   * @param status restrict to one status, or {@code null}
   * @param afterCursor cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page and its next cursor
   * @throws ApiException {@code INVALID_CURSOR} (400) when the cursor is malformed
   */
  public SpecialOrderPage listSpecialOrders(
      UUID tenantId, String storeIdStr, String customerIdStr, String afterCursor, int limit) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "storeId") : null;
    UUID customerId = customerIdStr != null ? Parsing.uuid(customerIdStr, "customerId") : null;
    Instant afterCreatedAt = null;
    UUID afterId = null;
    String rawKey = com.shelfj.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterCreatedAt = Instant.parse(rawKey.substring(0, sep));
        afterId = UUID.fromString(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<SpecialOrder> rows =
        repo.listSpecialOrders(tenantId, storeId, customerId, afterCreatedAt, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new SpecialOrderPage(rows, null);
    }
    List<SpecialOrder> page = rows.subList(0, limit);
    SpecialOrder last = page.get(page.size() - 1);
    return new SpecialOrderPage(
        page, com.shelfj.web.Cursor.encode(last.createdAt().toString() + "|" + last.id()));
  }

  /**
   * Reads one special order.
   *
   * <p>Also the tenant-scoping guard the other special-order methods call first.
   *
   * @param tenantId owning tenant
   * @param id the special order to read
   * @return the special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist in this
   *     tenant
   */
  public SpecialOrder getSpecialOrder(UUID tenantId, UUID id) {
    return repo.findSpecialOrder(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("SPECIAL_ORDER_NOT_FOUND", "special order not found"));
  }

  /**
   * The lines on one special order.
   *
   * @param tenantId owning tenant
   * @param soId the special order whose lines to read
   * @return the ordered lines with their prices
   */
  public List<SpecialOrderItem> getSpecialOrderItems(UUID tenantId, UUID soId) {
    getSpecialOrder(tenantId, soId);
    return repo.findSpecialOrderItems(tenantId, soId);
  }

  /**
   * Confirms a special order once the goods are on their way.
   *
   * @param tenantId owning tenant
   * @param soId the special order to confirm
   * @param userId the staff member confirming it
   * @return the confirmed special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist; a conflict
   *     when its current status does not allow confirmation
   */
  public SpecialOrder confirmSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    return repo.transitionSpecialOrderStatus(
        tenantId,
        soId,
        SpecialOrder.STATUS_PENDING,
        SpecialOrder.STATUS_CONFIRMED,
        "confirmed",
        userId);
  }

  /**
   * Marks a special order handed over to the customer.
   *
   * @param tenantId owning tenant
   * @param soId the special order to fulfil
   * @param userId the staff member handing it over
   * @return the fulfilled special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist; a conflict
   *     when its current status does not allow fulfilment
   */
  public SpecialOrder fulfilSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    return repo.transitionSpecialOrderStatus(
        tenantId,
        soId,
        SpecialOrder.STATUS_CONFIRMED,
        SpecialOrder.STATUS_FULFILLED,
        "fulfilled",
        userId);
  }

  /**
   * Cancels a special order that has not yet been handed over.
   *
   * @param tenantId owning tenant
   * @param soId the special order to cancel
   * @param userId the staff member cancelling it
   * @return the cancelled special order
   * @throws ApiException {@code SPECIAL_ORDER_NOT_FOUND} (404) when it does not exist; {@code
   *     SPECIAL_ORDER_FULFILLED} (409) when it has already been fulfilled
   */
  public SpecialOrder cancelSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    var so = getSpecialOrder(tenantId, soId);
    if (SpecialOrder.STATUS_FULFILLED.equals(so.status()))
      throw ApiException.conflict(
          "SPECIAL_ORDER_FULFILLED", "cannot cancel a fulfilled special order");
    return repo.transitionSpecialOrderStatus(
        tenantId, soId, so.status(), SpecialOrder.STATUS_CANCELLED, "cancelled", userId);
  }

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  /**
   * Writes a POSLog entry for a till sale — the audit record a POS audit expects.
   *
   * <p>POS channel only: an online order has no till transaction to log.
   *
   * @param tenantId owning tenant
   * @param orderId the sale to log
   * @param userId the cashier who rang it
   * @return the recorded entry
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; {@code
   *     POSLOG_NOT_POS} (400) when the order is not a POS sale
   */
  public PosLogEntry recordPosLog(UUID tenantId, UUID orderId, UUID userId) {
    var order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    if (!Order.CHANNEL_POS.equals(order.channel()))
      throw ApiException.badRequest("POSLOG_NOT_POS", "POSLog is only for POS channel orders");
    var entry =
        new PosLogEntry(
            Ids.newId(),
            tenantId,
            orderId,
            order.storeId(),
            userId,
            order.subtotal(),
            order.taxAmount(),
            order.discountAmount(),
            order.total(),
            order.currency(),
            order.taxExempt(),
            order.exemptReason(),
            Instant.now(),
            Instant.now());
    // Idempotent on the order: the till calls this straight after taking money, so it is on
    // the retry path — and an offline sale replays it along with everything else.
    return repo.recordPosLogOnce(entry);
  }

  // ── Staff exception report ────────────────────────────────────────────────

  /**
   * What loss prevention actually asks: which cashier is an outlier. Sums the three append-only
   * logs that record staff-initiated exceptions — discounts granted, sales voided, drawer opened
   * with no sale — against the transaction journal that says how much each person sold, so a result
   * can be read as a rate rather than a ranking of who worked the most shifts.
   *
   * <p>Rows are merged on the key rather than joined in SQL: these are four independent logs, and a
   * join would multiply a cashier's 3 discounts by their 2 voids into 6 of each. Merging also means
   * someone who appears in only one log still gets a row, which is the case that matters — the
   * cashier with no sales and four no-sales is the whole point of the report.
   *
   * <p>A null actor buckets as {@code UNATTRIBUTED} rather than being dropped. An exception nobody
   * is accountable for is the last thing this report should hide.
   */
  public List<ExceptionRow> exceptionReport(
      UUID tenantId, UUID storeId, Instant from, Instant to, ExceptionGrouping grouping) {
    boolean byActor = grouping == ExceptionGrouping.ACTOR;
    Map<String, BigDecimal[]> money = new LinkedHashMap<>();
    Map<String, long[]> counts = new LinkedHashMap<>();

    for (Object[] r : repo.aggregateDiscounts(tenantId, storeId, from, to, byActor)) {
      String k = key(r[0]);
      counts.computeIfAbsent(k, x -> new long[4])[0] = (Long) r[1];
      money.computeIfAbsent(k, x -> newMoney())[0] = (BigDecimal) r[2];
    }
    for (Object[] r : repo.aggregateVoids(tenantId, storeId, from, to, byActor)) {
      counts.computeIfAbsent(key(r[0]), x -> new long[4])[1] = (Long) r[1];
    }
    for (Object[] r : repo.aggregateNoSales(tenantId, storeId, from, to, byActor)) {
      counts.computeIfAbsent(key(r[0]), x -> new long[4])[2] = (Long) r[1];
    }
    for (Object[] r : repo.aggregateJournalledSales(tenantId, storeId, from, to, byActor)) {
      String k = key(r[0]);
      counts.computeIfAbsent(k, x -> new long[4])[3] = (Long) r[1];
      money.computeIfAbsent(k, x -> newMoney())[1] = (BigDecimal) r[2];
    }

    List<ExceptionRow> rows = new ArrayList<>();
    for (Map.Entry<String, long[]> e : counts.entrySet()) {
      long[] c = e.getValue();
      BigDecimal[] m = money.getOrDefault(e.getKey(), newMoney());
      rows.add(new ExceptionRow(e.getKey(), c[0], m[0], c[1], c[2], c[3], m[1]));
    }
    // Most exceptions first, money breaking the tie: two cashiers with three exceptions each are
    // not equally interesting if one of them discounted a hundred times more.
    rows.sort(
        java.util.Comparator.comparingLong(
                (ExceptionRow r) -> r.discounts() + r.voids() + r.noSales())
            .thenComparing(ExceptionRow::discountAmount)
            .reversed());
    return rows;
  }

  private static BigDecimal[] newMoney() {
    return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
  }

  /** Null actor or store ids are bucketed, never dropped. */
  private static String key(Object raw) {
    return raw == null ? "UNATTRIBUTED" : raw.toString();
  }

  // ---- sales by hour / by staff ----

  /**
   * Takings bucketed by hour of the trading day, on the clock of a named timezone.
   *
   * <p>The timezone is validated here rather than in the resource because getting it wrong is a
   * domain error, not a parsing one: {@code Europe/Londn} parses fine as a string and would reach
   * Postgres, which rejects it with an error that surfaces as a 500. {@link ZoneId#of} knows the
   * same tz database Postgres does, so validating with it turns that into the 400 it always was.
   *
   * @param tz an IANA zone name such as {@code Europe/London}; defaults to UTC when absent
   */
  public List<SalesByHourRow> salesByHour(
      UUID tenantId, UUID storeId, String channel, Instant from, Instant to, String tz) {
    requireOrderedPeriod(from, to);
    String normalisedChannel = normaliseChannel(channel);
    return salesAnalyticsRepo
        .salesByHour(tenantId, storeId, normalisedChannel, from, to, zone(tz))
        .stream()
        .map(
            r ->
                new SalesByHourRow(
                    r.hourOfDay(),
                    r.orders(),
                    r.grossAmount(),
                    r.discountAmount(),
                    // An hour with no orders produces no row, so the divisor is never zero.
                    r.grossAmount()
                        .divide(BigDecimal.valueOf(r.orders()), 2, RoundingMode.HALF_UP)))
        .toList();
  }

  /**
   * Takings by the cashier who rang them up, from the POS transaction journal.
   *
   * <p>Online orders have no cashier and are therefore not here at all. That is a property of the
   * data rather than a filter — the journal only ever covers the till.
   */
  public List<SalesByStaffRow> salesByStaff(
      UUID tenantId, UUID storeId, Instant from, Instant to, int limit) {
    requireOrderedPeriod(from, to);
    return salesAnalyticsRepo.salesByStaff(tenantId, storeId, from, to, limit).stream()
        .map(OrderService::withStaffRatios)
        .toList();
  }

  /**
   * Average basket and discount rate for one cashier.
   *
   * <p>The discount rate divides by what the sales would have been worth undiscounted, not by what
   * they fetched: discounting £50 off £100 is half the ticket given away, and dividing by the £50
   * that was actually taken would call it 100%.
   */
  private static SalesByStaffRow withStaffRatios(SalesByStaffRow r) {
    BigDecimal basket =
        r.sales() == 0
            ? null
            : r.grossAmount().divide(BigDecimal.valueOf(r.sales()), 2, RoundingMode.HALF_UP);
    BigDecimal undiscounted = r.grossAmount().add(r.discountAmount());
    BigDecimal rate =
        undiscounted.signum() <= 0
            ? null
            : r.discountAmount()
                .multiply(BigDecimal.valueOf(100))
                .divide(undiscounted, 1, RoundingMode.HALF_UP);
    return new SalesByStaffRow(
        r.groupKey(), r.sales(), r.grossAmount(), r.discountAmount(), basket, rate);
  }

  private static ZoneId zone(String tz) {
    if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
    try {
      return ZoneId.of(tz.trim());
    } catch (DateTimeException e) {
      // Cause preserved, as the grouping parsers do: what ZoneId disliked about the string is
      // the only thing that distinguishes a typo from an offset in a form it will not take.
      throw new ApiException(
          400,
          "ORDER_INVALID_TIMEZONE",
          "tz must be an IANA zone name such as Europe/London, or an ISO offset such as"
              + " +05:30 — got: "
              + tz,
          List.of(),
          e);
    }
  }

  /** Only the two channels exist; anything else is a caller error, not an empty result. */
  private static String normaliseChannel(String channel) {
    if (channel == null || channel.isBlank()) return null;
    String c = channel.trim().toUpperCase(Locale.ROOT);
    if (!Order.CHANNEL_ONLINE.equals(c) && !Order.CHANNEL_POS.equals(c))
      throw ApiException.badRequest(
          "ORDER_INVALID_CHANNEL", "channel must be ONLINE or POS — got: " + channel);
    return c;
  }

  private static void requireOrderedPeriod(Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to))
      throw ApiException.badRequest(
          "ORDER_INVALID_PERIOD", "from must be before to — got " + from + " and " + to);
  }

  /** One page of POSLog entries plus the opaque cursor for the next page (null when exhausted). */
  public record PosLogPage(List<PosLogEntry> entries, String nextCursor) {}

  /**
   * Cursor-paginated POSLog entries for the tenant.
   *
   * @param tenantId owning tenant
   * @param storeIdStr restrict to one store, or {@code null} for all
   * @param afterCursor cursor from the previous page, or {@code null} to start
   * @param limit page size
   * @return the page and its next cursor
   * @throws ApiException {@code INVALID_CURSOR} (400) when the cursor is malformed
   */
  public PosLogPage listPosLog(UUID tenantId, String storeIdStr, String afterCursor, int limit) {
    UUID storeId = storeIdStr != null ? Parsing.uuid(storeIdStr, "storeId") : null;
    Instant afterTransactionTs = null;
    UUID afterId = null;
    String rawKey = com.shelfj.web.Cursor.decode(afterCursor);
    if (rawKey != null) {
      int sep = rawKey.indexOf('|');
      try {
        if (sep < 0) throw new IllegalArgumentException("missing separator");
        afterTransactionTs = Instant.parse(rawKey.substring(0, sep));
        afterId = UUID.fromString(rawKey.substring(sep + 1));
      } catch (RuntimeException e) {
        throw new ApiException(400, "INVALID_CURSOR", "Malformed pagination cursor", List.of(), e);
      }
    }
    // Fetch one extra row to learn whether a further page exists without a second query.
    List<PosLogEntry> rows =
        repo.listPosLog(tenantId, storeId, afterTransactionTs, afterId, limit + 1);
    if (rows.size() <= limit) {
      return new PosLogPage(rows, null);
    }
    List<PosLogEntry> page = rows.subList(0, limit);
    PosLogEntry last = page.get(page.size() - 1);
    return new PosLogPage(
        page, com.shelfj.web.Cursor.encode(last.transactionTs().toString() + "|" + last.id()));
  }

  /**
   * The POSLog entries recorded against one sale.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose log entries to read
   * @return the entries, empty when the sale was not rung on a till
   */
  public List<PosLogEntry> getPosLogByOrder(UUID tenantId, UUID orderId) {
    return repo.findPosLogByOrder(tenantId, orderId);
  }

  // ── Gap #44: Receipts ─────────────────────────────────────────────────────

  /**
   * Records a print/email receipt event. For {@link OrderReceipt#TYPE_EMAIL}, builds a plain-text
   * receipt and delivers it via notification-svc (SMTP when configured, APP log otherwise) before
   * persisting the audit row — a send failure surfaces as 503 so the cashier is not told it emailed
   * when it did not.
   */
  public OrderReceipt generateReceipt(UUID tenantId, UUID orderId, GenerateReceiptRequest req) {
    return generateReceipt(tenantId, orderId, req, null);
  }

  /**
   * Records a print/email receipt event, optionally with the caller's context for access checks.
   *
   * <p>An email receipt is delivered <em>before</em> the audit row is written, so a send failure
   * surfaces as 503 rather than telling the cashier it emailed when it did not.
   *
   * @param tenantId owning tenant
   * @param orderId the sale being receipted
   * @param req the receipt type and, for email, the destination address
   * @param ctx caller context, or {@code null} for an internal caller with no access check
   * @return the recorded receipt event
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists; a 503 when an
   *     email receipt could not be delivered
   */
  public OrderReceipt generateReceipt(
      UUID tenantId, UUID orderId, GenerateReceiptRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    if (OrderReceipt.TYPE_EMAIL.equals(req.receiptType())
        && (req.emailedTo() == null || req.emailedTo().isBlank()))
      throw ApiException.badRequest(
          "RECEIPT_EMAIL_REQUIRED", "emailedTo required for EMAIL receipts");

    if (OrderReceipt.TYPE_EMAIL.equals(req.receiptType())) {
      List<OrderItem> items = repo.findOrderItems(tenantId, orderId);
      String body = formatReceiptEmail(order, items);
      String subject = "Your receipt — order " + shortId(order.id());
      UUID eventId = Ids.newId();
      UUID userId = ctx != null ? ctx.userId() : null;
      java.util.Set<String> roles =
          ctx != null && ctx.roles() != null ? ctx.roles() : java.util.Set.of("CASHIER");
      notifications.send(
          tenantId,
          userId,
          roles,
          req.emailedTo().trim(),
          subject,
          body,
          "POS_RECEIPT",
          eventId,
          order.customerId());
    }

    int printCount = req.printCount() != null ? req.printCount() : 1;
    var receipt =
        new OrderReceipt(
            Ids.newId(),
            tenantId,
            orderId,
            req.receiptType(),
            req.emailedTo(),
            printCount,
            Instant.now());
    return repo.insertOrderReceipt(receipt);
  }

  private static String shortId(UUID id) {
    String s = id.toString().replace("-", "");
    return s.substring(0, Math.min(8, s.length())).toUpperCase(Locale.ROOT);
  }

  private static String formatReceiptEmail(Order order, List<OrderItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append("Thank you for your purchase.\n\n");
    sb.append("Order: ").append(order.id()).append('\n');
    sb.append("Channel: ").append(order.channel()).append('\n');
    sb.append("Status: ").append(order.status()).append('\n');
    if (order.createdAt() != null) {
      sb.append("Date (UTC): ").append(order.createdAt()).append('\n');
    }
    sb.append('\n').append("Items:\n");
    if (items == null || items.isEmpty()) {
      sb.append("  (no line items)\n");
    } else {
      for (OrderItem i : items) {
        sb.append("  • ")
            .append(i.qty())
            .append(" × ")
            .append(i.variantId())
            .append(" @ ")
            .append(i.unitPrice())
            .append(" = ")
            .append(i.lineTotal())
            .append(' ')
            .append(order.currency())
            .append('\n');
      }
    }
    sb.append('\n');
    sb.append("Subtotal: ")
        .append(order.subtotal())
        .append(' ')
        .append(order.currency())
        .append('\n');
    if (order.taxAmount() != null) {
      sb.append("Tax: ")
          .append(order.taxAmount())
          .append(' ')
          .append(order.currency())
          .append('\n');
    }
    if (order.discountAmount() != null
        && order.discountAmount().compareTo(java.math.BigDecimal.ZERO) != 0) {
      sb.append("Discount: ")
          .append(order.discountAmount())
          .append(' ')
          .append(order.currency())
          .append('\n');
    }
    sb.append("Total: ").append(order.total()).append(' ').append(order.currency()).append('\n');
    sb.append("\n— Shelf-J\n");
    return sb.toString();
  }

  /**
   * Every print/email receipt event recorded against one sale.
   *
   * <p>Distinct from the fiscal receipt: this is the log of times a copy was produced, not the
   * numbered tax document.
   *
   * @param tenantId owning tenant
   * @param orderId the sale whose receipt events to read
   * @return the receipt events, empty when none were produced
   * @throws ApiException {@code ORDER_NOT_FOUND} (404) when no such order exists in this tenant
   */
  public List<OrderReceipt> listReceipts(UUID tenantId, UUID orderId) {
    repo.findOrder(tenantId, orderId)
        .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    return repo.findOrderReceipts(tenantId, orderId);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RNG = new SecureRandom();

  private String generateGiftCardCode() {
    StringBuilder sb = new StringBuilder(16);
    for (int i = 0; i < 16; i++) {
      if (i > 0 && i % 4 == 0) sb.append('-');
      sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
    }
    return sb.toString();
  }
}
