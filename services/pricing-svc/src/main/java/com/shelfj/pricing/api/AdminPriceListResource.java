package com.shelfj.pricing.api;

import com.shelfj.pricing.domain.Domain;
import com.shelfj.pricing.dto.Dtos.BatchUpsertPriceListItemsRequest;
import com.shelfj.pricing.dto.Dtos.BatchUpsertResult;
import com.shelfj.pricing.dto.Dtos.CreatePriceListRequest;
import com.shelfj.pricing.dto.Dtos.SetActiveRequest;
import com.shelfj.pricing.dto.Dtos.UpsertPriceListItemRequest;
import com.shelfj.pricing.mapper.Mappers;
import com.shelfj.pricing.service.PricingService;
import com.shelfj.web.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Setting and switching prices — the other half of the money-moving surface, alongside {@link
 * AdminPromotionResource}.
 *
 * <p><b>Why this class exists at all.</b> SJ-D33 and SJ-D36 were reported against promotions: a
 * promotion could be created by any staff role and then never switched off. Price lists had both
 * defects in identical form and neither was reported:
 *
 * <ul>
 *   <li><b>Anyone could write prices.</b> {@code POST /price-lists} and {@code
 *       /price-lists/{id}/items} sit outside {@code /admin/}, so {@code AdminAuthorizationFilter}
 *       asked only for <em>some</em> staff role. Proved against the running stack: a CASHIER token
 *       created a price list and got 201 back.
 *   <li><b>Nothing could switch one off.</b> {@code price_lists.active} has been {@code NOT NULL
 *       DEFAULT TRUE} since V1 and the resolve query filters on {@code pl.active = TRUE}, so it
 *       decides what customers are charged — and no route, service method or SQL statement ever
 *       wrote it.
 * </ul>
 *
 * <p>A price list is the more dangerous of the two: a promotion discounts a price, a price list
 * <em>is</em> the price. Fixing only what was reported is the mistake this branch has recorded four
 * times over (SJ-D10 → SJ-D11, SJ-D12 → SJ-D16, SJ-D2 → SJ-D23), so both are fixed here.
 *
 * <p>Gated by path, not by a role check inside each method, for the reason SJ-D10 established: a
 * method added to this class next year cannot be left open by someone forgetting a line. Reads stay
 * on {@code /price-lists} — the POS and storefront legitimately need them.
 */
@RequestScoped
@Path("/admin/price-lists")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Price Lists")
public class AdminPriceListResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a price list",
      description =
          "Creates a new price list scoped to a channel, currency, and effective period. Management"
              + " only: this sets what customers are charged, and it used to be reachable by any"
              + " staff role including a cashier.")
  @APIResponse(responseCode = "201", description = "Price list created")
  @APIResponse(responseCode = "403", description = "Caller is not management")
  @POST
  public Response create(CreatePriceListRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPriceList(req, ctx))))
        .build();
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
    return Response.ok(ApiResponse.ok(result)).build();
  }

  @Operation(
      summary = "Stop a price list",
      description =
          "Switches the price list off with immediate effect, recording who did it and why. The"
              + " resolve query filters on active, so from the next request its prices stop being"
              + " offered. Orders already placed keep the price they were charged — that figure is"
              + " recorded on the order, not looked up again.")
  @APIResponse(responseCode = "200", description = "Stopped")
  @APIResponse(responseCode = "400", description = "No reason given")
  @APIResponse(responseCode = "404", description = "No such price list for this tenant")
  @APIResponse(responseCode = "409", description = "Already stopped")
  @POST
  @Path("/{id}/deactivate")
  public Response deactivate(@PathParam("id") UUID id, SetActiveRequest req) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PRICE_LIST, id, false, req))))
        .build();
  }

  @Operation(summary = "Start a stopped price list again", description = "Requires a reason too.")
  @APIResponse(responseCode = "200", description = "Started")
  @APIResponse(responseCode = "409", description = "Already running")
  @POST
  @Path("/{id}/activate")
  public Response activate(@PathParam("id") UUID id, SetActiveRequest req) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PRICE_LIST, id, true, req))))
        .build();
  }

  @Operation(
      summary = "A price list's on/off history",
      description =
          "Append-only, newest first. Shares one trail with promotions rather than two that would"
              + " drift apart — the row says which subject it belongs to.")
  @APIResponse(responseCode = "200", description = "The history")
  @GET
  @Path("/{id}/status-history")
  public Response history(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.statusChanges(ctx, Domain.StatusChange.PRICE_LIST, id).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }
}
