package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.CreateSpecialOrderRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #42 — Special orders: customer orders placed at a store for future delivery. Distinct from
 * regular POS orders — no immediate inventory deduction, tracks customer contact and delivery date.
 */
@RequestScoped
@Path("/admin/special-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Special Orders")
public class SpecialOrderResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a special order",
      description =
          "Places a customer order for future delivery at a store, without immediate inventory"
              + " deduction.")
  @APIResponse(responseCode = "201", description = "Special order created")
  @APIResponse(responseCode = "400", description = "No items in the special order")
  @POST
  public Response create(CreateSpecialOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var so = svc.createSpecialOrder(tenantId, req, ctx);
    var items = svc.getSpecialOrderItems(tenantId, so.id());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(so, items))).build();
  }

  @Operation(
      summary = "List special orders",
      description =
          "Special orders for the tenant, optionally filtered by store or customer."
              + " Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of special orders")
  @GET
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("customerId") String customerId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    int clamped = Cursor.clampLimit(limit);
    var page = svc.listSpecialOrders(tenantId, storeId, customerId, after, clamped);
    var list =
        page.orders().stream()
            .map(so -> Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, so.id())))
            .toList();
    return Response.ok(
            ApiResponse.ok(list, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  @Operation(
      summary = "Get a special order by id",
      description = "Special order detail with items.")
  @APIResponse(responseCode = "200", description = "Special order found")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.getSpecialOrder(tenantId, id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  @Operation(
      summary = "Confirm a special order",
      description = "Transitions a PENDING special order to CONFIRMED.")
  @APIResponse(responseCode = "200", description = "Special order confirmed")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @POST
  @Path("/{id}/confirm")
  public Response confirm(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.confirmSpecialOrder(tenantId, id, ctx.userId());
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  @Operation(
      summary = "Fulfil a special order",
      description = "Transitions a CONFIRMED special order to FULFILLED.")
  @APIResponse(responseCode = "200", description = "Special order fulfilled")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @POST
  @Path("/{id}/fulfil")
  public Response fulfil(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.fulfilSpecialOrder(tenantId, id, ctx.userId());
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }

  @Operation(
      summary = "Cancel a special order",
      description = "Cancels a special order. A fulfilled special order cannot be cancelled.")
  @APIResponse(responseCode = "200", description = "Special order cancelled")
  @APIResponse(responseCode = "404", description = "Special order not found")
  @APIResponse(responseCode = "409", description = "Special order is already fulfilled")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var so = svc.cancelSpecialOrder(tenantId, id, ctx.userId());
    return Response.ok(ApiResponse.ok(Mappers.toDto(so, svc.getSpecialOrderItems(tenantId, id))))
        .build();
  }
}
