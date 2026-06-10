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

/** Gift card management — Gap #14 POS feature. */
@Path("/gift-cards")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GiftCardResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @POST
  public Response issue(IssueGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.issueGiftCard(req, ctx);
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  @GET
  @Path("/{code}")
  public Response get(@PathParam("code") String code) {
    var gc = svc.getGiftCard(ctx.tenantId(), code);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  @POST
  @Path("/{code}/reload")
  public Response reload(@PathParam("code") String code, ReloadGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.reloadGiftCard(ctx.tenantId(), code, req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  @POST
  @Path("/{code}/redeem")
  public Response redeem(@PathParam("code") String code, RedeemGiftCardRequest req) {
    Validations.validate(req);
    var gc = svc.redeemGiftCard(ctx.tenantId(), code, req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(gc))).build();
  }

  @GET
  @Path("/{code}/transactions")
  public Response transactions(@PathParam("code") String code) {
    var txns = svc.getGiftCardTransactions(ctx.tenantId(), code);
    return Response.ok(ApiResponse.ok(txns.stream().map(Mappers::toDto).toList())).build();
  }
}
