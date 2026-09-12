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

  /**
   * Raises the AR and AP sides of an intercompany transfer atomically.
   *
   * <p>Both sides commit together, with their FRS 102 / UK GAAP double-entry nominal ledger
   * postings. Payment due date is invoice date + 30 days (BACS terms).
   *
   * @param req the sending and receiving stores, the amount and the invoice date
   * @return {@code 201} with both sides of the pair
   * @throws com.shelfj.web.ApiException {@code 400} when the two stores are the same
   */
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

  /**
   * Lists the tenant's intercompany invoices, both AR and AP sides.
   *
   * @param limit page size; clamped to the platform default and maximum when absent or out of range
   * @return the invoices
   */
  @Operation(
      summary = "List intercompany invoices",
      description = "Lists intercompany invoices for the caller's tenant.")
  @APIResponse(responseCode = "200", description = "The invoices")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.shelfj.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listIntercompanyInvoices(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  /**
   * Reads a single AR or AP intercompany invoice.
   *
   * @param id the invoice to read
   * @return the invoice
   * @throws com.shelfj.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "Get an intercompany invoice",
      description = "Returns a single AR or AP intercompany invoice.")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getIntercompanyInvoice(ctx, id)))).build();
  }

  /**
   * Settles an intercompany invoice, posting its nominal-ledger entries.
   *
   * <p>Which entries depends on the side: bank/debtors for AR, creditors/bank for AP.
   *
   * @param id the invoice to settle
   * @return {@code 204} with no body
   * @throws com.shelfj.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
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
