package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.FoodSafety.Kind;
import com.shelfj.inventory.domain.FoodSafety.Limits;
import com.shelfj.inventory.dto.FoodSafetyDtos.CheckTypeResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.CreateCheckTypeRequest;
import com.shelfj.inventory.dto.FoodSafetyDtos.CreatePointRequest;
import com.shelfj.inventory.dto.FoodSafetyDtos.CreateReviewRequest;
import com.shelfj.inventory.dto.FoodSafetyDtos.PointResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.PointStatusRequest;
import com.shelfj.inventory.dto.FoodSafetyDtos.ReviewResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.UpdateCheckTypeRequest;
import com.shelfj.inventory.dto.FoodSafetyDtos.UpdatePointRequest;
import com.shelfj.inventory.mapper.FoodSafetyMappers;
import com.shelfj.inventory.service.FoodSafetyService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Food-safety setup and sign-off: monitoring points, the tenant's own check types, and the
 * manager's periodic review.
 *
 * <p>Under {@code /admin/food-safety} rather than {@code /admin/inventory/} on purpose: the
 * authorisation filter gates every {@code /admin/} path to management except the warehouse subtree,
 * so a cashier cannot loosen a chiller's limit. Gated by path, not by a role check in each method,
 * so a method added here later cannot be left open by forgetting one (SJ-D10, SJ-D19).
 */
@Path("/admin/food-safety")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Food Safety Setup")
public class FoodSafetySetupResource {

  @Inject FoodSafetyService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a monitoring point",
      description =
          "Limits default to the check type's and may only be stricter: a chiller cannot be set to"
              + " 10 °C when the law says 8.")
  @APIResponse(responseCode = "201", description = "Point created")
  @APIResponse(responseCode = "409", description = "The store already has a point with that name")
  @APIResponse(responseCode = "422", description = "Limits laxer than the check type's")
  @POST
  @Path("/points")
  public Response createPoint(CreatePointRequest req) {
    Validations.validate(req);
    var point =
        service.createPoint(
            ctx.requireTenantId(),
            ctx.userId(),
            Parsing.uuid(req.storeId(), "storeId"),
            Parsing.optionalUuid(req.zoneId(), "zoneId"),
            req.name(),
            Parsing.uuid(req.checkTypeId(), "checkTypeId"),
            new Limits(req.minValue(), req.maxValue()),
            req.frequencyHours());
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                FoodSafetyMappers.toPoint(point, Instant.now()),
                ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(
      summary = "Update a monitoring point",
      description =
          "Its name, zone, limits and frequency. Its store and check type are fixed, because the"
              + " records already made against it depend on both.")
  @APIResponse(responseCode = "404", description = "No such point")
  @APIResponse(responseCode = "422", description = "Limits laxer than the check type's")
  @PUT
  @Path("/points/{id}")
  public ApiResponse<PointResponse> updatePoint(
      @PathParam("id") String id, UpdatePointRequest req) {
    Validations.validate(req);
    var point =
        service.updatePoint(
            ctx.requireTenantId(),
            Parsing.uuid(id, "id"),
            Parsing.optionalUuid(req.zoneId(), "zoneId"),
            req.name(),
            new Limits(req.minValue(), req.maxValue()),
            req.frequencyHours());
    return ApiResponse.ok(
        FoodSafetyMappers.toPoint(point, Instant.now()), ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Switch a monitoring point on",
      description = "Requires a reason, kept in a trail.")
  @APIResponse(responseCode = "409", description = "Already switched on")
  @POST
  @Path("/points/{id}/activate")
  public ApiResponse<PointResponse> activatePoint(
      @PathParam("id") String id, PointStatusRequest req) {
    return switchPoint(id, true, req);
  }

  @Operation(
      summary = "Switch a monitoring point off",
      description =
          "A decommissioned chiller stops being due. Requires a reason; its records are kept.")
  @APIResponse(responseCode = "409", description = "Already switched off")
  @POST
  @Path("/points/{id}/deactivate")
  public ApiResponse<PointResponse> deactivatePoint(
      @PathParam("id") String id, PointStatusRequest req) {
    return switchPoint(id, false, req);
  }

  @Operation(summary = "Create a check type of the tenant's own")
  @APIResponse(responseCode = "201", description = "Check type created")
  @APIResponse(responseCode = "409", description = "Code already taken")
  @POST
  @Path("/check-types")
  public Response createCheckType(CreateCheckTypeRequest req) {
    Validations.validate(req);
    var type =
        service.createCheckType(
            ctx.requireTenantId(),
            ctx.userId(),
            req.code(),
            req.name(),
            Kind.valueOf(req.kind()),
            new Limits(req.minValue(), req.maxValue()),
            req.unit(),
            req.basis());
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                FoodSafetyMappers.toCheckType(type), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(summary = "Update one of the tenant's own check types")
  @APIResponse(responseCode = "404", description = "No such check type")
  @APIResponse(responseCode = "409", description = "A platform type, which cannot be changed")
  @PUT
  @Path("/check-types/{id}")
  public ApiResponse<CheckTypeResponse> updateCheckType(
      @PathParam("id") String id, UpdateCheckTypeRequest req) {
    Validations.validate(req);
    var type =
        service.updateCheckType(
            ctx.requireTenantId(),
            Parsing.uuid(id, "id"),
            req.name(),
            new Limits(req.minValue(), req.maxValue()),
            req.basis(),
            req.active());
    return ApiResponse.ok(
        FoodSafetyMappers.toCheckType(type), ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(
      summary = "Sign off a store's records for a period",
      description =
          "The verification step HACCP requires. Stores the counts the manager was signing off —"
              + " records, failures, and failures still without a corrective action — as they"
              + " stood.")
  @APIResponse(responseCode = "201", description = "Review recorded")
  @POST
  @Path("/reviews")
  public Response createReview(CreateReviewRequest req) {
    Validations.validate(req);
    var review =
        service.createReview(
            ctx.requireTenantId(),
            ctx.requireUserId(),
            Parsing.uuid(req.storeId(), "storeId"),
            Parsing.instant(req.from(), "from"),
            Parsing.instant(req.to(), "to"),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                FoodSafetyMappers.toReview(review), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(summary = "List reviews", description = "Newest first, cursor-paginated.")
  @GET
  @Path("/reviews")
  public ApiResponse<List<ReviewResponse>> listReviews(
      @QueryParam("storeId") String storeId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    var page =
        service.listReviews(tenantId, Parsing.optionalUuid(storeId, "storeId"), after, limit);
    var items = page.items().stream().map(FoodSafetyMappers::toReview).toList();
    return ApiResponse.ok(items, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  private ApiResponse<PointResponse> switchPoint(
      String id, boolean active, PointStatusRequest req) {
    Validations.validate(req);
    var point =
        service.setPointActive(
            ctx.requireTenantId(), Parsing.uuid(id, "id"), active, req.reason(), ctx.userId());
    return ApiResponse.ok(
        FoodSafetyMappers.toPoint(point, Instant.now()), ApiResponse.Meta.of(ctx.requestId()));
  }
}
