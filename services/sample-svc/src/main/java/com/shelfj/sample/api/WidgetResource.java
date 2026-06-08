package com.shelfj.sample.api;

import com.shelfj.sample.dto.CreateWidgetRequest;
import com.shelfj.sample.dto.WidgetResponse;
import com.shelfj.sample.mapper.WidgetMapper;
import com.shelfj.sample.service.WidgetService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
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

/**
 * Sample REST resource demonstrating the Shelf-J endpoint pattern (README §6, §7).
 *
 * <p>THIN controller: reads tenant from {@link TenantContext} (never the body/path), validates,
 * delegates to the service, returns the {@link ApiResponse} envelope. No business logic or DB
 * access here.
 */
@Path("/widgets")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WidgetResource {

  @Inject WidgetService service;

  @Inject TenantContext ctx;

  @POST
  public Response create(CreateWidgetRequest request) {
    UUID tenantId = ctx.requireTenantId();
    var widget = service.create(tenantId, request == null ? null : request.name());
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(WidgetMapper.toResponse(widget), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/{id}")
  public ApiResponse<WidgetResponse> get(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        WidgetMapper.toResponse(service.get(tenantId, id)), ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  public ApiResponse<List<WidgetResponse>> list(@QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    int clamped = Cursor.clampLimit(limit);
    List<WidgetResponse> items =
        service.list(tenantId, clamped).stream().map(WidgetMapper::toResponse).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }
}
