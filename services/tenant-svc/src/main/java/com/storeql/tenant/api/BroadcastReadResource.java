package com.storeql.tenant.api;

import com.storeql.tenant.dto.BroadcastDtos;
import com.storeql.tenant.mapper.BroadcastMappers;
import com.storeql.tenant.service.BroadcastService;
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
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /workforce/broadcasts}: the notices current for whoever is on shift, and their
 * acknowledgement (store operations & workforce).
 *
 * <p><b>Outside {@code /admin/} deliberately</b>, as the clock and the task list are: the people a
 * notice is for are cashiers and storekeepers, and a notice only management could read reaches
 * nobody. Who acknowledged what comes from the token, never from the request.
 */
@Path("/workforce/broadcasts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Time Clock")
public class BroadcastReadResource {

  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};

  @Inject BroadcastService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The notices current for me at a store",
      description =
          "Newest first, each with whether I have acknowledged it. Withdrawn and expired notices are not here.")
  @APIResponse(responseCode = "409", description = "WORKFORCE_NOT_ASSIGNED")
  @GET
  public ApiResponse<List<BroadcastDtos.BroadcastResponse>> current(
      @QueryParam("storeId") String storeId) {
    ctx.requireAnyRole(STAFF);
    UUID store = StoreTaskResource.uuid(storeId, "storeId");
    ctx.requireStoreAccess(store);
    return ApiResponse.ok(
        svc.current(ctx.requireTenantId(), store, ctx.requireUserId()).stream()
            .map(s -> BroadcastMappers.toDto(s.broadcast(), s.acknowledgedAt()))
            .toList());
  }

  @Operation(
      summary = "Acknowledge a notice",
      description =
          "Once. A second tap is a conflict, not a second reading — the constraint decides.")
  @APIResponse(
      responseCode = "409",
      description =
          "BROADCAST_ALREADY_ACKNOWLEDGED, BROADCAST_NOT_CURRENT, BROADCAST_NOT_ADDRESSED")
  @POST
  @Path("/{id}/acknowledgement")
  public Response acknowledge(@PathParam("id") UUID id, BroadcastDtos.AckRequest req) {
    ctx.requireAnyRole(STAFF);
    Validations.validate(req);
    UUID store = StoreTaskResource.uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(store);
    var ack = svc.acknowledge(ctx.requireTenantId(), id, store, ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(ack.ackedAt().toString())).build();
  }
}
