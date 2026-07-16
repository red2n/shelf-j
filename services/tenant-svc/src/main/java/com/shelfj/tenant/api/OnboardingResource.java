package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.CreateStoreRequest;
import com.shelfj.tenant.dto.Dtos.CreateTenantRequest;
import com.shelfj.tenant.dto.Dtos.OnboardRequest;
import com.shelfj.tenant.dto.Dtos.OnboardResponse;
import com.shelfj.tenant.dto.Dtos.OnboardingStatus;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.TenantService;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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
@Tag(name = "Onboarding")
public class OnboardingResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Onboard a new tenant and its first store",
      description =
          "Single-call onboarding: creates the tenant AND the first store (+ default zone)"
              + " atomically. No JWT refresh needed.")
  @APIResponse(responseCode = "201", description = "Tenant and first store created")
  @POST
  public Response onboard(OnboardRequest req) {
    Validations.validate(req);
    UUID ownerUserId = ctx.requireUserId();
    var result = service.onboard(ownerUserId, req);
    var body =
        new OnboardResponse(Mappers.toTenant(result.tenant()), Mappers.toStore(result.store()));
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(body, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(
      summary = "Create the business",
      description =
          "The caller has no tenant yet — the new tenant is bound to the authenticated userId as"
              + " OWNER.")
  @APIResponse(responseCode = "201", description = "Tenant created")
  @POST
  @Path("/tenants")
  public Response createTenant(CreateTenantRequest req) {
    Validations.validate(req);
    UUID ownerUserId = ctx.requireUserId();
    var tenant = service.createTenant(ownerUserId, req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTenant(tenant), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @Operation(
      summary = "Create the first/default store",
      description =
          "Creates the default store (+ its DEFAULT zone) for the caller's tenant, taken from the"
              + " JWT/context.")
  @APIResponse(responseCode = "201", description = "Default store created")
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

  @Operation(
      summary = "Get onboarding status",
      description = "Setup-checklist state: tenant active, default store present, and next steps.")
  @GET
  @Path("/status")
  public ApiResponse<OnboardingStatus> status() {
    return ApiResponse.ok(service.onboardingStatus(ctx.requireTenantId()));
  }
}
