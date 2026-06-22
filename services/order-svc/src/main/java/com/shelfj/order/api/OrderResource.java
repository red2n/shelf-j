package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.CreateReturnRequest;
import com.shelfj.order.dto.Dtos.OrderSummaryResponse;
import com.shelfj.order.dto.Dtos.PlaceOrderRequest;
import com.shelfj.order.dto.Dtos.VoidRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
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

/** Order lifecycle: place, confirm, cancel, fulfil, void (POS), returns. */
@Path("/orders")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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
   */
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
    UUID storeId = store != null && !store.isBlank() ? UUID.fromString(store) : null;
    Instant fromInst = parseInstant(from, "from");
    Instant toInst = parseInstant(to, "to");
    int clamped = Cursor.clampLimit(limit);
    var page =
        svc.listOrders(tenantId, storeId, null, channel, status, fromInst, toInst, after, clamped);
    return ApiResponse.ok(
        page.orders().stream().map(Mappers::toSummary).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * The signed-in customer's own order history (storefront). The tenant comes from the storefront
   * header (a customer account is global) and results are filtered to the authenticated customerId,
   * so a customer can only ever see their own orders — never another customer's or the tenant's
   * full order book.
   */
  @GET
  @Path("/mine")
  public ApiResponse<List<OrderSummaryResponse>> mine(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID customerId = ctx.userId();
    if (customerId == null) {
      throw com.shelfj.web.ApiException.unauthorized(
          "NO_CUSTOMER", "a customer token is required for order history");
    }
    int clamped = Cursor.clampLimit(limit);
    var page = svc.listOrders(tenantId, null, customerId, null, null, null, null, after, clamped);
    return ApiResponse.ok(
        page.orders().stream().map(Mappers::toSummary).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

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
    var order = svc.placeOrder(req, ctx, effectiveKey);
    var items = svc.getOrderItems(order.tenantId(), order.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    var order = svc.getOrder(ctx.tenantId(), UUID.fromString(id));
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @POST
  @Path("/{id}/confirm")
  public Response confirm(@PathParam("id") String id) {
    var order = svc.confirmOrder(ctx.tenantId(), UUID.fromString(id), ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id, VoidRequest req) {
    var order =
        svc.cancelOrder(
            ctx.tenantId(), UUID.fromString(id), req != null ? req.reason() : null, ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @POST
  @Path("/{id}/fulfil")
  public Response fulfil(@PathParam("id") String id) {
    var order = svc.fulfillOrder(ctx.tenantId(), UUID.fromString(id), ctx.userId());
    var items = svc.getOrderItems(ctx.tenantId(), order.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(order, items))).build();
  }

  @GET
  @Path("/{id}/history")
  public Response history(@PathParam("id") String id) {
    var hist = svc.getOrderHistory(ctx.tenantId(), UUID.fromString(id));
    return Response.ok(ApiResponse.ok(hist.stream().map(Mappers::toDto).toList())).build();
  }

  // ── Post-void (Gap #14) ───────────────────────────────────────────────────

  @POST
  @Path("/{id}/void")
  public Response voidOrder(@PathParam("id") String id, VoidRequest req) {
    Validations.validate(req);
    var vl = svc.voidOrder(ctx.tenantId(), UUID.fromString(id), req, ctx);
    return Response.ok(ApiResponse.ok(Mappers.toDto(vl))).build();
  }

  // ── Returns (Gap #14) ─────────────────────────────────────────────────────

  @POST
  @Path("/{id}/returns")
  public Response createReturn(@PathParam("id") String id, CreateReturnRequest req) {
    Validations.validate(req);
    var ret = svc.createReturn(ctx.tenantId(), UUID.fromString(id), req, ctx);
    var retItems = svc.getReturnItems(ctx.tenantId(), ret.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(ret, retItems))).build();
  }

  // ─────────────────────────────────────────────────────────────────── utils

  private static Instant parseInstant(String s, String field) {
    return s == null || s.isBlank() ? null : com.shelfj.web.Parsing.instant(s, field);
  }

  @GET
  @Path("/{id}/returns")
  public Response listReturns(@PathParam("id") String id) {
    var returns = svc.getReturns(ctx.tenantId(), UUID.fromString(id));
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
