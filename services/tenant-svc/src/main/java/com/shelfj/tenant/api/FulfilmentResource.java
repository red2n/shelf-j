package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.FulfilmentResolveResponse;
import com.shelfj.tenant.service.TenantService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Internal/public-resolve for which store fulfils a home delivery. Called by order-svc on DELIVERY
 * placement and optionally by the storefront for UX feedback. Tenant from JWT / storefront header.
 */
@RequestScoped
@Path("/fulfilment")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Fulfilment")
public class FulfilmentResource {

  @Inject TenantService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Resolve fulfilling store by pincode",
      description =
          "Returns the store that should fulfil a DELIVERY order for the given pincode. Lowest"
              + " priority mapping wins. If the tenant has no delivery areas configured, returns the"
              + " default/first store. If areas exist but the pincode is unmapped → 404"
              + " FULFILMENT_AREA_NOT_COVERED.")
  @APIResponse(responseCode = "200", description = "Resolved store")
  @APIResponse(responseCode = "400", description = "pincode missing")
  @APIResponse(
      responseCode = "404",
      description = "Pincode not covered (when areas are configured)")
  @GET
  @Path("/resolve")
  public ApiResponse<FulfilmentResolveResponse> resolve(@QueryParam("pincode") String pincode) {
    return ApiResponse.ok(service.resolveFulfilment(ctx.requireTenantId(), pincode));
  }
}
