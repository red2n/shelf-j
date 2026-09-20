package com.storeql.tenant.api;

import com.storeql.tenant.dto.BroadcastDtos;
import com.storeql.tenant.mapper.BroadcastMappers;
import com.storeql.tenant.service.BroadcastService;
import com.storeql.web.ApiException;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/workforce/broadcasts}: what management tells the shop floor, and how far it reached
 * (store operations & workforce). The staff half — reading and acknowledging — is {@link
 * BroadcastReadResource}, outside {@code /admin/}.
 */
@Path("/admin/workforce/broadcasts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Workforce")
public class BroadcastResource {

  @Inject BroadcastService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Publish a notice",
      description =
          "To one store or every open store, to everybody there or one role. Announced to each"
              + " store's devices in the same transaction; only an URGENT notice wakes them. Never"
              + " edited: withdraw and publish again, so what was acknowledged is what was seen.")
  @APIResponse(responseCode = "201", description = "The notice")
  @APIResponse(responseCode = "400", description = "BROADCAST_INVALID, BROADCAST_EXPIRY_INVALID")
  @POST
  public Response publish(BroadcastDtos.PublishRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    UUID storeId =
        req.storeId() == null || req.storeId().isBlank()
            ? null
            : StoreTaskResource.uuid(req.storeId(), "storeId");
    if (storeId != null) ctx.requireStoreAccess(storeId);
    var notice =
        svc.publish(
            ctx.requireTenantId(),
            req.title(),
            req.body(),
            req.priority(),
            storeId,
            req.role(),
            Boolean.TRUE.equals(req.requiresAck()),
            instant(req.expiresAt()),
            ctx.requireUserId());
    return Response.status(201)
        .entity(ApiResponse.ok(BroadcastMappers.toDto(notice, null)))
        .build();
  }

  @Operation(
      summary = "The business's notices, newest first",
      description = "Published ones unless all=true.")
  @GET
  public ApiResponse<List<BroadcastDtos.BroadcastResponse>> list(
      @QueryParam("all") Boolean all, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc.broadcasts(ctx.requireTenantId(), !Boolean.TRUE.equals(all), limit).stream()
            .map(b -> BroadcastMappers.toDto(b, null))
            .toList());
  }

  @Operation(summary = "One notice")
  @APIResponse(responseCode = "404", description = "BROADCAST_NOT_FOUND")
  @GET
  @Path("/{id}")
  public ApiResponse<BroadcastDtos.BroadcastResponse> one(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(BroadcastMappers.toDto(svc.broadcast(ctx.requireTenantId(), id), null));
  }

  @Operation(
      summary = "How far a notice reached",
      description =
          "Per store: how many it is addressed to, how many acknowledged, and who has not — named.")
  @GET
  @Path("/{id}/reach")
  public ApiResponse<List<BroadcastDtos.ReachResponse>> reach(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        svc.reach(ctx.requireTenantId(), id).stream().map(BroadcastMappers::toDto).toList());
  }

  @Operation(
      summary = "Withdraw a notice, with the reason",
      description = "Its acknowledgements stay: they were made against the text that stood.")
  @APIResponse(responseCode = "409", description = "BROADCAST_WITHDRAWN")
  @POST
  @Path("/{id}/withdrawal")
  public ApiResponse<BroadcastDtos.BroadcastResponse> withdraw(
      @PathParam("id") UUID id, BroadcastDtos.WithdrawRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        BroadcastMappers.toDto(
            svc.withdraw(ctx.requireTenantId(), id, req.reason(), ctx.requireUserId()), null));
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400,
          "BROADCAST_EXPIRY_INVALID",
          "expiresAt is an ISO-8601 instant: " + value,
          List.of(),
          e);
    }
  }
}
