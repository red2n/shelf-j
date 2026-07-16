package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AbcAssignmentResponse;
import com.shelfj.inventory.dto.Dtos.RunAbcRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
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

/**
 * ABC analysis (Gap #9): variant classification runs and assignments. Extracted from AdminResource.
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "ABC Analysis")
public class AbcAnalysisResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Run an ABC classification compile",
      description =
          "Scores variants by value or velocity and assigns A/B/C classes based on cumulative"
              + " percentage thresholds.")
  @APIResponse(responseCode = "201", description = "Compile run persisted with its assignments")
  @APIResponse(
      responseCode = "400",
      description = "Invalid criteria (must be VALUE or VELOCITY) or invalid thresholds")
  @POST
  @Path("/abc/compile")
  public Response runAbcCompile(RunAbcRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId =
        req != null && req.storeId() != null && !req.storeId().isBlank()
            ? uuid(req.storeId(), "storeId")
            : null;
    String criteria = req != null ? req.criteria() : null;
    var thA = req != null ? req.thresholdA() : null;
    var thAB = req != null ? req.thresholdAB() : null;
    var result = service.runAbcCompile(tenantId, storeId, criteria, thA, thAB);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toAbcCompileRun(result.run())))
        .build();
  }

  @Operation(
      summary = "List ABC assignments",
      description =
          "Returns the latest ABC class assignment per variant, optionally filtered by"
              + " store or class.")
  @GET
  @Path("/abc/assignments")
  public ApiResponse<List<AbcAssignmentResponse>> listAbcAssignments(
      @QueryParam("store") String store,
      @QueryParam("class") String abcClass,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listAbcAssignments(tenantId, storeId, abcClass, limit).stream()
            .map(Mappers::toAbcAssignment)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Get a variant's ABC assignment")
  @APIResponse(responseCode = "404", description = "No ABC assignment for this store/variant")
  @GET
  @Path("/abc/assignments/{storeId}/{variantId}")
  public ApiResponse<AbcAssignmentResponse> getAbcAssignment(
      @PathParam("storeId") UUID storeId, @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toAbcAssignment(service.getAbcAssignment(tenantId, storeId, variantId)));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }
}
