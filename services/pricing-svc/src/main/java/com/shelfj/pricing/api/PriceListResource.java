package com.shelfj.pricing.api;

import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reading price lists and the prices on them.
 *
 * <p>Read-only. Creating a price list and writing prices onto it moved to {@link
 * AdminPriceListResource} under {@code /admin/}: this path is outside {@code /admin/}, so the
 * mutation tier of {@code AdminAuthorizationFilter} asked only for some staff role, and a CASHIER
 * could therefore set what customers are charged. The reads stay here because the POS and the
 * storefront need them and neither runs as management.
 */
@RequestScoped
@Path("/price-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Lists")
public class PriceListResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  /** List price lists. Cursor-paginated: {@code ?after=<meta.nextCursor>&limit=1-100}. */
  @Operation(
      summary = "List price lists",
      description = "Cursor-paginated list of price lists for the tenant.")
  @APIResponse(responseCode = "200", description = "Page of price lists")
  @GET
  public Response list(@QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = svc.listPriceLists(ctx, after, Cursor.clampLimit(limit));
    return Response.ok(
            ApiResponse.ok(
                page.items().stream().map(Mappers::toDto).toList(),
                new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  @Operation(summary = "Get a price list by id", description = "Retrieves a single price list.")
  @APIResponse(responseCode = "200", description = "Price list found")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPriceList(ctx, id)))).build();
  }

  @Operation(
      summary = "List items on a price list",
      description = "All per-variant price entries on this price list.")
  @APIResponse(responseCode = "200", description = "List of price list items")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @GET
  @Path("/{id}/items")
  public Response listItems(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(svc.listPriceListItems(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
