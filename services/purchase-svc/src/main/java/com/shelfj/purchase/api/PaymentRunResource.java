package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.CancelPaymentRunRequest;
import com.shelfj.purchase.dto.Dtos.ProposePaymentRunRequest;
import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PaymentRunService;
import com.shelfj.web.ApiResponse;
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
 * Supplier payment runs and remittance (17.10). Every route needs a management role and {@code
 * finance.payments}.
 */
@RequestScoped
@Path("/payment-runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Payment Runs")
public class PaymentRunResource {

  @Inject PaymentRunService svc;
  @Inject com.shelfj.web.TenantContext ctx;

  @Operation(
      summary = "Propose a payment run",
      description =
          "Collects every invoice that may be paid — MATCHED, or FLAGGED and then APPROVED; posted;"
              + " unpaid; in the run's currency; due on or before payUpTo; not held by another open"
              + " run — and each supplier's unallocated credit notes, which are offset against"
              + " what it is paid. A supplier with no bank details, or whose credits cover its"
              + " invoices, is left out and listed under excluded with the reason. A supplier whose"
              + " bank details changed in the last 14 days is paid but carries the warning"
              + " BANK_DETAILS_CHANGED_RECENTLY. The documents are reserved to the run: a second"
              + " proposal will not pick them up, and two racing proposals produce one run and"
              + " one 409.")
  @APIResponse(responseCode = "201", description = "The proposed run")
  @APIResponse(responseCode = "400", description = "A bad date or currency")
  @APIResponse(responseCode = "403", description = "Not management, or no finance.payments")
  @APIResponse(
      responseCode = "409",
      description =
          "Nothing payable is due (PURCHASE_PAYMENT_RUN_NOTHING_DUE, with each supplier left out"
              + " and why), or a concurrent proposal took a document (PURCHASE_PAYMENT_RUN_CONFLICT)")
  @POST
  public Response propose(ProposePaymentRunRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.propose(ctx, req))))
        .build();
  }

  @Operation(summary = "List payment runs", description = "Newest first; ?status= filters.")
  @APIResponse(responseCode = "200", description = "Runs, newest first")
  @APIResponse(responseCode = "400", description = "A status that is not one of the four")
  @GET
  public Response list(
      @QueryParam("status") String status, @QueryParam("limit") @DefaultValue("20") int limit) {
    return Response.ok(
            ApiResponse.ok(
                svc.list(ctx, status, Math.min(Math.max(limit, 1), 100)).stream()
                    .map(Mappers::toDto)
                    .toList()))
        .build();
  }

  @Operation(summary = "One payment run, with what it pays each supplier")
  @APIResponse(responseCode = "200", description = "The run")
  @APIResponse(responseCode = "404", description = "Not found in this tenant")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.get(ctx, id)))).build();
  }

  @Operation(
      summary = "Approve a proposed run",
      description =
          "By someone other than its proposer, unless the approver is the owner. Refused when a"
              + " supplier in the run can no longer be paid.")
  @APIResponse(responseCode = "200", description = "The run, APPROVED")
  @APIResponse(responseCode = "403", description = "PURCHASE_PAYMENT_RUN_SELF_APPROVAL")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(responseCode = "409", description = "Not PROPOSED, or PURCHASE_PAYMENT_RUN_STALE")
  @POST
  @Path("/{id}/approve")
  public Response approve(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.approve(ctx, id)))).build();
  }

  @Operation(
      summary = "Pay an approved run",
      description =
          "Settles every invoice and allocates every credit note in the run, posts Dr 2100"
              + " Creditors / Cr 1200 Bank per supplier and store dated the payment date, and"
              + " emails each supplier with a remittance address its advice. Once only: twenty"
              + " concurrent calls are one payment and nineteen 409s. Refused when any supplier's"
              + " bank details changed after approval.")
  @APIResponse(responseCode = "200", description = "The run, PAID")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(
      responseCode = "409",
      description =
          "Not APPROVED, already paid, cancelled, stale, bank details changed after approval, or"
              + " the period is closed")
  @POST
  @Path("/{id}/pay")
  public Response pay(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.pay(ctx, id)))).build();
  }

  @Operation(
      summary = "Cancel a run that is not yet paid",
      description = "With a reason. Its documents are free for the next run.")
  @APIResponse(responseCode = "200", description = "The run, CANCELLED")
  @APIResponse(responseCode = "400", description = "No reason")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(responseCode = "409", description = "Already paid or already cancelled")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id, CancelPaymentRunRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.cancel(ctx, id, req)))).build();
  }

  @Operation(
      summary = "The bank file for an approved or paid run",
      description =
          "CSV, one payment per supplier: payee name, sort code and account number or IBAN and"
              + " BIC, amount, currency and the run reference. Cells are guarded against formula"
              + " injection. Never cached. Refused when any supplier's bank details changed after"
              + " the run was approved.")
  @APIResponse(responseCode = "200", description = "The file")
  @APIResponse(responseCode = "404", description = "Not found")
  @APIResponse(responseCode = "409", description = "Not approved, or bank details changed")
  @GET
  @Path("/{id}/bank-file")
  @Produces({"text/csv", MediaType.APPLICATION_JSON})
  public Response bankFile(@PathParam("id") UUID id) {
    PaymentRunService.Export file = svc.bankFile(ctx, id);
    return Response.ok(file.csv())
        .type("text/csv")
        .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
        .header("Cache-Control", "no-store")
        .build();
  }
}
