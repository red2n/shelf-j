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
import com.shelfj.web.Cursor;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Admin endpoints for tenant profile, stores, zones, and staff. All tenant-scoped. */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin")
public class AdminResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  // ── tenant profile ───────────────────────────────────────────────────────

  @Operation(summary = "Get the tenant profile", description = "Returns the caller's tenant.")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @GET
  @Path("/tenant")
  public ApiResponse<TenantResponse> getTenant() {
    return ApiResponse.ok(Mappers.toTenant(service.getTenant(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Update the tenant profile",
      description = "Updates business name and legal name for the caller's tenant.")
  @APIResponse(responseCode = "404", description = "Tenant not found")
  @PUT
  @Path("/tenant")
  public ApiResponse<TenantResponse> updateTenant(UpdateTenantRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toTenant(service.updateTenant(ctx.requireTenantId(), req)));
  }

  // ── stores ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "List the tenant's stores",
      description = "Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100.")
  @GET
  @Path("/stores")
  public ApiResponse<List<StoreResponse>> listStores(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = service.listStores(ctx.requireTenantId(), after, Cursor.clampLimit(limit));
    var stores = page.items().stream().map(Mappers::toStore).toList();
    return ApiResponse.ok(stores, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "Add a store",
      description = "Adds a new store (+ its DEFAULT zone) to the caller's tenant.")
  @APIResponse(responseCode = "201", description = "Store created")
  @POST
  @Path("/stores")
  public Response addStore(CreateStoreRequest req) {
    Validations.validate(req);
    var result = service.addStore(ctx.requireTenantId(), req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toStore(result.store())))
        .build();
  }

  @Operation(summary = "Get a store", description = "Returns a single store in the tenant.")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @GET
  @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> getStore(@PathParam("storeId") UUID storeId) {
    return ApiResponse.ok(Mappers.toStore(service.getStore(ctx.requireTenantId(), storeId)));
  }

  @Operation(
      summary = "Update a store",
      description =
          "Updates store address, geo, hours, price visibility, and enabled payment methods.")
  @APIResponse(responseCode = "400", description = "Invalid enabled payment methods")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @PUT
  @Path("/stores/{storeId}")
  public ApiResponse<StoreResponse> updateStore(
      @PathParam("storeId") UUID storeId, UpdateStoreRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toStore(service.updateStore(ctx.requireTenantId(), storeId, req)));
  }

  @Operation(
      summary = "Change a store's status",
      description = "Publishes StoreStatusChanged so other services (e.g. iam-svc) can react.")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @PATCH
  @Path("/stores/{storeId}/status")
  public ApiResponse<StoreResponse> patchStoreStatus(
      @PathParam("storeId") UUID storeId, PatchStatusRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toStore(service.patchStoreStatus(ctx.requireTenantId(), storeId, req)));
  }

  // ── zones ────────────────────────────────────────────────────────────────

  @Operation(
      summary = "List a store's zones",
      description = "Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100.")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @GET
  @Path("/stores/{storeId}/zones")
  public ApiResponse<List<ZoneResponse>> listZones(
      @PathParam("storeId") UUID storeId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = service.listZones(ctx.requireTenantId(), storeId, after, Cursor.clampLimit(limit));
    var zones = page.items().stream().map(Mappers::toZone).toList();
    return ApiResponse.ok(zones, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "Add a zone to a store",
      description = "Creates an aisle/rack/cold-room/back-store zone under the given store.")
  @APIResponse(responseCode = "201", description = "Zone created")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @POST
  @Path("/stores/{storeId}/zones")
  public Response addZone(@PathParam("storeId") UUID storeId, CreateZoneRequest req) {
    Validations.validate(req);
    var zone = service.addZone(ctx.requireTenantId(), storeId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toZone(zone)))
        .build();
  }

  @Operation(summary = "Get a zone", description = "Returns a single zone in the tenant.")
  @APIResponse(responseCode = "404", description = "No such zone")
  @GET
  @Path("/stores/{storeId}/zones/{zoneId}")
  public ApiResponse<ZoneResponse> getZone(
      @PathParam("storeId") UUID storeId, @PathParam("zoneId") UUID zoneId) {
    return ApiResponse.ok(Mappers.toZone(service.getZone(ctx.requireTenantId(), zoneId)));
  }

  @Operation(summary = "Update a zone", description = "Updates a zone's name, code, or type.")
  @APIResponse(responseCode = "404", description = "No such zone")
  @PUT
  @Path("/stores/{storeId}/zones/{zoneId}")
  public ApiResponse<ZoneResponse> updateZone(
      @PathParam("storeId") UUID storeId, @PathParam("zoneId") UUID zoneId, UpdateZoneRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(Mappers.toZone(service.updateZone(ctx.requireTenantId(), zoneId, req)));
  }

  @Operation(summary = "Change a zone's status", description = "Updates a zone's status.")
  @APIResponse(responseCode = "404", description = "No such zone")
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

  @Operation(
      summary = "Assign staff to a store",
      description = "Grants a user a role at a store. userId must already exist (see iam-svc).")
  @APIResponse(responseCode = "201", description = "Staff assigned")
  @APIResponse(responseCode = "404", description = "No such store in this tenant")
  @POST
  @Path("/staff")
  public Response assignStaff(AssignStaffRequest req) {
    Validations.validate(req);
    service.assignStaff(ctx.requireTenantId(), req);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok("assigned")).build();
  }

  @Operation(
      summary = "List staff assignments",
      description = "Cursor-paginated: ?after=<meta.nextCursor>&limit=1-100.")
  @GET
  @Path("/staff")
  public ApiResponse<List<StaffResponse>> listStaff(
      @QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = service.listStaff(ctx.requireTenantId(), after, Cursor.clampLimit(limit));
    return ApiResponse.ok(
        page.items().stream().map(Mappers::toStaff).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "Remove a staff assignment",
      description = "Removes a user's role assignment at the given store (?store=<storeId>).")
  @APIResponse(responseCode = "400", description = "?store=<storeId> query parameter is missing")
  @DELETE
  @Path("/staff/{userId}")
  public ApiResponse<String> removeStaff(
      @PathParam("userId") UUID userId, @QueryParam("store") String storeParam) {
    if (storeParam == null || storeParam.isBlank()) {
      throw ApiException.badRequest("MISSING_STORE", "?store=<storeId> is required");
    }
    UUID storeId = com.shelfj.web.Parsing.uuid(storeParam, "store");
    service.removeStaff(ctx.requireTenantId(), userId, storeId);
    return ApiResponse.ok("removed");
  }
}
