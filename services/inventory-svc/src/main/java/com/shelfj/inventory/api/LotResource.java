package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.CreateLotLinkRequest;
import com.shelfj.inventory.dto.Dtos.ExpiringBatchResponse;
import com.shelfj.inventory.dto.Dtos.LotActionResponse;
import com.shelfj.inventory.dto.Dtos.LotGenealogyLinkResponse;
import com.shelfj.inventory.dto.Dtos.LotGenealogyTreeResponse;
import com.shelfj.inventory.dto.Dtos.LotMergeRequest;
import com.shelfj.inventory.dto.Dtos.LotSplitRequest;
import com.shelfj.inventory.dto.Dtos.UpdateGradeRequest;
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
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Lot/batch traceability: genealogy (Gap #11), split/merge (Gap #23), expiry alerts (Gap #24),
 * grade control (Gap #25). Extracted from AdminResource — bundled as one cohesive "lot-level batch
 * operations" theme; none of these share state at the controller layer (each just delegates to
 * {@code InventoryService}), so the grouping here is purely organizational.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LotResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/lot-genealogy")
  public Response createLotLink(CreateLotLinkRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var link =
        service.createLotLink(
            tenantId,
            uuid(req.parentBatchId(), "parentBatchId"),
            uuid(req.childBatchId(), "childBatchId"),
            req.qty(),
            req.relationType(),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toLotLink(link), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/lot-genealogy/batch/{batchId}/ancestors")
  public ApiResponse<LotGenealogyTreeResponse> getAncestors(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var ancestors =
        service.findAncestors(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(
        new LotGenealogyTreeResponse(batchId.toString(), ancestors, List.of()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/lot-genealogy/batch/{batchId}/descendants")
  public ApiResponse<LotGenealogyTreeResponse> getDescendants(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var descendants =
        service.findDescendants(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(
        new LotGenealogyTreeResponse(batchId.toString(), List.of(), descendants),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/lot-genealogy/batch/{batchId}/links")
  public ApiResponse<List<LotGenealogyLinkResponse>> getDirectLinks(
      @PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var links =
        service.findDirectLinks(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(links, ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/lots/split")
  public ApiResponse<LotActionResponse> splitLot(LotSplitRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result =
        service.splitLot(
            tenantId, UUID.fromString(req.sourceBatchId()), req.qty(), req.batchNo(), req.notes());
    return ApiResponse.ok(Mappers.toLotAction(result.action()));
  }

  @POST
  @Path("/lots/merge")
  public ApiResponse<LotActionResponse> mergeLot(LotMergeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result =
        service.mergeLot(
            tenantId,
            UUID.fromString(req.sourceBatchId()),
            UUID.fromString(req.targetBatchId()),
            req.qty(),
            req.notes());
    return ApiResponse.ok(Mappers.toLotAction(result.action()));
  }

  @GET
  @Path("/lots/{batchId}/actions")
  public ApiResponse<List<LotActionResponse>> listLotActions(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listLotActions(tenantId, batchId).stream().map(Mappers::toLotAction).toList());
  }

  @GET
  @Path("/batches/expiring")
  public ApiResponse<List<ExpiringBatchResponse>> listExpiringBatches(
      @QueryParam("store") String store, @QueryParam("withinDays") Integer withinDays) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    int days = withinDays == null ? 30 : withinDays;
    return ApiResponse.ok(
        service.listExpiringBatches(tenantId, storeId, days).stream()
            .map(Mappers::toExpiringBatch)
            .toList());
  }

  @PUT
  @Path("/batches/{id}/grade")
  public ApiResponse<BatchResponse> updateBatchGrade(
      @PathParam("id") UUID id, UpdateGradeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toBatch(service.updateBatchGrade(tenantId, id, req.grade())));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
