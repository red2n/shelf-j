package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.IssueGiftCardRequest;
import com.shelfj.order.dto.Dtos.RedeemGiftCardRequest;
import com.shelfj.order.dto.Dtos.ReloadGiftCardRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Gift card management — Gap #14 POS feature. */
@Path("/gift-cards")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Gift Cards")
public class GiftCardResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Issues a gift card with a server-generated code and an opening balance.
   *
   * <p>The code is minted server-side, never supplied by the caller: it is bearer stored value, so
   * a guessable code would be spendable by whoever guessed it.
   *
   * @param req the store, amount, optional currency and optional expiry
   * @return {@code 201} with the issued card, including its code
   */
  @Operation(
      summary = "Issue a gift card",
      description = "Issues a new gift card for a store with an initial stored-value balance.")
  @APIResponse(responseCode = "201", description = "Gift card issued")
  @POST
  public Response issue(IssueGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.issueGiftCard(req, ctx);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * Looks a gift card up by its code, to check the balance at the till.
   *
   * @param code the card's code
   * @return the card with its current balance
   * @throws com.shelfj.web.ApiException {@code 404} when no such card exists in the tenant
   */
  @Operation(summary = "Get a gift card by code", description = "Looks up a gift card by its code.")
  @APIResponse(responseCode = "200", description = "Gift card found")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @GET
  @Path("/{code}")
  public Response get(@PathParam("code") String code) {
    var gc = svc.getGiftCard(ctx.tenantId(), code);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * Adds stored value to an existing gift card.
   *
   * @param code the card's code
   * @param req the amount to add and a reference for the transaction log
   * @return the card with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when no such card exists; a conflict when the
   *     card is not active
   */
  @Operation(
      summary = "Reload a gift card",
      description = "Adds stored value to an existing gift card's balance.")
  @APIResponse(responseCode = "200", description = "Gift card reloaded")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @POST
  @Path("/{code}/reload")
  public Response reload(@PathParam("code") String code, ReloadGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.reloadGiftCard(ctx.tenantId(), code, req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * Spends stored value from a gift card as tender for a purchase.
   *
   * <p>The balance check happens in the same transaction as the write, so two tills cannot together
   * overspend one card.
   *
   * @param code the card's code
   * @param req the amount, the order being paid towards, and a reference
   * @return the card with its new balance
   * @throws com.shelfj.web.ApiException {@code 404} when no such card exists; a conflict when the
   *     balance is insufficient or the card is not active
   */
  @Operation(
      summary = "Redeem a gift card",
      description =
          "Deducts stored value from a gift card, optionally against a specific order, as tender"
              + " for a purchase.")
  @APIResponse(responseCode = "200", description = "Gift card redeemed")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @POST
  @Path("/{code}/redeem")
  public Response redeem(@PathParam("code") String code, RedeemGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.redeemGiftCard(ctx.tenantId(), code, req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  /**
   * The append-only issue/reload/redeem history for one gift card.
   *
   * @param code the card's code
   * @return every transaction against the card
   * @throws com.shelfj.web.ApiException {@code 404} when no such card exists in the tenant
   */
  @Operation(
      summary = "List a gift card's transactions",
      description = "Append-only issue/reload/redeem transaction history for the gift card.")
  @APIResponse(responseCode = "200", description = "List of gift card transactions")
  @APIResponse(responseCode = "404", description = "Gift card not found")
  @GET
  @Path("/{code}/transactions")
  public Response transactions(@PathParam("code") String code) {
    var txns = svc.getGiftCardTransactions(ctx.tenantId(), code);
    return Response.ok(ApiResponse.ok(txns.stream().map(Mappers::toDto).toList())).build();
  }
}
