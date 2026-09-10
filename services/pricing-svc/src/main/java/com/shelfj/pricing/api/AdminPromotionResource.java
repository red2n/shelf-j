package com.shelfj.pricing.api;

import com.shelfj.pricing.domain.Domain;
import com.shelfj.pricing.dto.Dtos.AddPromotionItemRequest;
import com.shelfj.pricing.dto.Dtos.CreatePromotionRequest;
import com.shelfj.pricing.dto.Dtos.SetActiveRequest;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Creating and switching promotions. Price lists are the same surface and the same two defects —
 * see {@link AdminPriceListResource}.
 *
 * <p><b>Under {@code /admin/} deliberately, and that is the entire point of this class
 * existing.</b> These operations used to sit on {@code /promotions}, which is outside {@code
 * /admin/}, so {@code AdminAuthorizationFilter}'s mutation tier asked only for <em>some</em> staff
 * role — and a CASHIER could therefore create a 100%-off basket promotion with no end date
 * (SJ-D36). Combined with SJ-D33, which left no way to switch one off, a till operator could give
 * away the shop permanently and the only remedy was direct database access.
 *
 * <p>Gated by path rather than by a {@code requireAnyRole} call in each method, for the reason
 * SJ-D10 established and SJ-D19 then repeated: a method added to this class next year cannot be
 * left open by someone forgetting a line. The read side stays on {@code /promotions}, because the
 * storefront legitimately needs it.
 */
@RequestScoped
@Path("/admin/promotions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Promotions")
public class AdminPromotionResource {

  @Inject PricingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a promotion",
      description =
          "Creates a time-bounded discount, optionally scoped to a store and channel. Management"
              + " only: this is money leaving the business, and it used to be reachable by any"
              + " staff role including a cashier.")
  @APIResponse(responseCode = "201", description = "Promotion created")
  @APIResponse(responseCode = "403", description = "Caller is not management")
  @POST
  public Response create(CreatePromotionRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPromotion(req, ctx))))
        .build();
  }

  @Operation(
      summary = "Add a scope item to a promotion",
      description = "Attaches the promotion to a scope (ALL, VARIANT or CATEGORY).")
  @APIResponse(responseCode = "201", description = "Promotion item added")
  @POST
  @Path("/{id}/items")
  public Response addItem(@PathParam("id") UUID id, AddPromotionItemRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPromotionItem(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "Stop a promotion",
      description =
          "Switches the promotion off with immediate effect, recording who did it and why. Before"
              + " this existed there was no route, no service method and no writer of any kind for"
              + " promotions.active — a promotion created without an end date ran forever and could"
              + " only be stopped by reaching into the database (SJ-D33).")
  @APIResponse(responseCode = "200", description = "Stopped")
  @APIResponse(responseCode = "400", description = "No reason given")
  @APIResponse(responseCode = "404", description = "No such promotion for this tenant")
  @APIResponse(responseCode = "409", description = "Already stopped")
  @POST
  @Path("/{id}/deactivate")
  public Response deactivate(@PathParam("id") UUID id, SetActiveRequest req) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PROMOTION, id, false, req))))
        .build();
  }

  @Operation(summary = "Start a stopped promotion again", description = "Requires a reason too.")
  @APIResponse(responseCode = "200", description = "Started")
  @APIResponse(responseCode = "409", description = "Already running")
  @POST
  @Path("/{id}/activate")
  public Response activate(@PathParam("id") UUID id, SetActiveRequest req) {
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(svc.setActive(ctx, Domain.StatusChange.PROMOTION, id, true, req))))
        .build();
  }

  @Operation(
      summary = "A promotion's on/off history",
      description =
          "Append-only, newest first. A promotion can be stopped and started repeatedly, so this is"
              + " a table rather than a pair of columns: a record keeping only the last change"
              + " cannot answer who turned it back on.")
  @APIResponse(responseCode = "200", description = "The history")
  @GET
  @Path("/{id}/status-history")
  public Response history(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.statusChanges(ctx, Domain.StatusChange.PROMOTION, id).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }
}
