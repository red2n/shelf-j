package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.FoodSafety.FoodDisposition;
import com.shelfj.inventory.domain.FoodSafety.Result;
import com.shelfj.inventory.dto.FoodSafetyDtos.CheckRecordResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.CheckTypeResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.CorrectiveActionRequest;
import com.shelfj.inventory.dto.FoodSafetyDtos.PointResponse;
import com.shelfj.inventory.dto.FoodSafetyDtos.RecordCheckRequest;
import com.shelfj.inventory.mapper.FoodSafetyMappers;
import com.shelfj.inventory.service.FoodSafetyService;
import com.shelfj.inventory.service.FoodSafetyService.RecordCheck;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.HttpHeaders;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
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
 * Food-safety checks as store staff make them: the day's points, recording a check, the diary, and
 * corrective actions.
 *
 * <p>Under {@code /admin/inventory/} so the authorisation filter admits any staff role by path — a
 * storekeeper or a deli cashier takes the chiller reading. Setting up points and signing off
 * reviews is management work and lives in {@link FoodSafetySetupResource}, under a path the filter
 * gates to management.
 */
@Path("/admin/inventory/food-safety")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Food Safety")
public class FoodSafetyResource {

  @Inject FoodSafetyService service;
  @Inject TenantContext ctx;

