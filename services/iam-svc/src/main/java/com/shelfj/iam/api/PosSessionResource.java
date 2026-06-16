package com.shelfj.iam.api;

import com.shelfj.iam.domain.PosSession;
import com.shelfj.iam.dto.Dtos.IdleSweepResult;
import com.shelfj.iam.dto.Dtos.PosSessionResponse;
import com.shelfj.iam.dto.Dtos.StartPosSessionRequest;
import com.shelfj.iam.service.PosSessionService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Gap #45 — POS session idle timeout. Creates and manages cashier POS sessions; a background sweep
 * (POST /sweep) force-expires sessions idle past their configured timeout and revokes their tokens.
 */
@RequestScoped
@Path("/auth/pos/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PosSessionResource {

  @Inject PosSessionService svc;
  @Inject TenantContext ctx;

  @POST
  public Response start(StartPosSessionRequest req) {
    Validations.validate(req);
    var session = svc.start(ctx, req);
    return Response.status(201).entity(ApiResponse.ok(toDto(session))).build();
  }

  @PUT
  @Path("/{id}/activity")
  public Response touch(@PathParam("id") UUID id) {
    svc.touch(ctx, id);
    return Response.noContent().build();
  }

  @DELETE
  @Path("/{id}")
  public Response end(@PathParam("id") UUID id) {
    svc.end(ctx, id);
    return Response.noContent().build();
  }

  @GET
  public Response listActive() {
    List<PosSessionResponse> list = svc.listActive(ctx).stream().map(this::toDto).toList();
    return Response.ok(ApiResponse.ok(list)).build();
  }

  /**
   * Admin: expire all sessions idle past their timeout and revoke their refresh tokens. This is a
   * platform-wide maintenance operation (it ignores tenant scope), so it is restricted to platform
   * administrators — without this guard any authenticated caller could revoke POS sessions across
   * every tenant.
   */
  @POST
  @Path("/sweep")
  public Response sweep() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    int expired = svc.sweepIdle();
    return Response.ok(ApiResponse.ok(new IdleSweepResult(expired))).build();
  }

  private PosSessionResponse toDto(PosSession s) {
    return new PosSessionResponse(
        s.id() != null ? s.id().toString() : null,
        s.tenantId() != null ? s.tenantId().toString() : null,
        s.userId() != null ? s.userId().toString() : null,
        s.storeId() != null ? s.storeId().toString() : null,
        s.startedAt() != null ? s.startedAt().toString() : null,
        s.lastActivityAt() != null ? s.lastActivityAt().toString() : null,
        s.endedAt() != null ? s.endedAt().toString() : null,
        s.idleTimeoutSeconds(),
        s.status());
  }
}
