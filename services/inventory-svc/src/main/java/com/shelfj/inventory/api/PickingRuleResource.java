package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.CreatePickingRuleAssignmentRequest;
import com.shelfj.inventory.dto.Dtos.CreatePickingRuleRequest;
import com.shelfj.inventory.dto.Dtos.PickingRuleAssignmentResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleResolveResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleZonePriorityResponse;
import com.shelfj.inventory.dto.Dtos.SetZonePrioritiesRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

/** Picking rules, zone priorities, and rule assignments (Gap #38). Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PickingRuleResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/picking-rules")
  public Response createPickingRule(CreatePickingRuleRequest req) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                Mappers.toPickingRule(service.createPickingRule(ctx.requireTenantId(), req))))
        .build();
  }

  @GET
  @Path("/picking-rules")
  public ApiResponse<List<PickingRuleResponse>> listPickingRules(
      @QueryParam("limit") Integer limitParam) {
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listPickingRules(ctx.requireTenantId(), limit).stream()
            .map(Mappers::toPickingRule)
            .toList());
  }

  @GET
  @Path("/picking-rules/{id}")
  public ApiResponse<PickingRuleResponse> getPickingRule(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toPickingRule(service.getPickingRule(ctx.requireTenantId(), id)));
  }

  @DELETE
  @Path("/picking-rules/{id}")
  public ApiResponse<PickingRuleResponse> deactivatePickingRule(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toPickingRule(service.deactivatePickingRule(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/picking-rules/{id}/zone-priorities")
  public ApiResponse<List<PickingRuleZonePriorityResponse>> setZonePriorities(
      @PathParam("id") UUID id, SetZonePrioritiesRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        service.setZonePriorities(ctx.requireTenantId(), id, req).stream()
            .map(Mappers::toZonePriority)
            .toList());
  }

  @GET
  @Path("/picking-rules/{id}/zone-priorities")
  public ApiResponse<List<PickingRuleZonePriorityResponse>> listZonePriorities(
      @PathParam("id") UUID id) {
    return ApiResponse.ok(
        service.listZonePriorities(ctx.requireTenantId(), id).stream()
            .map(Mappers::toZonePriority)
            .toList());
  }

  @POST
  @Path("/picking-rule-assignments")
  public Response createPickingRuleAssignment(CreatePickingRuleAssignmentRequest req) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                Mappers.toPickingRuleAssignment(
                    service.createPickingRuleAssignment(ctx.requireTenantId(), req))))
        .build();
  }

  @GET
  @Path("/picking-rule-assignments")
  public ApiResponse<List<PickingRuleAssignmentResponse>> listPickingRuleAssignments(
      @QueryParam("limit") Integer limitParam) {
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listPickingRuleAssignments(ctx.requireTenantId(), limit).stream()
            .map(Mappers::toPickingRuleAssignment)
            .toList());
  }

  @DELETE
  @Path("/picking-rule-assignments/{id}")
  public Response deletePickingRuleAssignment(@PathParam("id") UUID id) {
    service.deletePickingRuleAssignment(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  @GET
  @Path("/picking-rules/resolve")
  public ApiResponse<PickingRuleResolveResponse> resolvePickingRule(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    if (store == null || variant == null) {
      throw new ApiException(
          400, "MISSING_PARAM", "store and variant are required", List.of(), null);
    }
    return ApiResponse.ok(
        service.resolvePickingRule(tenantId, uuid(store, "store"), uuid(variant, "variant")));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
