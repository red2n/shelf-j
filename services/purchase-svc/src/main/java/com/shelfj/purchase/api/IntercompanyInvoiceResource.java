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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Intercompany AR/AP invoicing for inter-org inventory transfers (Gap #20, Oracle Inventory Ch.
 * 19).
 */
@RequestScoped
@Path("/intercompany-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Intercompany Invoices")
public class IntercompanyInvoiceResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Raise an intercompany invoice pair",
      description =
          "Raises an AR invoice for the sending store and an AP invoice for the receiving store"
              + " atomically, posting the corresponding FRS 102 / UK GAAP double-entry nominal"
              + " ledger entries. Payment due date is invoice date + 30 days (BACS terms).")
  @APIResponse(responseCode = "201", description = "Invoice pair raised")
  @APIResponse(responseCode = "400", description = "from and to store must be different")
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

  @Operation(
      summary = "List intercompany invoices",
      description = "Lists intercompany invoices for the caller's tenant.")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.shelfj.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listIntercompanyInvoices(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Get an intercompany invoice",
      description = "Returns a single AR or AP intercompany invoice.")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getIntercompanyInvoice(ctx, id)))).build();
  }

  @Operation(
      summary = "Settle an intercompany invoice",
      description =
          "Posts the settlement nominal ledger entries (bank/debtors or creditors/bank) for the"
              + " given invoice.")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @POST
  @Path("/{id}/settle")
  public Response settle(@PathParam("id") UUID id) {
    svc.settleIntercompanyInvoice(ctx, id);
    return Response.ok(ApiResponse.ok("settled")).build();
  }
}
