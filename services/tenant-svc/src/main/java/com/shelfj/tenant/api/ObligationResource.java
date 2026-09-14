package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.Dtos.ObligationsResponse;
import com.shelfj.tenant.mapper.Mappers;
import com.shelfj.tenant.service.ObligationService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
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
 * The laws a business trades under. Read-only: the rules are reference data that change only with
 * the law, by migration. Any staff role reads them, because the till obeys them; a shopper does
 * not.
 */
@Path("/admin/tenant/obligations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Legal obligations")
public class ObligationResource {

  @Inject ObligationService service;
  @Inject TenantContext ctx;

  /**
   * The obligations that bind a country on a day.
   *
   * @param country ISO 3166-1 alpha-2; the business's own when absent
   * @param on yyyy-mm-dd; today when absent
   * @return in force, then upcoming
   */
  @Operation(
      summary = "Which laws bind this business",
      description =
          "The legal obligations that reach a country, directly or through a regime it belongs to,"
              + " each with its citation and the window it applies in — narrowed to the country's"
              + " membership, so a British business is not bound by EU law made after it left."
              + " IN_FORCE on the day asked about, then UPCOMING; anything already ended is left"
              + " out. country defaults to the business's own, on to today.")
  @APIResponse(responseCode = "200", description = "In force, then upcoming")
  @APIResponse(responseCode = "400", description = "A country or a date that is not one")
  @GET
  public ApiResponse<ObligationsResponse> obligations(
      @QueryParam("country") String country, @QueryParam("on") String on) {
    return ApiResponse.ok(
        Mappers.toObligations(service.obligations(ctx.requireTenantId(), country, on)));
  }
}
