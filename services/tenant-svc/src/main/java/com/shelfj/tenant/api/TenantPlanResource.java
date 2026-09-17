package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.PlanDtos;
import com.shelfj.tenant.mapper.PlanMappers;
import com.shelfj.tenant.service.PlanService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What a business sees of its own plan (21.8): what it is on, what that allows, and how much of
 * each allowance it is using. Management's — the limits decide what the business may do next, and a
 * limit already reached is a thing an owner needs to see before it refuses somebody mid-shift.
 */
@Path("/admin/tenant/plan")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Plans")
public class TenantPlanResource {

  @Inject PlanService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "The plan this business is on, with each limit against what it is using",
      description =
          "A business on no plan is unrestricted, and says so: every business that predates plans"
              + " is in that state, and so is every business while the platform names no default."
              + " A count the owning service could not give is left unknown rather than guessed at.")
  @GET
  public ApiResponse<PlanDtos.TenantPlanResponse> mine() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(PlanMappers.toDto(svc.planOf(ctx.requireTenantId())));
  }

  /**
   * What this business is allowed, and nothing else: no prices, no plan names.
   *
   * <p>Another service reads this to enforce a limit it owns — iam-svc for staff, product-svc for
   * products — so it is open to any staff role, which is what a service-to-service read carries. An
   * allowance is not commercially sensitive; what it costs is, and is not here.
   */
  @Operation(
      summary = "What this business is allowed",
      description =
          "The limits and features of its plan, for the service that enforces one. No prices."
              + " A business on no plan is allowed everything, and answers an empty list.")
  @GET
  @Path("/limits")
  public ApiResponse<PlanDtos.GrantsResponse> limits() {
    ctx.requireAnyRole("OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "PLATFORM_ADMIN");
    return ApiResponse.ok(
        new PlanDtos.GrantsResponse(PlanMappers.grantsOf(svc.planOf(ctx.requireTenantId()))));
  }

  @Operation(
      summary = "The plans on sale",
      description = "What this business could be moved to. Moving it is the platform's to do.")
  @GET
  @Path("/available")
  public ApiResponse<List<PlanDtos.PlanResponse>> available() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(svc.published().stream().map(PlanMappers::toDto).toList());
  }
}
