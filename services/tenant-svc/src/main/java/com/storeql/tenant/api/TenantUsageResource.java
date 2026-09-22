package com.storeql.tenant.api;

import com.storeql.tenant.dto.UsageDtos;
import com.storeql.tenant.mapper.UsageMappers;
import com.storeql.tenant.service.UsageService;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What a business has used (21.10): this billing period against what its plan includes, what it
 * owes beyond that so far, and what earlier periods billed. Management's, like the plan it reads —
 * except whether one more may be done, which the service about to do it asks.
 */
@Path("/admin/tenant/usage")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Plans")
public class TenantUsageResource {

  @Inject UsageService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "What this business has used this period, and before",
      description =
          "Each meter this billing period against what the plan includes, and what is owed beyond"
              + " that so far; the thresholds reached (80%, then 100%); and each closed period as it"
              + " was billed. A business on no plan is read by the calendar month, and nothing is"
              + " charged.")
  @GET
  public ApiResponse<UsageDtos.UsageResponse> mine() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(UsageMappers.toDto(svc.summary(ctx.requireTenantId())));
  }

  /**
   * Whether one more may be done, and nothing else: no prices, no history.
   *
   * <p>Asked by the service about to do it under a staff identity — notification-svc before a
   * marketing text — so it is open to every staff role, and the admin filter admits the leaf.
   */
  @Operation(
      summary = "Whether this business may do more of a metered thing now",
      description =
          "Only a hard ceiling ever answers no, and only a meter that may be refused has one."
              + " USAGE_METER_UNKNOWN, USAGE_QUANTITY_INVALID.")
  @GET
  @Path("/allowance")
  public ApiResponse<UsageDtos.AllowanceResponse> allowance(
      @QueryParam("meter") String meter, @QueryParam("quantity") @DefaultValue("1") long quantity) {
    ctx.requireAnyRole("OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "PLATFORM_ADMIN");
    return ApiResponse.ok(
        UsageMappers.toDto(svc.allowance(ctx.requireTenantId(), meter, quantity)));
  }
}
