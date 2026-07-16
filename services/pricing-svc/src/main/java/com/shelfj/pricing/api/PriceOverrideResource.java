package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.CreatePriceOverrideRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Gap #41 — POS price overrides. Append-only audit log of staff-approved ad-hoc price changes at
 * the point of sale. Requires STAFF or ADMIN role (enforced by AdminAuthorizationFilter).
 */
@RequestScoped
@Path("/admin/price-overrides")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Overrides")
public class PriceOverrideResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Record a POS price override",
      description =
          "Appends a staff-approved ad-hoc price change made at the point of sale. Requires an"
              + " admin/staff role (enforced by AdminAuthorizationFilter).")
  @APIResponse(responseCode = "201", description = "Price override recorded")
  @APIResponse(responseCode = "403", description = "Caller lacks a staff/admin role")
  @POST
  public Response create(CreatePriceOverrideRequest req) {
    Validations.validate(req);
    var override = svc.createPriceOverride(ctx, req);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(override))).build();
  }

  /** Cursor-paginated: {@code ?after=<meta.nextCursor>&limit=1-100}. */
  @Operation(
      summary = "List price overrides",
      description =
          "Append-only audit log of POS price overrides, optionally filtered by store or variant."
              + " Cursor-paginated.")
  @APIResponse(responseCode = "200", description = "Page of price overrides")
  @APIResponse(responseCode = "403", description = "Caller lacks a staff/admin role")
  @GET
  public Response list(
      @QueryParam("storeId") String storeId,
      @QueryParam("variantId") String variantId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = svc.listPriceOverrides(ctx, storeId, variantId, after, Cursor.clampLimit(limit));
    var overrides = page.items().stream().map(Mappers::toDto).toList();
    return Response.ok(
            ApiResponse.ok(overrides, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }
}
