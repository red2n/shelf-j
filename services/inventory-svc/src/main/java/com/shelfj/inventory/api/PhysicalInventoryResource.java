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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Physical inventory counts (Gap #16). Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Physical Inventory")
public class PhysicalInventoryResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  /**
   * Starts a full physical inventory count for a store.
   *
   * <p>Creates the count header; tags for each variant/zone are added separately.
   *
   * @param req the request body
   * @return physical inventory created ({@code 201})
   */
  @Operation(
      summary = "Start a full physical inventory count for a store",
      description = "Creates the count header; tags for each variant/zone are added separately.")
  @APIResponse(responseCode = "201", description = "Physical inventory created")
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

  /**
   * Lists physical inventories.
   *
   * <p>Filterable by store.
   *
   * @param store the store (query parameter)
   */
  @Operation(summary = "List physical inventories", description = "Filterable by store.")
  @APIResponse(responseCode = "200", description = "List physical inventories")
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

  /**
   * Gets a physical inventory by id, with its tags.
   *
   * @param id the id (path parameter)
   * @throws com.shelfj.web.ApiException {@code 404} physical inventory not found
   */
  @Operation(summary = "Get a physical inventory by id, with its tags")
  @APIResponse(responseCode = "404", description = "Physical inventory not found")
  @GET
  @Path("/physical-inventories/{id}")
  public ApiResponse<PhysicalInventoryResponse> getPhysicalInventory(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var pi = service.getPhysicalInventory(tenantId, id);
    return ApiResponse.ok(Mappers.toPhysicalInventory(pi, service.listTags(tenantId, id)));
  }

  /**
   * Adds a count tag to a physical inventory.
   *
   * <p>Registers a variant (optionally at a zone) with its known system quantity to be counted.
   *
   * @param piId the pi id (path parameter)
   * @param req the request body
   */
  @Operation(
      summary = "Add a count tag to a physical inventory",
      description =
          "Registers a variant (optionally at a zone) with its known system quantity to"
              + " be counted.")
  @APIResponse(responseCode = "200", description = "Add a count tag to a physical inventory")
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

  /**
   * Records a counted quantity for a tag.
   *
   * <p>Sets the counted qty and computes its adjustment against system qty.
   *
   * @param piId the pi id (path parameter)
   * @param tagId the tag id (path parameter)
   * @param req the request body
   * @throws com.shelfj.web.ApiException {@code 404} physical inventory tag not found
   */
  @Operation(
      summary = "Record a counted quantity for a tag",
      description = "Sets the counted qty and computes its adjustment against system qty.")
  @APIResponse(responseCode = "404", description = "Physical inventory tag not found")
  @POST
  @Path("/physical-inventories/{id}/tags/{tagId}/count")
  public ApiResponse<PhysicalInventoryTagResponse> countTag(
      @PathParam("id") UUID piId, @PathParam("tagId") UUID tagId, CountTagRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toTag(service.countTag(tenantId, piId, tagId, req.countedQty())));
  }

  /**
   * Completes a physical inventory.
   *
   * <p>Marks the count complete and posts stock adjustments for tag variances.
   *
   * @param id the id (path parameter)
   * @throws com.shelfj.web.ApiException {@code 404} physical inventory not found
   */
  @Operation(
      summary = "Complete a physical inventory",
      description = "Marks the count complete and posts stock adjustments for tag variances.")
  @APIResponse(responseCode = "404", description = "Physical inventory not found")
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
