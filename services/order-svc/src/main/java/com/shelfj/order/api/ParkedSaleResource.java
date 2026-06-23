package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.NoSaleRequest;
import com.shelfj.order.dto.Dtos.ParkSaleRequest;
import com.shelfj.order.dto.Dtos.ParkedSaleResponse;
import com.shelfj.order.service.ParkedSaleService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Parked (suspended) sales — allows a cashier to hold an in-progress sale and serve the next
 * customer, then resume. Also handles no-sale / open-drawer logging.
 */
@Path("/pos")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ParkedSaleResource {

  @Inject ParkedSaleService svc;
  @Inject TenantContext ctx;

  @POST
  @Path("/parked-sales")
  public Response park(ParkSaleRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID cashierId = ctx.userId();
    var sale = svc.park(tenantId, cashierId, req);
    return Response.status(201)
        .entity(ApiResponse.ok(sale, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/parked-sales")
  public ApiResponse<List<ParkedSaleResponse>> listParked(@QueryParam("storeId") String storeId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    UUID sid = storeId == null ? null : Parsing.uuid(storeId, "storeId");
    var sales = svc.list(tenantId, sid);
    return ApiResponse.ok(sales, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/parked-sales/{id}")
  public ApiResponse<ParkedSaleResponse> getParked(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(svc.get(tenantId, id), ApiResponse.Meta.of(ctx.requestId()));
  }

  @DELETE
  @Path("/parked-sales/{id}")
  public Response cancel(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    svc.cancel(tenantId, id);
    return Response.noContent().build();
  }

  @POST
  @Path("/no-sale")
  public Response logNoSale(NoSaleRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    UUID tenantId = ctx.requireTenantId();
    UUID cashierId = ctx.userId();
    var entry = svc.logNoSale(tenantId, cashierId, req);
    return Response.status(201)
        .entity(ApiResponse.ok(entry, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
