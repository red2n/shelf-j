package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.AssignStaffRequest;
import com.shelfj.tenant.dto.Dtos.CreateStoreRequest;
import com.shelfj.tenant.dto.Dtos.CreateZoneRequest;
import com.shelfj.tenant.dto.Dtos.PatchStatusRequest;
import com.shelfj.tenant.dto.Dtos.StaffResponse;
import com.shelfj.tenant.dto.Dtos.StoreResponse;
import com.shelfj.tenant.dto.Dtos.TenantResponse;
import com.shelfj.tenant.dto.Dtos.UpdateStoreRequest;
import com.shelfj.tenant.dto.Dtos.UpdateTenantRequest;
import com.shelfj.tenant.dto.Dtos.UpdateZoneRequest;
import com.shelfj.tenant.dto.Dtos.ZoneResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Admin endpoints for tenant profile, stores, zones, and staff. All tenant-scoped. */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  // ── tenant profile ───────────────────────────────────────────────────────

  @GET
  @Path("/tenant")
  public ApiResponse<TenantResponse> getTenant() {
    return ApiResponse.ok(Mappers.toTenant(service.getTenant(ctx.requireTenantId())));
  }

  @PUT
  @Path("/tenant")
  public ApiResponse<TenantResponse> updateTenant(UpdateTenantRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.updateTenant(ctx.requireTenantId(), req)));
  }

  // ── stores ───────────────────────────────────────────────────────────────

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
  @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> getStore(@PathParam("storeId") UUID storeId) {
    return ApiResponse.ok(Mappers.toStore(service.getStore(ctx.requireTenantId(), storeId)));
  }

  @PUT
  @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> updateStore(
      @PathParam("storeId") UUID storeId, UpdateStoreRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toStore(service.updateStore(ctx.requireTenantId(), storeId, req)));
  }

  @PATCH
  @Path("/stores/{storeId}/status")
  public ApiResponse<StoreResponse> patchStoreStatus(
      @PathParam("storeId") UUID storeId, PatchStatusRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toStore(service.patchStoreStatus(ctx.requireTenantId(), storeId, req)));
  }

  // ── zones ────────────────────────────────────────────────────────────────

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

  @GET
  @Path("/stores/{storeId}/zones/{zoneId}")
  public ApiResponse<ZoneResponse> getZone(
      @PathParam("storeId") UUID storeId, @PathParam("zoneId") UUID zoneId) {
    return ApiResponse.ok(Mappers.toZone(service.getZone(ctx.requireTenantId(), zoneId)));
  }

  @PUT
  @Path("/stores/{storeId}/zones/{zoneId}")
  public ApiResponse<ZoneResponse> updateZone(
      @PathParam("storeId") UUID storeId, @PathParam("zoneId") UUID zoneId, UpdateZoneRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toZone(service.updateZone(ctx.requireTenantId(), zoneId, req)));
  }

  @PATCH
  @Path("/stores/{storeId}/zones/{zoneId}/status")
  public ApiResponse<ZoneResponse> patchZoneStatus(
      @PathParam("storeId") UUID storeId,
      @PathParam("zoneId") UUID zoneId,
      PatchStatusRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toZone(service.patchZoneStatus(ctx.requireTenantId(), zoneId, req)));
  }

  // ── staff ────────────────────────────────────────────────────────────────

  @POST
  @Path("/staff")
  public Response assignStaff(AssignStaffRequest req) {
    Validations.validate(req);
    service.assignStaff(ctx.requireTenantId(), req);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok("assigned")).build();
  }

  @GET
  @Path("/staff")
  public ApiResponse<List<StaffResponse>> listStaff() {
    return ApiResponse.ok(
        service.listStaff(ctx.requireTenantId()).stream().map(Mappers::toStaff).toList());
  }

  @DELETE
  @Path("/staff/{userId}")
  public ApiResponse<String> removeStaff(
      @PathParam("userId") UUID userId, @QueryParam("store") String storeParam) {
    if (storeParam == null || storeParam.isBlank()) {
      throw ApiException.badRequest("MISSING_STORE", "?store=<storeId> is required");
    }
    UUID storeId;
    try {
      storeId = UUID.fromString(storeParam);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_UUID", "store must be a UUID", List.of(), e);
    }
    service.removeStaff(ctx.requireTenantId(), userId, storeId);
    return ApiResponse.ok("removed");
  }
}
