package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.StoreTaskDtos;
import com.shelfj.tenant.mapper.StoreTaskMappers;
import com.shelfj.tenant.service.StoreTaskService;
import com.shelfj.tenant.service.StoreTaskService.Line;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/workforce/tasks}: what a shop does and when, and how each day came to (store
 * operations & workforce).
 *
 * <p>Management's half — writing the list, raising a job by hand, reading the day. The staff half,
 * working the list, is {@link StoreTaskWorkResource} outside {@code /admin/}, because a checklist
 * only a manager could tick is a checklist nobody keeps.
 */
@Path("/admin/workforce/tasks")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Workforce")
public class StoreTaskResource {

  @Inject StoreTaskService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Write a list",
      description =
          "A piece of work the shop does on a schedule. With lines it is a checklist, finished when"
              + " every required line is ticked; without them a single task. It falls due at dueTime on"
              + " the STORE's own clock, so an opening list at 08:00 falls due at 08:00 in Mumbai and"
              + " 08:00 in London, not at the same instant. Omit storeId for every store.")
  @APIResponse(responseCode = "201", description = "The list, with its lines")
  @APIResponse(
      responseCode = "400",
      description = "TASK_LIST_INVALID, TASK_TITLE_REQUIRED, TASK_LINE_BLANK, TASK_TIME_INVALID")
  @POST
  @Path("/lists")
  public Response create(StoreTaskDtos.TemplateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank() ? null : uuid(req.storeId(), "storeId");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    List<Line> lines =
        req.lines() == null
            ? List.of()
            : req.lines().stream()
                .map(l -> new Line(l.text(), l.required() == null || l.required()))
                .toList();
    var list =
        svc.create(
            ctx.requireTenantId(),
            storeId,
            req.title(),
            req.instructions(),
            req.kind(),
            req.daysOfWeek(),
            time(req.dueTime()),
            req.graceMinutes(),
            req.role(),
            req.required() == null || req.required(),
            lines,
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(StoreTaskMappers.toDto(list))).build();
  }

  @Operation(
      summary = "The lists this business keeps",
      description =
          "Active ones unless all=true. Withdrawn lists stay, to explain days already worked.")
  @GET
  @Path("/lists")
  public ApiResponse<List<StoreTaskDtos.TemplateResponse>> lists(@QueryParam("all") Boolean all) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc.templates(ctx.requireTenantId(), !Boolean.TRUE.equals(all)).stream()
            .map(StoreTaskMappers::toDto)
            .toList());
  }

  @Operation(summary = "One list, with its lines")
  @APIResponse(responseCode = "404", description = "TASK_LIST_NOT_FOUND")
  @GET
  @Path("/lists/{id}")
  public ApiResponse<StoreTaskDtos.TemplateResponse> list(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(StoreTaskMappers.toDto(svc.template(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Withdraw a list",
      description =
          "No new days are generated for it. Days already generated stand: what was done was done.")
  @APIResponse(responseCode = "409", description = "TASK_LIST_WITHDRAWN")
  @DELETE
  @Path("/lists/{id}")
  public ApiResponse<StoreTaskDtos.TemplateResponse> withdraw(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        StoreTaskMappers.toDto(svc.withdraw(ctx.requireTenantId(), id, ctx.requireUserId())));
  }

  @Operation(
      summary = "Generate a store's day now",
      description =
          "Every active list that falls due on that date, once — the sweeper does this hourly, and a"
              + " manager who has just written the opening list need not wait for it. Idempotent: the"
              + " unique constraint decides, so a day is never generated twice however many ask.")
  @APIResponse(responseCode = "200", description = "How many occurrences this call created")
  @POST
  @Path("/days")
  public ApiResponse<Integer> generate(StoreTaskDtos.GenerateRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    UUID tenantId = ctx.requireTenantId();
    LocalDate day =
        req.businessDate() == null || req.businessDate().isBlank()
            ? svc.today(tenantId, storeId)
            : date(req.businessDate(), "businessDate");
    return ApiResponse.ok(svc.generate(tenantId, storeId, day));
  }

  @Operation(
      summary = "Raise a job by hand",
      description =
          "A list put on a store's day once: a delivery to put away, a spill. Today on the store's clock when no date is given.")
  @APIResponse(responseCode = "201", description = "The task, open")
  @APIResponse(
      responseCode = "409",
      description = "TASK_ALREADY_RAISED, TASK_LIST_WITHDRAWN, TASK_LIST_OTHER_STORE")
  @POST
  @Path("/raise")
  public Response raise(StoreTaskDtos.RaiseRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    var task =
        svc.raise(
            ctx.requireTenantId(),
            uuid(req.listId(), "listId"),
            storeId,
            req.businessDate() == null || req.businessDate().isBlank()
                ? null
                : date(req.businessDate(), "businessDate"));
    return Response.status(201).entity(ApiResponse.ok(StoreTaskMappers.toDto(task))).build();
  }

  @Operation(
      summary = "A store's work over a range of days",
      description = "Every occurrence, with its lines, in the order it fell due. At most 62 days.")
  @APIResponse(responseCode = "400", description = "TASK_RANGE_INVALID")
  @GET
  @Path("/days")
  public ApiResponse<List<StoreTaskDtos.InstanceResponse>> days(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID store = uuid(storeId, "storeId");
    ctx.requireStoreAccess(store);
    return ApiResponse.ok(
        svc.day(ctx.requireTenantId(), store, date(from, "from"), date(to, "to")).stream()
            .map(StoreTaskMappers::toDto)
            .toList());
  }

  @Operation(
      summary = "What each day came to",
      description =
          "One summary per business date: done, late, skipped, missed, open. Late is done after it fell"
              + " due, which is not missed; a day is settled when nothing required is open or missed.")
  @GET
  @Path("/summary")
  public ApiResponse<List<StoreTaskDtos.DayResponse>> summary(
      @QueryParam("storeId") String storeId,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    UUID store = uuid(storeId, "storeId");
    ctx.requireStoreAccess(store);
    return ApiResponse.ok(
        svc.summary(ctx.requireTenantId(), store, date(from, "from"), date(to, "to")).stream()
            .map(StoreTaskMappers::toDto)
            .toList());
  }

  @Operation(summary = "One task, with its lines")
  @APIResponse(responseCode = "404", description = "TASK_NOT_FOUND")
  @GET
  @Path("/{id}")
  public ApiResponse<StoreTaskDtos.InstanceResponse> task(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(StoreTaskMappers.toDto(svc.instance(ctx.requireTenantId(), id)));
  }

  static UUID uuid(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("TASK_ID_INVALID", field + " is required");
    }
    try {
      return UUID.fromString(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          400, "TASK_ID_INVALID", field + " is not an id: " + value, List.of(), e);
    }
  }

  static LocalDate date(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("TASK_DATE_INVALID", field + " is a date as YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "TASK_DATE_INVALID", field + " is a date as YYYY-MM-DD: " + value, List.of(), e);
    }
  }

  private static LocalTime time(String value) {
    try {
      return LocalTime.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "TASK_TIME_INVALID", "dueTime is a time as HH:mm: " + value, List.of(), e);
    }
  }
}
