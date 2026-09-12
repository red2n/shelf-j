package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.CaptureSupplierInvoiceRequest;
import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Supplier invoices and the three-way match: ordered against received against invoiced.
 *
 * <p>The control that stops a business paying for goods it did not order, did not get, or was
 * charged the wrong price for.
 */
@RequestScoped
@Path("/supplier-invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Supplier Invoices")
public class SupplierInvoiceResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Records a supplier's invoice against a purchase order and matches it three ways.
   *
   * <p>Quantity is matched against what was <em>received</em>, because you pay for what turned up;
   * price against what was <em>ordered</em>, because a delivery note does not renegotiate the
   * price. Variances flag the invoice rather than block it, and the flags are stored as found.
   *
   * @param req the purchase order, invoice number, date, currency and lines
   * @return {@code 201} with the captured invoice, status MATCHED or FLAGGED
   * @throws com.shelfj.web.ApiException {@code 400} when there are no lines or the currency differs
   *     from the order's; {@code 404} when the order does not exist; {@code 409} when this
   *     supplier's invoice number was already captured
   */
  @Operation(
      summary = "Capture a supplier invoice and match it three ways",
      description =
          "Records the supplier's invoice against a purchase order and compares it with what was"
              + " ordered and what was received. Quantity is matched against RECEIVED, not ordered,"
              + " because you pay for what turned up — an order for 100 that delivered 60 and"
              + " invoiced 60 is correct. Price is matched against the ORDER, because a goods"
              + " receipt records quantity only. Invoiced quantity is compared cumulatively across"
              + " every invoice on the order, so a supplier who delivers and bills in two parts is"
              + " not flagged as over-invoicing on the second.\\n\\n"
              + "The invoice is stored whether or not it matches: flagging never blocks capture. An"
              + " invoice that arrived is a fact, and refusing to record one that disagrees with the"
              + " order destroys the evidence of the disagreement. Tolerance bands are configured"
              + " per deployment (shelfj.purchase.match.tolerance.*) and default to zero, which"
              + " surfaces every difference.")
  @APIResponse(responseCode = "201", description = "Invoice captured; status MATCHED or FLAGGED")
  @APIResponse(responseCode = "400", description = "No lines, or a currency the order was not in")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "409",
      description = "This supplier's invoice number has already been captured")
  @POST
  public Response capture(CaptureSupplierInvoiceRequest req) {
    Validations.validate(req);
    var invoice = svc.captureSupplierInvoice(ctx, req);
    return Response.status(201)
        .entity(
            ApiResponse.ok(
                Mappers.toDto(
                    invoice,
                    svc.supplierInvoiceLines(ctx, invoice.id()),
                    svc.matchPositions(ctx, invoice.poId()))))
        .build();
  }

  /**
   * Lists supplier invoices, newest first.
   *
   * @param poId restrict to one purchase order, or {@code null} for the whole tenant
   * @param limit page size, defaulting to 20
   * @return the invoices, newest first
   */
  @Operation(
      summary = "List supplier invoices",
      description = "Optionally filtered to one purchase order.")
  @APIResponse(responseCode = "200", description = "Invoices, newest first")
  @GET
  public Response list(
      @QueryParam("poId") String poId, @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID po = Parsing.optionalUuid(poId, "poId");
    return Response.ok(
            ApiResponse.ok(
                svc.listSupplierInvoices(ctx, po, Math.min(Math.max(limit, 1), 100)).stream()
                    .map(
                        inv ->
                            Mappers.toDto(
                                inv,
                                svc.supplierInvoiceLines(ctx, inv.id()),
                                svc.matchPositions(ctx, inv.poId())))
                    .toList()))
        .build();
  }

  /**
   * One supplier invoice with the order, the receipts and the invoice side by side.
   *
   * <p>Each line carries what was ordered, what was received, what earlier invoices billed, what
   * this one bills, and every variance found. The variances are those stored at capture, not
   * recomputed — the order can be amended afterwards, and re-matching on read would erase the
   * disagreement the invoice was flagged for.
   *
   * @param id the invoice to read
   * @return the invoice and its match
   * @throws com.shelfj.web.ApiException {@code 404} when it does not exist in the caller's tenant
   */
  @Operation(
      summary = "One supplier invoice, with all three documents side by side",
      description =
          "Each line carries what was ordered, what was received, what earlier invoices billed, what"
              + " this one bills, and every variance found. The variances are the ones stored at"
              + " capture rather than recomputed: a purchase order can be amended after an invoice"
              + " is flagged, and re-matching on read would silently erase the disagreement it was"
              + " flagged for.")
  @APIResponse(responseCode = "200", description = "The invoice and its match")
  @APIResponse(responseCode = "404", description = "Invoice not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    var invoice = svc.getSupplierInvoice(ctx, id);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    invoice,
                    svc.supplierInvoiceLines(ctx, id),
                    svc.matchPositions(ctx, invoice.poId()))))
        .build();
  }
}
