package com.shelfj.order.service;

import com.shelfj.order.domain.Domain.GiftCard;
import com.shelfj.order.domain.Domain.GiftCardTransaction;
import com.shelfj.order.domain.Domain.Layaway;
import com.shelfj.order.domain.Domain.LayawayDeposit;
import com.shelfj.order.domain.Domain.LayawayItem;
import com.shelfj.order.domain.Domain.Order;
import com.shelfj.order.domain.Domain.OrderItem;
import com.shelfj.order.domain.Domain.OrderReceipt;
import com.shelfj.order.domain.Domain.OrderStatusHistory;
import com.shelfj.order.domain.Domain.PosLogEntry;
import com.shelfj.order.domain.Domain.PosVoidLog;
import com.shelfj.order.domain.Domain.Return;
import com.shelfj.order.domain.Domain.ReturnItem;
import com.shelfj.order.domain.Domain.SpecialOrder;
import com.shelfj.order.domain.Domain.SpecialOrderItem;
import com.shelfj.order.dto.Dtos.AddDepositRequest;
import com.shelfj.order.dto.Dtos.CreateLayawayRequest;
import com.shelfj.order.dto.Dtos.CreateReturnRequest;
import com.shelfj.order.dto.Dtos.CreateSpecialOrderRequest;
import com.shelfj.order.dto.Dtos.GenerateReceiptRequest;
import com.shelfj.order.dto.Dtos.IssueGiftCardRequest;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Business logic for order-svc. Thin resource → this service → repository. */
@ApplicationScoped
public class OrderService {

  private static final System.Logger LOG = System.getLogger(OrderService.class.getName());

  @Inject OrderRepository repo;
  @Inject TenantStatusRepository tenantStatusRepo;
  @Inject StoreStatusRepository storeStatusRepo;
  @Inject com.shelfj.order.config.ServiceConfig config;
  @Inject com.shelfj.order.client.PricingClient pricing;
  @Inject com.shelfj.order.client.InventoryClient inventory;
  @Inject com.shelfj.order.client.NotificationClient notifications;
  @Inject com.shelfj.order.client.TenantClient tenants;

  // ── Orders ────────────────────────────────────────────────────────────────

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

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
    // customer
    // explicitly via the body.
    UUID customerId;
    if (ctx.hasRole("CUSTOMER") && ctx.userId() != null) {
      customerId = ctx.userId();
    } else {
      customerId = req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    }
    String currency = req.currency() != null ? req.currency() : "USD";
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
    UUID orderId = UUID.randomUUID();

    List<UUID> variantIds =
        req.items().stream().map(ir -> Parsing.uuid(ir.variantId(), "variantId")).toList();
    // Gap #63: when enforcement is on, the price comes from pricing-svc — the client-supplied
    // unitPrice is ignored. When off (local dev / unseeded rigs), the client price is trusted.
    // One batched call resolves every line instead of one cross-service HTTP call per line.
    List<com.shelfj.order.client.PricingClient.ResolvedLine> resolvedLines = null;
    if (enforcePricing) {
      var lineRequests =
          new ArrayList<com.shelfj.order.client.PricingClient.LineRequest>(variantIds.size());
      for (int i = 0; i < variantIds.size(); i++) {
        lineRequests.add(
            new com.shelfj.order.client.PricingClient.LineRequest(
                variantIds.get(i), req.items().get(i).qty()));
      }
      resolvedLines = pricing.resolveLines(tenantId, lineRequests, storeId, req.channel());
    }

