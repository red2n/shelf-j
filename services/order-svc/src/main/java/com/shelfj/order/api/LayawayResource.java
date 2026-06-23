package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.AddDepositRequest;
import com.shelfj.order.dto.Dtos.CreateLayawayRequest;
import com.shelfj.order.dto.Dtos.VoidRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
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

/** Layaway management — Gap #14 POS feature. */
@Path("/layaways")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LayawayResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  @POST
  public Response create(CreateLayawayRequest req) {
    Validations.validate(req);
    var layaway = svc.createLayaway(req, ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(layaway, items, deposits)))
        .build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") String id) {
    var layaway = svc.getLayaway(ctx.tenantId(), Parsing.uuid(id, "id"));
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }

  @POST
  @Path("/{id}/deposits")
  public Response addDeposit(@PathParam("id") String id, AddDepositRequest req) {
    Validations.validate(req);
    var layaway = svc.addDeposit(ctx.tenantId(), Parsing.uuid(id, "id"), req, ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }

  @POST
  @Path("/{id}/complete")
  public Response complete(@PathParam("id") String id) {
    var layaway = svc.completeLayaway(ctx.tenantId(), Parsing.uuid(id, "id"), ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }

  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") String id, VoidRequest req) {
    var layaway =
        svc.cancelLayaway(
            ctx.tenantId(), Parsing.uuid(id, "id"), req != null ? req.reason() : null, ctx);
    var items = svc.getLayawayItems(ctx.tenantId(), layaway.id());
    var deposits = svc.getLayawayDeposits(ctx.tenantId(), layaway.id());
    return Response.ok(ApiResponse.ok(Mappers.toDto(layaway, items, deposits))).build();
  }
}
