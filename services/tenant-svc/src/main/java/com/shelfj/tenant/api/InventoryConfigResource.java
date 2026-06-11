package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.TenantInventoryConfigResponse;
import com.shelfj.tenant.dto.Dtos.UpsertInventoryConfigRequest;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Gap #53: per-tenant inventory control parameters (lot, serial, grade, costing, UOM). */
@Path("/admin/inventory-config")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InventoryConfigResource {

  @Inject TenantService svc;
  @Inject TenantContext ctx;

  @PUT
  public Response upsert(UpsertInventoryConfigRequest req) {
    TenantInventoryConfigResponse body =
        svc.upsertInventoryConfig(
            ctx.requireTenantId(),
            req != null
                ? req
                : new UpsertInventoryConfigRequest(null, null, null, null, null, null, null, null));
    return Response.ok(ApiResponse.ok(body)).build();
  }

  @GET
  public Response get() {
    TenantInventoryConfigResponse body = svc.getInventoryConfig(ctx.requireTenantId());
    return Response.ok(ApiResponse.ok(body)).build();
  }
}