    for (int i = 0; i < req.items().size(); i++) {
      var ir = req.items().get(i);
      UUID variantId = variantIds.get(i);
      BigDecimal unitPrice;
      if (enforcePricing) {
        var resolved = resolvedLines.get(i);
        unitPrice = resolved.unitPrice();
        serverTax = serverTax.add(resolved.vatAmount().multiply(ir.qty()));
      } else {
        if (ir.unitPrice() == null)
          throw ApiException.badRequest(
              "ORDER_PRICE_REQUIRED", "unitPrice is required for variant " + ir.variantId());
        unitPrice = ir.unitPrice();
      }
      BigDecimal line = unitPrice.multiply(ir.qty());
      subtotal = subtotal.add(line);
      items.add(
          new OrderItem(
              UUID.randomUUID(),
              tenantId,
              orderId,
              variantId,
              ir.qty(),
              unitPrice,
              line,
              ir.notes()));
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

    boolean staff = ctx.hasRole("CASHIER") || ctx.hasRole("MANAGER") || ctx.hasRole("OWNER");

    BigDecimal tax;
    BigDecimal disc;
    if (enforcePricing) {
      // Tax is derived server-side from pricing-svc's per-line VAT; any promotion discount is
      // already baked into the resolved unitPrice above, so there is no separate discount left to
      // apply. Client-supplied taxAmount/discountAmount are never trusted here.
      tax = serverTax.setScale(2, java.math.RoundingMode.HALF_UP);
      disc = BigDecimal.ZERO;
    } else {
      tax = req.taxAmount() != null ? req.taxAmount() : BigDecimal.ZERO;
      disc = req.discountAmount() != null ? req.discountAmount() : BigDecimal.ZERO;
      // Manual discounts are a staff privilege (POS). A non-staff caller (online/guest checkout)
      // self-applying a discount would let them name their own price.
      if (!staff && disc.signum() != 0)
        throw ApiException.forbidden(
            "ORDER_DISCOUNT_NOT_ALLOWED", "discounts can only be applied by staff");
    }
    if (disc.compareTo(subtotal) > 0)
      throw ApiException.badRequest(
          "ORDER_DISCOUNT_EXCEEDS_SUBTOTAL",
          "discountAmount " + disc + " exceeds order subtotal " + subtotal);
    BigDecimal total = subtotal.add(tax).subtract(disc);

    boolean taxExempt = req.taxExempt() != null && req.taxExempt();
    Order order =
        new Order(
            orderId,
            tenantId,
            storeId,
            customerId,
            req.channel(),
            fulfilment,
            Order.STATUS_PENDING,
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
            paymentMethod);

    try {
      return repo.createOrder(
          order, items, Events.orderPlaced(tenantId, orderId, req.channel(), customerId, storeId));
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

  /** One page of orders plus the opaque cursor for the next page (null when exhausted). */
  public record OrderPage(List<Order> orders, String nextCursor) {}

  public OrderPage listOrders(
      UUID tenantId,
      UUID storeId,
      UUID customerId,
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

  public Order getOrder(UUID tenantId, UUID orderId) {
    return repo.findOrder(tenantId, orderId)
        .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
  }

  /** Order-by-id read for the API: tenant scope plus object-level authorization. */
  public Order getOrder(UUID tenantId, UUID orderId, TenantContext ctx) {
    Order order = getOrder(tenantId, orderId);
    requireReadAccess(order, ctx);
    return order;
  }

  /**
   * Object-level authorization for order-by-id reads (mirrors CartService.requireOwnership): an
   * order id alone is not proof of ownership. Staff may read any order in their tenant; an
   * authenticated customer may only read an order placed against their own customerId. Denials are
   * 404 (not 403) so order ids can't be probed for existence. A caller with no principal at all (no
   * userId, no roles — only X-Tenant-Id) is a service-to-service lookup (e.g. payment-svc verifying
   * an online payment claim); the gateway never forwards a tenant to these paths without a verified
   * user, so that shape cannot originate from outside.
   */
  private static void requireReadAccess(Order order, TenantContext ctx) {
    if (isStaff(ctx)) return;
    if (ctx.userId() == null && ctx.roles().isEmpty()) return;
    if (order.customerId() == null || !order.customerId().equals(ctx.userId()))
      throw ApiException.notFound("ORDER_NOT_FOUND", "order not found");
  }

  private static boolean isStaff(TenantContext ctx) {
    return ctx.hasRole("PLATFORM_ADMIN")
        || ctx.hasRole("OWNER")
        || ctx.hasRole("MANAGER")
        || ctx.hasRole("STOREKEEPER")
        || ctx.hasRole("CASHIER");
  }

  /** SIM↔POS projection rows for POS screens (gap #50). */
  public List<com.shelfj.order.domain.Domain.PosStockPosition> listStockPositions(
      UUID tenantId, UUID storeId, UUID variantId, int limit) {
    return repo.findStockPositions(tenantId, storeId, variantId, limit);
  }

  public List<OrderItem> getOrderItems(UUID tenantId, UUID orderId) {
    return repo.findOrderItems(tenantId, orderId);
  }

  public List<OrderStatusHistory> getOrderHistory(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return repo.findOrderHistory(tenantId, orderId);
  }

  public Order confirmOrder(UUID tenantId, UUID orderId, UUID userId) {
    // Load the order so OrderConfirmed can carry the buyer + settled amount (loyalty accrual).
    Order order = getOrder(tenantId, orderId);
    return repo.transitionOrderStatus(
        tenantId,
        orderId,
        Order.STATUS_PENDING,
        Order.STATUS_CONFIRMED,
        "confirmed",
        userId,
        Events.orderConfirmed(
            tenantId,
            orderId,
            order.storeId(),
            order.channel(),
            order.customerId(),
            order.total(),
            order.currency()));
  }

  public Order cancelOrder(UUID tenantId, UUID orderId, String reason, UUID userId) {
    // PENDING covers pay-later online orders awaiting confirmation; both states must be
    // cancellable so their stock holds get released (inventory-svc reacts to OrderCancelled).
    Order order = getOrder(tenantId, orderId);
    if (!Order.STATUS_PENDING.equals(order.status())
        && !Order.STATUS_CONFIRMED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_CANNOT_CANCEL", "only PENDING or CONFIRMED orders can be cancelled");
    return repo.transitionOrderStatus(
        tenantId,
        orderId,
        order.status(),
        Order.STATUS_CANCELLED,
        reason,
        userId,
        Events.orderCancelled(tenantId, orderId, reason));
  }

  public Order fulfillOrder(UUID tenantId, UUID orderId, UUID userId) {
    Order order = getOrder(tenantId, orderId);
    List<OrderItem> items = repo.findOrderItems(tenantId, orderId);
    return repo.transitionOrderStatus(
        tenantId,
        orderId,
        Order.STATUS_CONFIRMED,
        Order.STATUS_FULFILLED,
        "fulfilled",
        userId,
        Events.orderFulfilled(tenantId, orderId, order.storeId(), items));
  }

  // ── Returns ───────────────────────────────────────────────────────────────

  public Return createReturn(
      UUID tenantId, UUID orderId, CreateReturnRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    ctx.requireStoreAccess(order.storeId());

    if (Order.STATUS_CANCELLED.equals(order.status()) || Order.STATUS_VOIDED.equals(order.status()))
      throw ApiException.conflict(
          "ORDER_CANNOT_RETURN", "cannot return a voided or cancelled order");

    List<OrderItem> orderItems = repo.findOrderItems(tenantId, orderId);
    UUID returnId = UUID.randomUUID();
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
              UUID.randomUUID(),
              tenantId,
              returnId,
              variantId,
              ri.qty(),
              refundAmt,
              ri.condition()));
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

  public List<Return> getReturns(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(getOrder(tenantId, orderId), ctx);
    return repo.findReturns(tenantId, orderId);
  }

  public List<ReturnItem> getReturnItems(UUID tenantId, UUID returnId) {
    return repo.findReturnItems(tenantId, returnId);
  }

  // ── Post-void ─────────────────────────────────────────────────────────────

  public PosVoidLog voidOrder(UUID tenantId, UUID orderId, VoidRequest req, TenantContext ctx) {
    Order order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    ctx.requireStoreAccess(order.storeId());
    if (!Order.CHANNEL_POS.equals(order.channel()))
      throw ApiException.conflict("ORDER_VOID_ONLY_POS", "void is only allowed on POS orders");
    return repo.voidOrder(
        tenantId,
        orderId,
        order.storeId(),
        req.reason(),
        ctx.userId(),
        Events.orderVoided(tenantId, orderId));
  }

  // ── Layaway ───────────────────────────────────────────────────────────────

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
    UUID layawayId = UUID.randomUUID();

    BigDecimal total = BigDecimal.ZERO;
    List<LayawayItem> items = new ArrayList<>();
    for (var li : req.items()) {
      BigDecimal line = li.unitPrice().multiply(li.qty());
      total = total.add(line);
      items.add(
          new LayawayItem(
              UUID.randomUUID(),
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

    Instant dueDate = req.dueDate() != null ? Instant.parse(req.dueDate()) : null;
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
            UUID.randomUUID(),
            tenantId,
            layawayId,
            req.initialDeposit(),
            req.paymentMethod(),
            null,
            Instant.now());

    return repo.createLayaway(layaway, items, deposit, Events.layawayCreated(tenantId, layawayId));
  }

  public Layaway getLayaway(UUID tenantId, UUID layawayId) {
    return repo.findLayaway(tenantId, layawayId)
        .orElseThrow(() -> ApiException.notFound("LAYAWAY_NOT_FOUND", "layaway not found"));
  }

  public List<LayawayItem> getLayawayItems(UUID tenantId, UUID layawayId) {
    return repo.findLayawayItems(tenantId, layawayId);
  }

  public List<LayawayDeposit> getLayawayDeposits(UUID tenantId, UUID layawayId) {
    return repo.findLayawayDeposits(tenantId, layawayId);
  }

  public Layaway addDeposit(
      UUID tenantId, UUID layawayId, AddDepositRequest req, TenantContext ctx) {
    LayawayDeposit deposit =
        new LayawayDeposit(
            UUID.randomUUID(),
            tenantId,
            layawayId,
            req.amount(),
            req.paymentMethod(),
            req.reference(),
            Instant.now());
    return repo.addDeposit(tenantId, layawayId, deposit);
  }

  public Layaway completeLayaway(UUID tenantId, UUID layawayId, TenantContext ctx) {
    return repo.completeLayaway(tenantId, layawayId, Events.layawayCompleted(tenantId, layawayId));
  }

  public Layaway cancelLayaway(UUID tenantId, UUID layawayId, String reason, TenantContext ctx) {
    return repo.cancelLayaway(
        tenantId, layawayId, reason, Events.layawayCancelled(tenantId, layawayId));
  }

  // ── Gift cards ────────────────────────────────────────────────────────────

  public GiftCard issueGiftCard(IssueGiftCardRequest req, TenantContext ctx) {
    // requireTenantId (not the nullable tenantId()) so issuing a gift card without a tenant in
    // context fails 401 rather than minting stored value against a null-tenant row.
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID gcId = UUID.randomUUID();
    String code = generateGiftCardCode();
    String currency = req.currency() != null ? req.currency() : "USD";
    Instant expiresAt = req.expiresAt() != null ? Instant.parse(req.expiresAt()) : null;

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
            UUID.randomUUID(),
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

  public GiftCard getGiftCard(UUID tenantId, String code) {
    return repo.findGiftCardByCode(tenantId, code)
        .orElseThrow(() -> ApiException.notFound("GIFT_CARD_NOT_FOUND", "gift card not found"));
  }

  public GiftCard reloadGiftCard(UUID tenantId, String code, ReloadGiftCardRequest req) {
    return repo.reloadGiftCard(tenantId, code, req.amount(), req.reference());
  }

  public GiftCard redeemGiftCard(UUID tenantId, String code, RedeemGiftCardRequest req) {
    UUID orderId = req.orderId() != null ? Parsing.uuid(req.orderId(), "orderId") : null;
    return repo.redeemGiftCard(tenantId, code, req.amount(), orderId, req.reference());
  }

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
  public void handlePaymentCaptured(
      java.util.UUID tenantId,
      java.util.UUID orderId,
      java.util.UUID paymentId,
      java.math.BigDecimal amount) {
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
    repo.applyPaymentCaptured(
        tenantId,
        orderId,
        paymentId,
        amount,
        Events.orderConfirmed(
            tenantId,
            orderId,
            order.storeId(),
            order.channel(),
            order.customerId(),
            order.total(),
            order.currency()));
  }

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

  public SpecialOrder createSpecialOrder(
      UUID tenantId, CreateSpecialOrderRequest req, TenantContext ctx) {
    if (req.items() == null || req.items().isEmpty())
      throw ApiException.badRequest(
          "SPECIAL_ORDER_NO_ITEMS", "special order must have at least one item");

    UUID soId = UUID.randomUUID();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID customerId =
        req.customerId() != null ? Parsing.uuid(req.customerId(), "customerId") : null;
    String currency = req.currency() != null ? req.currency() : "GBP";

    java.math.BigDecimal subtotal = java.math.BigDecimal.ZERO;
    List<SpecialOrderItem> items = new ArrayList<>();
    for (var ir : req.items()) {
      var line = ir.unitPrice().multiply(ir.qty());
      subtotal = subtotal.add(line);
      items.add(
          new SpecialOrderItem(
              UUID.randomUUID(),
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
      delivDate = java.time.LocalDate.parse(req.requestedDeliveryDate());

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

  public SpecialOrder getSpecialOrder(UUID tenantId, UUID id) {
    return repo.findSpecialOrder(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("SPECIAL_ORDER_NOT_FOUND", "special order not found"));
  }

  public List<SpecialOrderItem> getSpecialOrderItems(UUID tenantId, UUID soId) {
    getSpecialOrder(tenantId, soId);
    return repo.findSpecialOrderItems(tenantId, soId);
  }

  public SpecialOrder confirmSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    return repo.transitionSpecialOrderStatus(
        tenantId,
        soId,
        SpecialOrder.STATUS_PENDING,
        SpecialOrder.STATUS_CONFIRMED,
        "confirmed",
        userId);
  }

  public SpecialOrder fulfilSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    return repo.transitionSpecialOrderStatus(
        tenantId,
        soId,
        SpecialOrder.STATUS_CONFIRMED,
        SpecialOrder.STATUS_FULFILLED,
        "fulfilled",
        userId);
  }

  public SpecialOrder cancelSpecialOrder(UUID tenantId, UUID soId, UUID userId) {
    var so = getSpecialOrder(tenantId, soId);
    if (SpecialOrder.STATUS_FULFILLED.equals(so.status()))
      throw ApiException.conflict(
          "SPECIAL_ORDER_FULFILLED", "cannot cancel a fulfilled special order");
    return repo.transitionSpecialOrderStatus(
        tenantId, soId, so.status(), SpecialOrder.STATUS_CANCELLED, "cancelled", userId);
  }

  // ── Gap #43: POSLog ───────────────────────────────────────────────────────

  public PosLogEntry recordPosLog(UUID tenantId, UUID orderId, UUID userId) {
    var order =
        repo.findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    if (!Order.CHANNEL_POS.equals(order.channel()))
      throw ApiException.badRequest("POSLOG_NOT_POS", "POSLog is only for POS channel orders");
    var entry =
        new PosLogEntry(
            UUID.randomUUID(),
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
    return repo.insertPosLogEntry(entry);
  }

  /** One page of POSLog entries plus the opaque cursor for the next page (null when exhausted). */
  public record PosLogPage(List<PosLogEntry> entries, String nextCursor) {}

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
      UUID eventId = UUID.randomUUID();
      UUID userId = ctx != null ? ctx.userId() : null;
      java.util.Set<String> roles =
          ctx != null && ctx.roles() != null ? ctx.roles() : java.util.Set.of("CASHIER");
      notifications.send(
          tenantId, userId, roles, req.emailedTo().trim(), subject, body, "POS_RECEIPT", eventId);
    }

    int printCount = req.printCount() != null ? req.printCount() : 1;
    var receipt =
        new OrderReceipt(
            UUID.randomUUID(),
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
