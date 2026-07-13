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

/** Price list management: create price lists and populate per-variant prices. */
@RequestScoped
@Path("/price-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PriceListResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @POST
  public Response create(CreatePriceListRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPriceList(req, ctx))))
        .build();
  }

  /** List price lists. Cursor-paginated: {@code ?after=<meta.nextCursor>&limit=1-100}. */
  @GET
  public Response list(@QueryParam("after") String after, @QueryParam("limit") Integer limit) {
    var page = svc.listPriceLists(ctx, after, Cursor.clampLimit(limit));
    return Response.ok(
            ApiResponse.ok(
                page.items().stream().map(Mappers::toDto).toList(),
                new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPriceList(ctx, id)))).build();
  }

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

  @GET
  @Path("/{id}/items")
  public Response listItems(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(svc.listPriceListItems(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
