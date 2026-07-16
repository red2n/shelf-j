package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.BatchUpsertPriceListItemsRequest;
import com.shelfj.pricing.dto.Dtos.BatchUpsertResult;
import com.shelfj.pricing.dto.Dtos.CreatePriceListRequest;
import com.shelfj.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.ErrorBody;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Price list management: create price lists and populate per-variant prices. */
@RequestScoped
@Path("/price-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Lists")
public class PriceListResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a price list",
      description = "Creates a new price list scoped to a channel, currency, and effective period.")
  @APIResponse(responseCode = "201", description = "Price list created")
  @POST
  public Response create(CreatePriceListRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPriceList(req, ctx))))
        .build();
  }

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
      summary = "Upsert a price list item",
      description = "Sets or updates the price for a single variant on this price list.")
  @APIResponse(responseCode = "200", description = "Price list item upserted")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @POST
  @Path("/{id}/items")
  public Response upsertItem(@PathParam("id") UUID id, UpsertPriceListItemRequest req) {
    Validations.validate(req);
    return Response.status(200)
        .entity(ApiResponse.ok(Mappers.toDto(svc.upsertPriceListItem(ctx, id, req))))
        .build();
  }

  /**
   * Batch upsert prices for multiple variants at once. Body: { "items": [{ "variantId": "...",
   * "price": 9.99, "minQty": 1 }, ...] } Returns 200 with { "upserted": N, "errors": [...] }. Never
   * 4xx on partial failure.
   */
  @Operation(
      summary = "Batch upsert price list items",
      description =
          "Upserts prices for multiple variants at once. Never returns 4xx on a partial failure —"
              + " per-item errors are reported in the response body alongside the upserted count.")
  @APIResponse(
      responseCode = "200",
      description = "Batch result with upserted count and any errors")
  @APIResponse(responseCode = "400", description = "items array missing or empty")
  @APIResponse(responseCode = "404", description = "Price list not found")
  @POST
  @Path("/{id}/items/batch")
  public Response batchUpsertItems(@PathParam("id") UUID id, BatchUpsertPriceListItemsRequest req) {
    if (req == null || req.items() == null || req.items().isEmpty()) {
      return Response.status(400)
          .entity(
              ApiResponse.<Void>error(
                  new ErrorBody(
                      "INVALID_BODY", "items array required and must not be empty", List.of())))
          .build();
    }
    BatchUpsertResult result = svc.batchUpsertPriceListItems(ctx, id, req);
    return Response.ok(com.shelfj.web.ApiResponse.ok(result)).build();
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
