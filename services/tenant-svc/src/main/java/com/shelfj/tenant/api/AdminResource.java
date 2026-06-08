package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.AssignStaffRequest;
import com.shelfj.tenant.dto.Dtos.CreateStoreRequest;
import com.shelfj.tenant.dto.Dtos.CreateZoneRequest;
import com.shelfj.tenant.dto.Dtos.StoreResponse;
import com.shelfj.tenant.dto.Dtos.ZoneResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Admin endpoints for stores, zones, and staff. All tenant-scoped (tenantId from JWT/context). */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @GET
  @Path("/stores")
  public ApiResponse<List<StoreResponse>> listStores() {
    var stores = service.listStores(ctx.requireTenantId()).stream().map(Mappers::toStore).toList();
    return ApiResponse.ok(stores, ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/stores")
  public Response addStore(CreateStoreRequest req) {
    Validations.validate(req);
    var result = service.addStore(ctx.requireTenantId(), req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toStore(result.store())))
        .build();
  }

  @GET
  @Path("/stores/{storeId}/zones")
  public ApiResponse<List<ZoneResponse>> listZones(@PathParam("storeId") UUID storeId) {
    var zones =
        service.listZones(ctx.requireTenantId(), storeId).stream().map(Mappers::toZone).toList();
    return ApiResponse.ok(zones, ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/stores/{storeId}/zones")
  public Response addZone(@PathParam("storeId") UUID storeId, CreateZoneRequest req) {
    Validations.validate(req);
    var zone = service.addZone(ctx.requireTenantId(), storeId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toZone(zone)))
        .build();
  }

  @POST
  @Path("/staff")
  public Response assignStaff(AssignStaffRequest req) {
    Validations.validate(req);
    service.assignStaff(ctx.requireTenantId(), req);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok("assigned")).build();
  }
}
