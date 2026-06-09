package com.shelfj.pricing.api;

import com.shelfj.pricing.dto.Dtos.CreatePriceListRequest;
import com.shelfj.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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

  @GET
  public Response list() {
    return Response.ok(
            ApiResponse.ok(svc.listPriceLists(ctx).stream().map(Mappers::toDto).toList()))
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

  @GET
  @Path("/{id}/items")
  public Response listItems(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(svc.listPriceListItems(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
