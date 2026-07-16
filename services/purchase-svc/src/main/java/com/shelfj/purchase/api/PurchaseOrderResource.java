package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.shelfj.purchase.dto.Dtos.CreatePurchaseOrderRequest;
import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
import com.shelfj.web.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
@Path("/purchase-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Purchase Orders")
public class PurchaseOrderResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a purchase order",
      description = "Creates a DRAFT purchase order for the given supplier and store.")
  @APIResponse(responseCode = "201", description = "Purchase order created")
  @APIResponse(responseCode = "404", description = "Supplier not found")
  @POST
  public Response create(CreatePurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPurchaseOrder(req, ctx))))
        .build();
  }

  @Operation(
      summary = "List purchase orders",
      description = "Lists purchase orders for the caller's tenant.")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.shelfj.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrders(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "Get a purchase order", description = "Returns a single purchase order.")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPurchaseOrder(ctx, id)))).build();
  }

  @Operation(
      summary = "Submit a purchase order to the supplier",
      description = "Transitions a DRAFT purchase order to SUBMITTED.")
  @APIResponse(responseCode = "400", description = "Only DRAFT orders can be submitted")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  @Path("/{id}/submit")
  public Response submit(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.submitPurchaseOrder(ctx, id)))).build();
  }

  @Operation(
      summary = "Add a line to a purchase order",
      description = "Lines can only be added while the purchase order is DRAFT.")
  @APIResponse(responseCode = "201", description = "Line added")
  @APIResponse(responseCode = "400", description = "Purchase order is not DRAFT")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  @Path("/{id}/lines")
  public Response addLine(@PathParam("id") UUID id, AddPurchaseOrderLineRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPurchaseOrderLine(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "List a purchase order's lines",
      description = "Returns all lines on the given purchase order.")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/lines")
  public Response listLines(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrderLines(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