  /**
   * Lists a store's monitoring points.
   *
   * <p>Each point with its check type, limits, last check and due status (OK, DUE, OVERDUE), and
   * how many of its failures have no corrective action yet.
   *
   * @param storeId the store id (query parameter)
   * @param includeInactive the include inactive (query parameter)
   */
  @Operation(
      summary = "List a store's monitoring points",
      description =
          "Each point with its check type, limits, last check and due status (OK, DUE, OVERDUE),"
              + " and how many of its failures have no corrective action yet.")
  @APIResponse(responseCode = "200", description = "List a store's monitoring points")
  @GET
  @Path("/points")
  public ApiResponse<List<PointResponse>> listPoints(
      @QueryParam("storeId") String storeId,
      @QueryParam("includeInactive") boolean includeInactive) {
    UUID tenantId = ctx.requireTenantId();
    Instant now = Instant.now();
    var points =
        service.listPoints(tenantId, Parsing.uuid(storeId, "storeId"), includeInactive).stream()
            .map(p -> FoodSafetyMappers.toPoint(p, now))
            .toList();
    return ApiResponse.ok(points, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Lists check types.
   *
   * <p>The platform's reference types, which carry the statutory limits, and the tenant's own.
   */
  @Operation(
      summary = "List check types",
      description =
          "The platform's reference types, which carry the statutory limits, and the tenant's own.")
  @APIResponse(responseCode = "200", description = "List check types")
  @GET
  @Path("/check-types")
  public ApiResponse<List<CheckTypeResponse>> listCheckTypes() {
    UUID tenantId = ctx.requireTenantId();
    var types =
        service.listCheckTypes(tenantId).stream().map(FoodSafetyMappers::toCheckType).toList();
    return ApiResponse.ok(types, ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Records a check.
   *
   * <p>A reading for a temperature check or passed for a pass/fail check. The server judges the
   * result against the limits in force and stores those limits with the record. A failure alerts
   * the store. Supports Idempotency-Key, so a tablet retrying on poor Wi-Fi records the check once.
   *
   * @param idempotencyKey the idempotency key (header parameter)
   * @param req the request body
   * @return check recorded ({@code 201})
   * @throws com.shelfj.web.ApiException {@code 403} caller is not assigned to the point's store;
   *     {@code 409} the point is switched off
   */
  @Operation(
      summary = "Record a check",
      description =
          "A reading for a temperature check or passed for a pass/fail check. The server judges"
              + " the result against the limits in force and stores those limits with the record."
              + " A failure alerts the store. Supports Idempotency-Key, so a tablet retrying on"
              + " poor Wi-Fi records the check once.")
  @APIResponse(responseCode = "201", description = "Check recorded")
  @APIResponse(
      responseCode = "200",
      description = "Replay of a check already recorded with this key")
  @APIResponse(responseCode = "403", description = "Caller is not assigned to the point's store")
  @APIResponse(responseCode = "409", description = "The point is switched off")
  @POST
  @Path("/records")
  public Response recordCheck(
      @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey, RecordCheckRequest req) {
    Validations.validate(req);
    var recorded =
        service.recordCheck(
            new RecordCheck(
                ctx.requireTenantId(),
                ctx.requireUserId(),
                Parsing.uuid(req.pointId(), "pointId"),
                req.value(),
                req.passed(),
                req.notes(),
                req.refType(),
                Parsing.optionalUuid(req.refId(), "refId"),
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey),
            ctx::requireStoreAccess);
    return Response.status(recorded.replayed() ? Response.Status.OK : Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                FoodSafetyMappers.toRecord(recorded.entry(), null),
                ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Lists the check diary.
   *
   * <p>Newest first, cursor-paginated. from is inclusive and to exclusive. openOnly keeps failures
   * with no corrective action.
   *
   * @param storeId the store id (query parameter)
   * @param pointId the point id (query parameter)
   * @param result the result (query parameter)
   * @param openOnly the open only (query parameter)
   * @param from the from (query parameter)
   * @param to the to (query parameter)
   * @param after the after (query parameter)
   * @param limit the limit (query parameter)
   */
  @Operation(
      summary = "List the check diary",
      description =
          "Newest first, cursor-paginated. from is inclusive and to exclusive. openOnly keeps"
              + " failures with no corrective action.")
  @APIResponse(responseCode = "200", description = "List the check diary")
  @GET
  @Path("/records")
  public ApiResponse<List<CheckRecordResponse>> listRecords(
      @QueryParam("storeId") String storeId,
      @QueryParam("pointId") String pointId,
      @QueryParam("result") String result,
      @QueryParam("openOnly") boolean openOnly,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    var page =
        service.listDiary(
            tenantId,
            Parsing.optionalUuid(storeId, "storeId"),
            Parsing.optionalUuid(pointId, "pointId"),
            parseResult(result),
            openOnly,
            Parsing.optionalInstant(from, "from"),
            Parsing.optionalInstant(to, "to"),
            after,
            limit);
    var items = page.items().stream().map(e -> FoodSafetyMappers.toRecord(e, null)).toList();
    return ApiResponse.ok(items, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Gets a check record with its corrective actions.
   *
   * @param id the id (path parameter)
   * @throws com.shelfj.web.ApiException {@code 404} no such record
   */
  @Operation(summary = "Get a check record with its corrective actions")
  @APIResponse(responseCode = "404", description = "No such record")
  @GET
  @Path("/records/{id}")
  public ApiResponse<CheckRecordResponse> getRecord(@PathParam("id") String id) {
    UUID tenantId = ctx.requireTenantId();
    var detail = service.getRecord(tenantId, Parsing.uuid(id, "id"));
    return ApiResponse.ok(
        FoodSafetyMappers.toRecord(detail.entry(), detail.actions()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Records a corrective action on a failed check.
   *
   * <p>What was done and what happened to the food. A failure stays open until one is recorded;
   * more than one may be.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @return corrective action recorded ({@code 201})
   * @throws com.shelfj.web.ApiException {@code 404} no such record; {@code 409} the check passed
   */
  @Operation(
      summary = "Record a corrective action on a failed check",
      description =
          "What was done and what happened to the food. A failure stays open until one is"
              + " recorded; more than one may be.")
  @APIResponse(responseCode = "201", description = "Corrective action recorded")
  @APIResponse(responseCode = "404", description = "No such record")
  @APIResponse(responseCode = "409", description = "The check passed")
  @POST
  @Path("/records/{id}/corrective-actions")
  public Response addCorrectiveAction(@PathParam("id") String id, CorrectiveActionRequest req) {
    Validations.validate(req);
    var action =
        service.addCorrectiveAction(
            ctx.requireTenantId(),
            ctx.requireUserId(),
            Parsing.uuid(id, "id"),
            req.action(),
            FoodDisposition.valueOf(req.foodDisposition()),
            ctx::requireStoreAccess);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                FoodSafetyMappers.toCorrectiveAction(action), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  private static Result parseResult(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Result.valueOf(value.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "FOOD_SAFETY_RESULT_INVALID", "result must be PASS or FAIL", List.of(), e);
    }
  }
}
