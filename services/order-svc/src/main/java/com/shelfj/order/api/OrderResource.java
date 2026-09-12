package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.CreateReturnRequest;
import com.shelfj.order.dto.Dtos.OrderResponse;
import com.shelfj.order.dto.Dtos.OrderSummaryResponse;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
import com.shelfj.order.dto.Dtos.VoidRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Order lifecycle: place, confirm, cancel, fulfil, void (POS), returns. */
@Path("/orders")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Orders")
public class OrderResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * List orders for this tenant. All filters are optional.
   *
   * <p>?store= UUID — filter by store ?channel= ONLINE|POS — filter by channel ?status=
   * PENDING|CONFIRMED|FULFILLED|CANCELLED|VOIDED — filter by status ?from= ISO-8601 datetime —
   * created_at >= from ?to= ISO-8601 datetime — created_at <= to ?after= opaque cursor from the
   * previous page's meta.nextCursor ?limit= 1-100 (default 20)
   *
   * @param store restrict to one store, or {@code null}
   * @param channel restrict to {@code ONLINE} or {@code POS}, or {@code null}
   * @param status restrict to one order status, or {@code null}
   * @param from inclusive ISO-8601 lower bound on creation time, or {@code null}
   * @param to exclusive ISO-8601 upper bound, or {@code null}
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of order summaries, with the next cursor in {@code meta}
   */
  @Operation(
      summary = "List orders",
      description =
          "List orders for the caller's tenant, optionally filtered by store, channel, status, and"
              + " creation-date range. Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of order summaries")
  @GET
  public ApiResponse<List<OrderSummaryResponse>> list(
      @QueryParam("store") String store,
      @QueryParam("channel") String channel,
      @QueryParam("status") String status,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store != null && !store.isBlank() ? Parsing.uuid(store, "store") : null;
    Instant fromInst = parseInstant(from, "from");
    Instant toInst = parseInstant(to, "to");
    int clamped = Cursor.clampLimit(limit);
    var page =
        svc.listOrders(
            tenantId, storeId, null, null, channel, status, fromInst, toInst, after, clamped);
    return ApiResponse.ok(
        page.orders().stream().map(Mappers::toSummary).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Every order one person placed here, for a data-portability request (UK GDPR art.20).
   *
   * <p>Staff-only, and read service-to-service by customer-svc, which assembles the whole export.
   * Both ids are accepted because a person has both: an online sale is filed under their login and
   * a till sale under the shop's customer record (SJ-D44). The shopper's own route is {@code GET
   * /customers/me/export}; this one is how it gets the orders.
   *
   * @param customer the shop's customer record for the person, or {@code null}
   * @param login the login they sign in with, or {@code null}
   * @return that person's orders, newest first, each with its lines
   */
  @Operation(
      summary = "Export one person's orders",
      description =
          "The sales half of a GDPR art.20 data export. Matches on the customer id, the login id,"
              + " or both — an online order is filed under the login and a till sale under the"
              + " customer record, and an export that knew only one would be incomplete.")
  @APIResponse(responseCode = "200", description = "The person's orders, newest first")
  @APIResponse(responseCode = "400", description = "Neither a customer nor a login was named")
  @GET
  @Path("/export")
  public ApiResponse<List<OrderResponse>> export(
      @QueryParam("customer") String customer, @QueryParam("login") String login) {
    // Belt and braces with the read filter: this route names a subject, so it is never a
    // self-read, whatever shape the path happens to match upstream.
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    UUID tenantId = ctx.requireTenantId();
    UUID customerId =
        customer != null && !customer.isBlank() ? Parsing.uuid(customer, "customer") : null;
    UUID loginId = login != null && !login.isBlank() ? Parsing.uuid(login, "login") : null;
    if (customerId == null && loginId == null) {
      throw com.shelfj.web.ApiException.badRequest(
          "ORDER_EXPORT_NO_SUBJECT", "name a customer, a login, or both");
    }
    var orders =
        svc.exportOrdersFor(tenantId, customerId, loginId).stream()
            .map(o -> Mappers.toDto(o.order(), o.items()))
            .toList();
    return ApiResponse.ok(orders, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * The signed-in customer's own order history (storefront). The tenant comes from the storefront
   * header (a customer account is global) and results are filtered to the authenticated customerId,
   * so a customer can only ever see their own orders — never another customer's or the tenant's
   * full order book.
   *
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100; clamped when absent or out of range
   * @return the page of the caller's own order summaries
   * @throws com.shelfj.web.ApiException {@code NO_CUSTOMER} (401) when the token carries no
   *     customer identity
   */
  @Operation(
      summary = "List the signed-in customer's own orders",
      description =
          "Storefront order history for the authenticated customer only — never another"
              + " customer's or the tenant's full order book. Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of the caller's own order summaries")
  @APIResponse(responseCode = "401", description = "No customer identity on the token")
  @GET
  @Path("/mine")
  public ApiResponse<List<OrderSummaryResponse>> mine(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID loginId = ctx.userId();
    if (loginId == null) {
      throw com.shelfj.web.ApiException.unauthorized(
          "NO_CUSTOMER", "a customer token is required for order history");
    }
    int clamped = Cursor.clampLimit(limit);
    // Filtered on the login, not the customer id: the shopper's own history is the orders their
    // login placed, including any placed before the customer record existed (SJ-D44).
    var page =
        svc.listOrders(tenantId, null, null, loginId, null, null, null, null, after, clamped);
    return ApiResponse.ok(
        page.orders().stream().map(Mappers::toSummary).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Places an ONLINE or POS order — the single entry point both channels share.
   *
   * <p>ONLINE orders reserve stock in inventory-svc before persisting; POS orders deduct on
   * fulfilment. Supplying an {@code Idempotency-Key} is what makes a retried checkout replay the
   * original order instead of creating a duplicate.
   *
   * @param idempotencyKey the {@code Idempotency-Key} header; the body field is a legacy fallback
   * @param req the store, channel, fulfilment type, lines and customer details
   * @return {@code 201} with the placed order
   * @throws com.shelfj.web.ApiException {@code 400} when the order has no lines or the request is
   *     malformed; {@code 403} when a POS order is placed without a cashier/manager/owner role;
   *     {@code 409} when the tenant or store is not trading
   */
  @Operation(
      summary = "Place an order",
      description =
          "Places an ONLINE or POS order. Requires an Idempotency-Key (header, or the legacy body"
              + " field as fallback) so a retried checkout replays the original order instead of"
              + " creating a duplicate. ONLINE orders reserve stock in inventory-svc before"
              + " persisting; POS orders deduct stock directly on fulfilment. POS channel requires"
              + " CASHIER, MANAGER, or OWNER.")
  @APIResponse(responseCode = "201", description = "Order placed")
  @APIResponse(
      responseCode = "400",
      description =
          "No items, missing delivery address for DELIVERY orders, invalid paymentMethod, missing"
              + " Idempotency-Key, missing price in non-enforced mode, or discount exceeds subtotal")
  @APIResponse(responseCode = "403", description = "Non-staff caller attempted to apply a discount")
  @APIResponse(
      responseCode = "409",
      description = "Tenant or store is suspended/closed, or insufficient stock to reserve")
  @POST
  public Response place(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      PlaceOrderRequest req) {
    Validations.validate(req);
    if ("POS".equalsIgnoreCase(req.channel())) {
      ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    }
    // The standard Idempotency-Key header is authoritative; the body field is a legacy fallback.
    String effectiveKey =
        idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : req.idempotencyKey();
    if (effectiveKey == null || effectiveKey.isBlank()) {
      throw ApiException.badRequest(
          "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required to place an order");
    }
    var order = svc.placeOrder(req, ctx, effectiveKey);
    var items = svc.getOrderItems(order.tenantId(), order.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  /**
   * Reads one order with its lines.
   *
   * @param id the order to read
   * @return the order and its lines
   * @throws com.shelfj.web.ApiException {@code 404} when no such order exists in the tenant or the
   *     caller may not read it — a denial is a 404 so ids cannot be probed for existence
   */
  @Operation(
      summary = "Get an order by id",
      description =
          "Staff may read any order in their tenant; an authenticated customer may only read their"
              + " own order.")
  @APIResponse(responseCode = "200", description = "Order with items")
  @APIResponse(
      responseCode = "404",
      description = "Order not found, or not owned by the calling customer")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    var order = svc.getOrder(ctx.tenantId(), Parsing.uuid(id, "id"), ctx);
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  /**
   * Moves a PENDING order to CONFIRMED and emits {@code OrderConfirmed}.
   *
   * @param id the order to confirm
   * @return the confirmed order with its lines
   * @throws com.shelfj.web.ApiException {@code 404} when the order does not exist; {@code 409} when
   *     it is not PENDING
   */
  @Operation(
      summary = "Confirm an order",
      description = "Transitions a PENDING order to CONFIRMED and emits OrderConfirmed.")
  @APIResponse(responseCode = "200", description = "Order confirmed")
  @APIResponse(responseCode = "404", description = "Order not found")
  @APIResponse(responseCode = "409", description = "Order is not in PENDING status")
  @POST
  @Path("/{id}/confirm")
  public Response confirm(@PathParam("id") String id) {
    var order = svc.confirmOrder(ctx.tenantId(), Parsing.uuid(id, "id"), ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  /**
   * Cancels a PENDING or CONFIRMED order, releasing its stock holds via {@code OrderCancelled}.
   *
   * <p>A cancel with no body at all is allowed; a body that <em>is</em> sent must carry a reason
   * rather than silently passing an empty one through.
   *
   * @param id the order to cancel
   * @param req the reason, or {@code null} to cancel without one
   * @return the cancelled order with its lines
   * @throws com.shelfj.web.ApiException {@code 404} when the order does not exist; {@code 409} when
   *     it is neither PENDING nor CONFIRMED
   */
  @Operation(
      summary = "Cancel an order",
      description =
          "Cancels a PENDING or CONFIRMED order and releases any stock holds via OrderCancelled."
              + " An optional reason may be given; if a body is sent it must include one.")
  @APIResponse(responseCode = "200", description = "Order cancelled")
  @APIResponse(responseCode = "404", description = "Order not found")
  @APIResponse(
      responseCode = "409",
      description = "Order is not PENDING or CONFIRMED, so it cannot be cancelled")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id, VoidRequest req) {
    // A cancel with no body at all is allowed (no reason given); a body that IS sent must satisfy
    // VoidRequest's @NotBlank reason rather than silently passing an empty one through.
    if (req != null) {
      Validations.validate(req);
    }
    var order =
        svc.cancelOrder(
            ctx.tenantId(),
            Parsing.uuid(id, "id"),
            req != null ? req.reason() : null,
            ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  /**
   * Prices a catalog-mode till order (SJ-D41). Management-only.
   *
   * @param id the AWAITING_PRICE order
   * @param req a unit price per variant, and the VAT
   * @return the order, now PENDING with its totals
   */
  @Operation(
      summary = "Price an order that was placed without prices",
      description =
          "A catalog-mode till places the goods and leaves the prices to a manager. Every line on"
              + " the order gets its unit price here; the totals are recomputed and the order"
              + " becomes PENDING, payable like any other. Management-only (SJ-D41).")
  @APIResponse(responseCode = "200", description = "Order priced, now PENDING")
  @APIResponse(responseCode = "400", description = "A line unpriced, unknown, or priced below zero")
  @APIResponse(responseCode = "409", description = "Order is not AWAITING_PRICE")
  @POST
  @Path("/{id}/price")
  public Response price(
      @PathParam("id") String id, com.shelfj.order.dto.Dtos.PriceOrderRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER", "PLATFORM_ADMIN");
    com.shelfj.web.Validations.validate(req);
    var order = svc.priceOrder(ctx.tenantId(), Parsing.uuid(id, "id"), req, ctx.userId(), ctx);
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  /** Parses the optional fulfil body; a JAX-RS String entity keeps an absent body legal. */
  private static final jakarta.json.bind.Jsonb FULFIL_JSON =
      jakarta.json.bind.JsonbBuilder.create();

  /**
   * Moves a CONFIRMED order to FULFILLED and emits {@code OrderFulfilled} with its lines.
   *
   * @param id the order to fulfil
   * @return the fulfilled order with its lines
   * @throws com.shelfj.web.ApiException {@code 404} when the order does not exist; {@code 409} when
   *     it is not CONFIRMED
   */
  @Operation(
      summary = "Fulfil an order",
      description =
          "Hands over the order, or with a body {lines:[{variantId, qty}]} part of it: the order"
              + " is PARTIALLY_FULFILLED until every line is complete, then FULFILLED. Each call"
              + " emits OrderFulfilled with only the quantities handed over now (SJ-D35).")
  @APIResponse(responseCode = "200", description = "Order fulfilled")
  @APIResponse(responseCode = "404", description = "Order not found")
  @APIResponse(responseCode = "409", description = "Order is not in CONFIRMED status")
  @POST
  @Path("/{id}/fulfil")
  public Response fulfil(@PathParam("id") String id, String raw) {
    // SJ-D35: with a body, only those lines and quantities are handed over now; without one —
    // and a till or a script that has always posted nothing here sends nothing — everything still
    // outstanding, which for an untouched order is the old whole fulfilment.
    com.shelfj.order.dto.Dtos.FulfilRequest req = null;
    if (raw != null && !raw.isBlank()) {
      try {
        req = FULFIL_JSON.fromJson(raw, com.shelfj.order.dto.Dtos.FulfilRequest.class);
      } catch (jakarta.json.bind.JsonbException e) {
        throw new ApiException(
            400, "VALIDATION_FAILED", "fulfil body is not valid JSON", java.util.List.of(), e);
      }
    }
    if (req != null && req.lines() != null) {
      for (var line : req.lines()) {
        com.shelfj.web.Validations.validate(line);
      }
    }
    var order = svc.fulfilOrder(ctx.tenantId(), Parsing.uuid(id, "id"), req, ctx.userId(), ctx);
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  /**
   * The order's append-only status transition log.
   *
   * @param id the order whose history to read
   * @return the transitions, oldest first
   * @throws com.shelfj.web.ApiException {@code 404} when no such order exists or the caller may not
   *     read it
   */
  @Operation(
      summary = "Get an order's status history",
      description = "Append-only status transition log for the order (object-level authorized).")
  @APIResponse(responseCode = "200", description = "Ordered list of status transitions")
  @APIResponse(
      responseCode = "404",
      description = "Order not found, or not owned by the calling customer")
  @GET
  @Path("/{id}/history")
  public Response history(@PathParam("id") String id) {
    var hist = svc.getOrderHistory(ctx.tenantId(), Parsing.uuid(id, "id"), ctx);
    return Response.ok(ApiResponse.ok(hist.stream().map(Mappers::toDto).toList())).build();
  }

  /**
   * The numbered fiscal receipt for a sale.
   *
   * <p>The number is issued when the payment completing the sale reaches order-svc, so for a few
   * seconds after a sale this answers 404 and the till waits rather than printing without one.
   *
   * @param id the sale whose receipt to read
   * @return the receipt
   * @throws com.shelfj.web.ApiException {@code 404} when the caller may not read the sale, or no
   *     receipt has been issued for it yet
   */
  @Operation(
      summary = "The receipt number issued for a sale",
      description =
          "Authorized like the order it belongs to: any staff member, or the customer who placed"
              + " it. The till reads this to print the legal receipt number. The number is issued"
              + " when the payment that completes the sale reaches order-svc, so for a few seconds"
              + " after a sale this answers 404 ORDER_RECEIPT_NOT_ISSUED and the till waits.")
  @APIResponse(responseCode = "200", description = "The receipt")
  @APIResponse(
      responseCode = "404",
      description = "No such order for this caller, or no receipt issued for it yet")
  @GET
  @Path("/{id}/fiscal-receipt")
  public Response fiscalReceipt(
      @PathParam("id") String id, @QueryParam("wait") Integer waitSeconds) {
    // ?wait=N holds the request up to N seconds (capped at 20) for the number to be issued, so a
    // till prints with the number after one round trip instead of polling and giving up.
    if (waitSeconds != null && waitSeconds > 0) {
      return Response.ok(
              ApiResponse.ok(
                  Mappers.toDto(
                      svc.awaitReceipt(ctx.tenantId(), Parsing.uuid(id, "id"), ctx, waitSeconds))))
          .build();
    }
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.receiptOf(ctx.tenantId(), Parsing.uuid(id, "id"), ctx))))
        .build();
  }

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  /**
   * Voids a POS sale after the fact, emitting {@code OrderVoided} to restock its lines.
   *
   * <p>The fiscal receipt keeps its number and gains a reason rather than disappearing — a closed
   * gap in the sequence is what a till fraud relies on.
   *
   * @param id the sale to void
   * @param req the reason, which is required
   * @return the recorded void
   * @throws com.shelfj.web.ApiException {@code 404} when the order does not exist; {@code 409} when
   *     it is not a POS-channel order
   */
  @Operation(
      summary = "Void a POS order",
      description = "Voids a POS-channel order after the fact and emits OrderVoided.")
  @APIResponse(responseCode = "200", description = "Order voided")
  @APIResponse(responseCode = "404", description = "Order not found")
  @APIResponse(responseCode = "409", description = "Void is only allowed on POS-channel orders")
  @POST
  @Path("/{id}/void")
  public Response voidOrder(@PathParam("id") String id, VoidRequest req) {
    Validations.validate(req);
    var vl = svc.voidOrder(ctx.tenantId(), Parsing.uuid(id, "id"), req, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(vl))).build();
  }

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  /**
   * Refunds one or more lines of a fulfilled order.
   *
   * <p>Cannot be used on a voided or cancelled order: goods can only come back once they were
   * handed over.
   *
   * @param id the order being returned against
   * @param req the lines and quantities coming back, the reason and the refund method
   * @return {@code 201} with the recorded return and its lines
   * @throws com.shelfj.web.ApiException {@code 404} when the order does not exist or a returned
   *     variant is not on it; {@code 409} when the order is voided or cancelled
   */
  @Operation(
      summary = "Create a return for an order",
      description =
          "Refunds one or more line items of the order. Cannot be used on a voided or cancelled"
              + " order.")
  @APIResponse(responseCode = "201", description = "Return created")
  @APIResponse(
      responseCode = "404",
      description = "Order not found, or a returned variant is not on the order")
  @APIResponse(responseCode = "409", description = "Order is voided or cancelled")
  @POST
  @Path("/{id}/returns")
  public Response createReturn(@PathParam("id") String id, CreateReturnRequest req) {
    Validations.validate(req);
    var ret = svc.createReturn(ctx.tenantId(), Parsing.uuid(id, "id"), req, ctx);
    var retItems = svc.getReturnItems(ctx.tenantId(), ret.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(ret, retItems))).build();
  }

  // ─────────────────────────────────────────────────────────────────── utils

  private static Instant parseInstant(String s, String field) {
    return s == null || s.isBlank() ? null : com.shelfj.web.Parsing.instant(s, field);
  }

  /**
   * All returns recorded against one order.
   *
   * @param id the order whose returns to read
   * @return the returns with their lines, empty when nothing has come back
   * @throws com.shelfj.web.ApiException {@code 404} when no such order exists or the caller may not
   *     read it
   */
  @Operation(
      summary = "List returns for an order",
      description = "All returns recorded against the given order.")
  @APIResponse(responseCode = "200", description = "List of returns with their items")
  @APIResponse(
      responseCode = "404",
      description = "Order not found, or not owned by the calling customer")
  @GET
  @Path("/{id}/returns")
  public Response listReturns(@PathParam("id") String id) {
    var returns = svc.getReturns(ctx.tenantId(), Parsing.uuid(id, "id"), ctx);
    var dtos =
        returns.stream()
            .map(
                r -> {
                  var ri = svc.getReturnItems(ctx.tenantId(), r.id());
                  return Mappers.toDto(r, ri);
                })
            .toList();
    return Response.ok(ApiResponse.ok(dtos)).build();
  }
}
