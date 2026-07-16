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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Kanban replenishment cards (Gap #18), including their order-modifier fields (Gap #28's kanban
 * half — matches {@code KanbanRepository}'s grouping at the data layer). Extracted from
 * AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Kanban Replenishment")
public class KanbanResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a kanban replenishment card",
      description =
          "Defines a fixed reorder quantity card for a variant at a store, optionally"
              + " sourced from another store.")
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

  @Operation(summary = "List kanban cards", description = "Filterable by store and status.")
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

  @Operation(summary = "Get a kanban card by id")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @GET
  @Path("/kanban-cards/{id}")
  public ApiResponse<KanbanCardResponse> getKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.getKanbanCard(tenantId, id)));
  }

  @Operation(
      summary = "Trigger a kanban card",
      description = "Signals the card's reorder point has been hit, moving it to TRIGGERED status.")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @POST
  @Path("/kanban-cards/{id}/trigger")
  public ApiResponse<KanbanCardResponse> triggerKanbanCard(
      @PathParam("id") UUID id, TriggerKanbanRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.triggerKanbanCard(tenantId, id, req != null ? req.notes() : null)));
  }

  @Operation(
      summary = "Mark a kanban card as replenished",
      description = "Closes the reorder cycle for a triggered card.")
  @APIResponse(responseCode = "404", description = "kanban card not found")
  @POST
  @Path("/kanban-cards/{id}/replenish")
  public ApiResponse<KanbanCardResponse> replenishKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.replenishKanbanCard(tenantId, id)));
  }

  @Operation(
      summary = "Update a kanban card's order modifiers",
      description =
          "Sets min/max order quantity and lot-size multiplier applied when computing"
              + " the actual reorder qty (Gap #28).")
  @APIResponse(responseCode = "404", description = "kanban card not found")
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
