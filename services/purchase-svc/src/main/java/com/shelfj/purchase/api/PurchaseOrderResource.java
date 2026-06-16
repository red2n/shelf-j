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

@RequestScoped
@Path("/purchase-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PurchaseOrderResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @POST
  public Response create(CreatePurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPurchaseOrder(req, ctx))))
        .build();
  }

  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.shelfj.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrders(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPurchaseOrder(ctx, id)))).build();
  }

  @POST
  @Path("/{id}/submit")
  public Response submit(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.submitPurchaseOrder(ctx, id)))).build();
  }

  @POST
  @Path("/{id}/lines")
  public Response addLine(@PathParam("id") UUID id, AddPurchaseOrderLineRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPurchaseOrderLine(ctx, id, req))))
        .build();
  }

  @GET
  @Path("/{id}/lines")
  public Response listLines(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrderLines(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
