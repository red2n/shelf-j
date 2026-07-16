package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AddTagRequest;
import com.shelfj.inventory.dto.Dtos.CountTagRequest;
import com.shelfj.inventory.dto.Dtos.CreatePhysicalInventoryRequest;
import com.shelfj.inventory.dto.Dtos.PhysicalInventoryResponse;
import com.shelfj.inventory.dto.Dtos.PhysicalInventoryTagResponse;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/** Physical inventory counts (Gap #16). Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PhysicalInventoryResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/physical-inventories")
  public Response createPhysicalInventory(CreatePhysicalInventoryRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    var pi = service.createPhysicalInventory(tenantId, storeId, req.notes());
    var tags = service.listTags(tenantId, pi.id());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toPhysicalInventory(pi, tags)))
        .build();
  }

  @GET
  @Path("/physical-inventories")
  public ApiResponse<List<PhysicalInventoryResponse>> listPhysicalInventories(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listPhysicalInventories(tenantId, store).stream()
            .map(pi -> Mappers.toPhysicalInventory(pi, service.listTags(tenantId, pi.id())))
            .toList());
  }

  @GET
  @Path("/physical-inventories/{id}")
  public ApiResponse<PhysicalInventoryResponse> getPhysicalInventory(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var pi = service.getPhysicalInventory(tenantId, id);
    return ApiResponse.ok(Mappers.toPhysicalInventory(pi, service.listTags(tenantId, id)));
  }

  @POST
  @Path("/physical-inventories/{id}/tags")
  public ApiResponse<PhysicalInventoryTagResponse> addTag(
      @PathParam("id") UUID piId, AddTagRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID variantId = uuid(req.variantId(), "variantId");
    UUID zoneId = req.zoneId() != null ? uuid(req.zoneId(), "zoneId") : null;
    return ApiResponse.ok(
        Mappers.toTag(service.addTag(tenantId, piId, variantId, zoneId, req.systemQty())));
  }

  @POST
  @Path("/physical-inventories/{id}/tags/{tagId}/count")
  public ApiResponse<PhysicalInventoryTagResponse> countTag(
      @PathParam("id") UUID piId, @PathParam("tagId") UUID tagId, CountTagRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toTag(service.countTag(tenantId, piId, tagId, req.countedQty())));
  }

  @POST
  @Path("/physical-inventories/{id}/complete")
  public ApiResponse<PhysicalInventoryResponse> completePhysicalInventory(
      @PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var pi = service.completePhysicalInventory(tenantId, id);
    return ApiResponse.ok(Mappers.toPhysicalInventory(pi, service.listTags(tenantId, id)));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
