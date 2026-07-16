package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.CreateReasonCodeRequest;
import com.shelfj.inventory.dto.Dtos.CreateSourceTypeRequest;
import com.shelfj.inventory.dto.Dtos.ReasonCodeResponse;
import com.shelfj.inventory.dto.Dtos.SourceTypeResponse;
import com.shelfj.inventory.dto.Dtos.UpsertZoneGlMappingRequest;
import com.shelfj.inventory.dto.Dtos.ZoneGlMappingResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Transaction reason codes (Gap #21), source types (Gap #22), and zone GL mappings (Gap #31) —
 * matches {@code ReferenceDataRepository}'s grouping at the data layer. Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Reference Data")
public class ReferenceDataResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(summary = "Create a transaction reason code")
  @POST
  @Path("/reason-codes")
  public ApiResponse<ReasonCodeResponse> createReasonCode(CreateReasonCodeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toReasonCode(service.createReasonCode(tenantId, req.code(), req.description())));
  }

  @Operation(summary = "List transaction reason codes")
  @GET
  @Path("/reason-codes")
  public ApiResponse<List<ReasonCodeResponse>> listReasonCodes() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listReasonCodes(tenantId).stream().map(Mappers::toReasonCode).toList());
  }

  @Operation(summary = "Activate a transaction reason code")
  @POST
  @Path("/reason-codes/{id}/activate")
  public ApiResponse<ReasonCodeResponse> activateReasonCode(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toReasonCode(service.setReasonCodeActive(tenantId, id, true)));
  }

  @Operation(summary = "Deactivate a transaction reason code")
  @POST
  @Path("/reason-codes/{id}/deactivate")
  public ApiResponse<ReasonCodeResponse> deactivateReasonCode(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toReasonCode(service.setReasonCodeActive(tenantId, id, false)));
  }

  @Operation(summary = "Create a transaction source type")
  @POST
  @Path("/source-types")
  public ApiResponse<SourceTypeResponse> createSourceType(CreateSourceTypeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toSourceType(service.createSourceType(tenantId, req.code(), req.description())));
  }

  @Operation(summary = "List transaction source types")
  @GET
  @Path("/source-types")
  public ApiResponse<List<SourceTypeResponse>> listSourceTypes() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listSourceTypes(tenantId).stream().map(Mappers::toSourceType).toList());
  }

  @Operation(summary = "Activate a transaction source type")
  @POST
  @Path("/source-types/{id}/activate")
  public ApiResponse<SourceTypeResponse> activateSourceType(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toSourceType(service.setSourceTypeActive(tenantId, id, true)));
  }

  @Operation(summary = "Deactivate a transaction source type")
  @POST
  @Path("/source-types/{id}/deactivate")
  public ApiResponse<SourceTypeResponse> deactivateSourceType(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toSourceType(service.setSourceTypeActive(tenantId, id, false)));
  }

  @Operation(
      summary = "Upsert a zone-to-GL-account mapping",
      description =
          "Maps a store (optionally a specific zone) to a nominal ledger code for"
              + " accounting postings.")
  @PUT
  @Path("/zone-gl-mappings")
  public ApiResponse<ZoneGlMappingResponse> upsertZoneGlMapping(UpsertZoneGlMappingRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID zoneId =
        req.zoneId() == null || req.zoneId().isBlank() ? null : UUID.fromString(req.zoneId());
    var m =
        service.upsertZoneGlMapping(
            tenantId, uuid(req.storeId(), "storeId"), zoneId, req.nominalCode(), req.description());
    return ApiResponse.ok(Mappers.toZoneGlMapping(m));
  }

  @Operation(summary = "List zone-to-GL-account mappings for a store")
  @GET
  @Path("/zone-gl-mappings")
  public ApiResponse<List<ZoneGlMappingResponse>> listZoneGlMappings(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listZoneGlMappings(tenantId, storeId).stream()
            .map(Mappers::toZoneGlMapping)
            .toList());
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
