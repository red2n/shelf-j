package com.storeql.order.api;

import com.storeql.order.domain.EInvoiceTransports.Transmission;
import com.storeql.order.domain.SalesInvoices.SalesInvoice;
import com.storeql.order.dto.SalesInvoiceDtos;
import com.storeql.order.mapper.SalesInvoiceMappers;
import com.storeql.order.service.EInvoiceTransportService;
import com.storeql.order.service.SalesInvoiceService;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Invoices and credit notes to business buyers (18.9).
 *
 * <p>Issued without anyone asking when a sale to a VAT-registered customer completes, and when a
 * return is taken against an invoiced sale. These endpoints read them back, download them, and
 * issue one by hand when the automatic issue was refused and the reason has since been put right.
 * Management-only, as everything under {@code /admin} is.
 */
@RequestScoped
@Path("/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Sales invoices")
public class SalesInvoiceResource {

  static final int MAX_PAGE = 100;

  @Inject SalesInvoiceService svc;
  @Inject EInvoiceTransportService transport;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Invoice a sale to a business",
      description =
          "Idempotent: the invoice already issued for the sale comes back rather than a second"
              + " number. The business is named as its tenant identity holds it, the buyer as"
              + " its VAT registration and billing address do, and every line at the rate its"
              + " quote applied. The document is checked against EN 16931 (and Peppol BIS"
              + " Billing 3.0 when both parties have a network address) before its number is"
              + " kept, so a refusal takes no number. An Indian business's document is also"
              + " written as the IRP's INV-01, or kept with what the portal would refuse.")
  @APIResponse(responseCode = "200", description = "The invoice, newly issued or already existing")
  @APIResponse(responseCode = "404", description = "No such sale")
  @APIResponse(
      responseCode = "409",
      description =
          "Not a completed sale; no customer, or one not VAT-registered or without an address; the"
              + " business without a VAT number or legal name; or a document that would break"
              + " EN 16931 or disagree with what the sale charged")
  @APIResponse(responseCode = "503", description = "A service the document needs was unreachable")
  @POST
  @Path("/orders/{orderId}/invoice")
  public Response issueInvoice(@PathParam("orderId") UUID orderId) {
    return one(svc.issueInvoice(ctx.requireTenantId(), orderId, ctx.userId()));
  }

  @Operation(
      summary = "A sale's invoice and credit notes",
      description = "Newest first; empty when the sale was never invoiced.")
  @APIResponse(responseCode = "200", description = "The documents")
  @GET
  @Path("/orders/{orderId}/invoices")
  public Response ofOrder(@PathParam("orderId") UUID orderId) {
    return Response.ok(
            ApiResponse.ok(dtos(svc.list(ctx.requireTenantId(), orderId, null, MAX_PAGE))))
        .build();
  }

  @Operation(
      summary = "Credit a return against an invoiced sale",
      description =
          "Idempotent, like the invoice. The returned goods at the rates they were sold at, with"
              + " the invoice they correct named as its preceding document.")
  @APIResponse(responseCode = "200", description = "The credit note, newly issued or existing")
  @APIResponse(responseCode = "404", description = "No such return")
  @APIResponse(responseCode = "409", description = "The sale was never invoiced, or as an invoice")
  @APIResponse(responseCode = "503", description = "A service the document needs was unreachable")
  @POST
  @Path("/returns/{returnId}/credit-note")
  public Response issueCreditNote(@PathParam("returnId") UUID returnId) {
    return one(svc.issueCreditNote(ctx.requireTenantId(), returnId, ctx.userId()));
  }

  @Operation(
      summary = "Every invoice and credit note",
      description = "Newest first, a page at a time: pass meta.nextCursor back as after.")
  @APIResponse(responseCode = "200", description = "One page")
  @APIResponse(responseCode = "400", description = "A cursor that is not a document id")
  @GET
  @Path("/sales-invoices")
  public Response list(
      @QueryParam("after") String after, @QueryParam("limit") @DefaultValue("20") int limit) {
    int size = Math.max(1, Math.min(limit, MAX_PAGE));
    List<SalesInvoice> page =
        svc.list(ctx.requireTenantId(), null, Parsing.optionalUuid(after, "after"), size);
    String next = page.size() == size ? page.get(page.size() - 1).id().toString() : null;
    return Response.ok(ApiResponse.ok(dtos(page), new ApiResponse.Meta(ctx.requestId(), next)))
        .build();
  }

  @Operation(summary = "One invoice or credit note")
  @APIResponse(responseCode = "200", description = "The document's facts")
  @APIResponse(responseCode = "404", description = "No such document")
  @GET
  @Path("/sales-invoices/{id}")
  public Response get(@PathParam("id") UUID id) {
    return one(svc.get(ctx.requireTenantId(), id));
  }

  @Operation(
      summary = "Download an invoice or credit note",
      description =
          "format=UBL (the default) is the document as issued; CII is the same document in"
              + " UN/CEFACT syntax; FACTURX is a PDF/A-3 with the CII inside; IRP is an Indian"
              + " business's INV-01 JSON for the Invoice Registration Portal.")
  @APIResponse(responseCode = "200", description = "The file")
  @APIResponse(responseCode = "400", description = "An unknown format")
  @APIResponse(
      responseCode = "404",
      description = "No such document, or IRP asked of a business outside India")
  @APIResponse(
      responseCode = "409",
      description = "IRP asked of a document the portal would refuse")
  @GET
  @Path("/sales-invoices/{id}/document")
  @Produces({MediaType.APPLICATION_XML, "application/pdf", MediaType.APPLICATION_JSON})
  public Response document(@PathParam("id") UUID id, @QueryParam("format") String format) {
    SalesInvoiceService.Download file = svc.document(ctx.requireTenantId(), id, format);
    return Response.ok(file.bytes())
        .type(file.contentType())
        .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .build();
  }

  private Response one(SalesInvoice s) {
    return Response.ok(ApiResponse.ok(dtos(List.of(s)).get(0))).build();
  }

  /** The documents with the newest attempt to send each, in one read. */
  private List<SalesInvoiceDtos.SalesInvoiceResponse> dtos(List<SalesInvoice> docs) {
    Map<UUID, Transmission> latest =
        transport.latest(ctx.requireTenantId(), docs.stream().map(SalesInvoice::id).toList());
    return docs.stream().map(d -> SalesInvoiceMappers.toDto(d, latest.get(d.id()))).toList();
  }
}
