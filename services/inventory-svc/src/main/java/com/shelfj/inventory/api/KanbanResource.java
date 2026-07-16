package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.CreateKanbanCardRequest;
import com.shelfj.inventory.dto.Dtos.KanbanCardResponse;
import com.shelfj.inventory.dto.Dtos.TriggerKanbanRequest;
import com.shelfj.inventory.dto.Dtos.UpdateOrderModifiersRequest;
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
 * Kanban replenishment cards (Gap #18), including their order-modifier fields (Gap #28's kanban
 * half — matches {@code KanbanRepository}'s grouping at the data layer). Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KanbanResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/kanban-cards")
  public Response createKanbanCard(CreateKanbanCardRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    UUID sourceStoreId =
        req.sourceStoreId() != null && !req.sourceStoreId().isBlank()
            ? uuid(req.sourceStoreId(), "sourceStoreId")
            : null;
    var card =
        service.createKanbanCard(
            tenantId,
            storeId,
            variantId,
            req.kanbanType(),
            req.reorderQty(),
            sourceStoreId,
            req.supplierRef(),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toKanbanCard(card)))
        .build();
  }

  @GET
  @Path("/kanban-cards")
  public ApiResponse<List<KanbanCardResponse>> listKanbanCards(
      @QueryParam("store") String store, @QueryParam("status") String status) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listKanbanCards(tenantId, storeId, status).stream()
            .map(Mappers::toKanbanCard)
            .toList());
  }

  @GET
  @Path("/kanban-cards/{id}")
  public ApiResponse<KanbanCardResponse> getKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.getKanbanCard(tenantId, id)));
  }

  @POST
  @Path("/kanban-cards/{id}/trigger")
  public ApiResponse<KanbanCardResponse> triggerKanbanCard(
      @PathParam("id") UUID id, TriggerKanbanRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.triggerKanbanCard(tenantId, id, req != null ? req.notes() : null)));
  }

  @POST
  @Path("/kanban-cards/{id}/replenish")
  public ApiResponse<KanbanCardResponse> replenishKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.replenishKanbanCard(tenantId, id)));
  }

  @PUT
  @Path("/kanban-cards/{id}/order-modifiers")
  public ApiResponse<KanbanCardResponse> updateKanbanModifiers(
      @PathParam("id") UUID id, UpdateOrderModifiersRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.updateKanbanOrderModifiers(
                tenantId, id, req.minOrderQty(), req.maxOrderQty(), req.lotMultiplier())));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
