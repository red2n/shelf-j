package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.IntercompanyInvoicePairResponse;
import com.shelfj.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
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

/**
 * Intercompany AR/AP invoicing for inter-org inventory transfers (Gap #20, Oracle Inventory Ch.
 * 19).
 */
@RequestScoped
@Path("/intercompany-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IntercompanyInvoiceResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @POST
  public Response raise(RaiseIntercompanyInvoiceRequest req) {
    Validations.validate(req);
    var pair = svc.raiseIntercompanyInvoices(req, ctx);
    return Response.status(201)
        .entity(
            ApiResponse.ok(
                new IntercompanyInvoicePairResponse(
                    Mappers.toDto(pair.get(0)), Mappers.toDto(pair.get(1)))))
        .build();
  }

  @GET
  public Response list() {
    return Response.ok(
            ApiResponse.ok(svc.listIntercompanyInvoices(ctx).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getIntercompanyInvoice(ctx, id)))).build();
  }

  @POST
  @Path("/{id}/settle")
  public Response settle(@PathParam("id") UUID id) {
    svc.settleIntercompanyInvoice(ctx, id);
    return Response.ok(ApiResponse.ok("settled")).build();
  }
}
