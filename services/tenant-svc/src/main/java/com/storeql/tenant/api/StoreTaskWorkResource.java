package com.storeql.tenant.api;

import com.storeql.tenant.dto.StoreTaskDtos;
import com.storeql.tenant.mapper.StoreTaskMappers;
import com.storeql.tenant.service.StoreTaskService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /workforce/tasks}: today's list, worked by the people on shift (store operations &
 * workforce).
 *
 * <p><b>Outside {@code /admin/} deliberately</b>, as the clock is: that prefix is gated to
 * management platform-wide, and the people who open up and lock the back door are cashiers and
 * storekeepers. A checklist only a manager could tick is a checklist nobody keeps.
 *
 * <p>Who ticked what comes from the token, never from the request; and every act is refused for
 * somebody not assigned to that store, so a list is worked by the shop it belongs to.
 */
@Path("/workforce/tasks")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Time Clock")
public class StoreTaskWorkResource {

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject StoreTaskService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Today's list at a store",
      description =
          "Every task on the store's own today, in the order it falls due, with its lines. Today is"
              + " the store's, on its own clock. Yesterday's unfinished work is not here: it is on"
              + " the day it belonged to, where the manager's report will find it missed.")
  @GET
  public ApiResponse<List<StoreTaskDtos.InstanceResponse>> today(
      @QueryParam("storeId") String storeId, @QueryParam("date") String date) {
    ctx.requireAnyRole(STAFF);
    UUID store = StoreTaskResource.uuid(storeId, "storeId");
    ctx.requireStoreAccess(store);
    UUID tenantId = ctx.requireTenantId();
    LocalDate day =
        date == null || date.isBlank()
            ? svc.today(tenantId, store)
            : StoreTaskResource.date(date, "date");
    return ApiResponse.ok(
        svc.day(tenantId, store, day, day).stream().map(StoreTaskMappers::toDto).toList());
  }

  @Operation(summary = "One task, with its lines")
  @GET
  @Path("/{id}")
  public ApiResponse<StoreTaskDtos.InstanceResponse> task(@PathParam("id") UUID id) {
    ctx.requireAnyRole(STAFF);
    var task = svc.instance(ctx.requireTenantId(), id);
    ctx.requireStoreAccess(task.storeId());
    return ApiResponse.ok(StoreTaskMappers.toDto(task));
  }

  @Operation(
      summary = "Tick a line",
      description =
          "One line of a checklist, by whoever is at the till. A line ticked twice is a conflict, not a second tick.")
  @APIResponse(
      responseCode = "409",
      description = "TASK_NOT_OPEN, TASK_LINE_TICKED, WORKFORCE_NOT_ASSIGNED")
  @POST
  @Path("/{id}/lines/{position}/tick")
  public ApiResponse<StoreTaskDtos.InstanceResponse> tick(
      @PathParam("id") UUID id, @PathParam("position") int position) {
    ctx.requireAnyRole(STAFF);
    return ApiResponse.ok(
        StoreTaskMappers.toDto(svc.tick(ctx.requireTenantId(), id, position, ctx.requireUserId())));
  }

  @Operation(
      summary = "Finish a task",
      description =
          "Done, by the caller, now. A checklist is refused until every required line is ticked: a"
              + " closing list signed off with the safe still open is what the required flag is for.")
  @APIResponse(responseCode = "409", description = "TASK_LINES_OUTSTANDING, TASK_NOT_OPEN")
  @POST
  @Path("/{id}/complete")
  public ApiResponse<StoreTaskDtos.InstanceResponse> complete(
      @PathParam("id") UUID id, StoreTaskDtos.CompleteRequest req) {
    ctx.requireAnyRole(STAFF);
    if (req != null) Validations.validate(req);
    return ApiResponse.ok(
        StoreTaskMappers.toDto(
            svc.complete(
                ctx.requireTenantId(), id, req == null ? null : req.note(), ctx.requireUserId())));
  }

  @Operation(
      summary = "Skip a task, with the reason",
      description =
          "Explained away rather than done. The reason is required: a skipped closing check with none is what an auditor asks about.")
  @APIResponse(responseCode = "400", description = "TASK_REASON_REQUIRED")
  @POST
  @Path("/{id}/skip")
  public ApiResponse<StoreTaskDtos.InstanceResponse> skip(
      @PathParam("id") UUID id, StoreTaskDtos.SkipRequest req) {
    ctx.requireAnyRole(STAFF);
    Validations.validate(req);
    return ApiResponse.ok(
        StoreTaskMappers.toDto(
            svc.skip(ctx.requireTenantId(), id, req.reason(), ctx.requireUserId())));
  }
}
