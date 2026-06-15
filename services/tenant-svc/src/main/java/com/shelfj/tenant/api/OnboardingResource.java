package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.CreateStoreRequest;
import com.shelfj.tenant.dto.Dtos.CreateTenantRequest;
import com.shelfj.tenant.dto.Dtos.OnboardRequest;
import com.shelfj.tenant.dto.Dtos.OnboardResponse;
import com.shelfj.tenant.dto.Dtos.OnboardingStatus;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/**
 * Onboarding endpoints (docs/onboarding-and-locations.md §4-5).
 *
 * <p>{@code POST /onboarding/tenants}: the caller has NO tenant yet — we bind the new tenant to the
 * authenticated userId (from the gateway-forwarded identity). Afterwards, {@code tenantId} comes
 * from the JWT/context.
 */
@Path("/onboarding")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OnboardingResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  /**
   * Single-call onboarding: creates the tenant AND the first store atomically. No JWT refresh
   * needed.
   */
  @POST
  public Response onboard(OnboardRequest req) {
    Validations.validate(req);
    UUID ownerUserId = requireUserId();
    var result = service.onboard(ownerUserId, req);
    var body =
        new OnboardResponse(Mappers.toTenant(result.tenant()), Mappers.toStore(result.store()));
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(body, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @POST
  @Path("/tenants")
  public Response createTenant(CreateTenantRequest req) {
    Validations.validate(req);
    UUID ownerUserId = requireUserId();
    var tenant = service.createTenant(ownerUserId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTenant(tenant), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @POST
  @Path("/stores")
  public Response createStore(CreateStoreRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result = service.createDefaultStore(tenantId, req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(Mappers.toStore(result.store()), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/status")
  public ApiResponse<OnboardingStatus> status() {
    return ApiResponse.ok(service.onboardingStatus(ctx.requireTenantId()));
  }

  private UUID requireUserId() {
    if (ctx.userId() == null) {
      throw ApiException.unauthorized("NO_USER", "Authenticated user required to create a tenant");
    }
    return ctx.userId();
  }
}
