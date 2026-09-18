package com.shelfj.tenant.api;

import com.shelfj.tenant.dto.BillingDtos;
import com.shelfj.tenant.mapper.BillingMappers;
import com.shelfj.tenant.service.BillingService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The platform's own billing (21.9): who it invoices as, the rates it has to charge, the run that
 * raises the invoices, and the money that comes back. Every route is {@code PLATFORM_ADMIN} — this
 * is the platform's own books. What a business sees of its own subscription is {@link
 * TenantBillingResource}.
 */
@Path("/platform/billing")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Billing")
public class PlatformBillingResource {

  @Inject BillingService svc;
  @Inject com.shelfj.tenant.service.SubscriptionService subscriptions;
  @Inject TenantContext ctx;

  /**
   * Whether a caller may name the day the billing run is for.
   *
   * <p>Off in production, on in compose. A year of periods has to be drivable in seconds for a live
   * flow to exist at all — Stripe ships a test clock for the same reason — but a platform
   * administrator being able to bill next March is not a feature, it is a way to issue an invoice
   * nobody owes yet. So the ability is configuration, not a role.
   */
  @Inject
  @ConfigProperty(name = "shelfj.billing.test-clock.enabled", defaultValue = "false")
  boolean testClock;

  // ── who the platform invoices as ────────────────────────────────────────────

  @Operation(
      summary = "What the platform bills as",
      description = "Empty until it is set, and nothing is invoiced until then.")
  @GET
  @Path("/profile")
  public ApiResponse<BillingDtos.ProfileResponse> profile() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(svc.profile()));
  }

  @Operation(summary = "Sets what the platform bills as")
  @PUT
  @Path("/profile")
  public ApiResponse<BillingDtos.ProfileResponse> saveProfile(
      @Valid BillingDtos.ProfileRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(svc.saveProfile(req, ctx.requireUserId())));
  }

  // ── the rates it has to charge where it has no choice ───────────────────────

  @Operation(
      summary = "The VAT rates the platform has set",
      description =
          "A business in another member state with no checked VAT number is charged its own"
              + " country's rate. A country with no rate here refuses the invoice rather than"
              + " guessing at one.")
  @GET
  @Path("/vat-rates")
  public ApiResponse<List<BillingDtos.RateResponse>> rates() {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.rates(svc.rates()));
  }

  @Operation(summary = "Sets a country's rate from a date")
  @PUT
  @Path("/vat-rates")
  public ApiResponse<List<BillingDtos.RateResponse>> saveRate(@Valid BillingDtos.RateRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.rates(svc.saveRate(req, ctx.requireUserId())));
  }

  // ── the evidence behind a reverse charge ────────────────────────────────────

  @Operation(
      summary = "Records that a business's VAT number has been checked",
      description =
          "The reverse charge rests on the number being real, so who checked it and when is kept —"
              + " an audit asks for the date, not for an assurance. Calling VIES is a seam, like the"
              + " e-invoicing networks: SIMULATED is the honest answer until there is a contract,"
              + " and it says on the record that nothing left the building. A business supplies its"
              + " own number; checking it is the platform's, and changing the number afterwards"
              + " discards the check.")
  @POST
  @Path("/tenants/{tenantId}/vat-check")
  public ApiResponse<BillingDtos.SubscriptionFileResponse> recordVatCheck(
      @PathParam("tenantId") UUID tenantId, @Valid BillingDtos.VatCheckRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        BillingMappers.toDto(
            subscriptions.recordVatCheck(
                tenantId, req.vatNumber(), req.source(), ctx.requireUserId())));
  }

  // ── the run ─────────────────────────────────────────────────────────────────

  @Operation(
      summary = "Raises the invoices that are due",
      description =
          "Every subscription whose period has run out is billed for the period it is entering. Safe"
              + " to run twice: a period is invoiced once, by a unique index, not by a check.")
  @POST
  @Path("/run")
  public ApiResponse<BillingDtos.RunResponse> run(@QueryParam("asOf") String asOf) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    LocalDate on = day(asOf);
    return ApiResponse.ok(BillingMappers.run(on, svc.run(on)));
  }

  // ── what is owed, and what has come in ──────────────────────────────────────

  @Operation(
      summary = "The invoices that are still owed, oldest first",
      description = "The platform's receivables: open invoices, with the overdue ones first.")
  @GET
  @Path("/receivables")
  public ApiResponse<List<BillingDtos.InvoiceResponse>> receivables(
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.invoices(svc.receivables(limit)));
  }

  @Operation(summary = "One invoice, with its lines, its payments and both snapshots")
  @GET
  @Path("/invoices/{invoiceId}")
  public ApiResponse<BillingDtos.InvoiceFileResponse> invoice(
      @PathParam("invoiceId") UUID invoiceId) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(svc.invoiceFile(invoiceId)));
  }

  @Operation(
      summary = "Records money received against an invoice",
      description =
          "Append-only, and it settles the invoice when what has been paid reaches the total. An"
              + " invoice already withdrawn takes no payment.")
  @POST
  @Path("/invoices/{invoiceId}/payments")
  public ApiResponse<BillingDtos.InvoiceFileResponse> recordPayment(
      @PathParam("invoiceId") UUID invoiceId, @Valid BillingDtos.RecordPaymentRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        BillingMappers.toDto(svc.recordPayment(invoiceId, req, ctx.requireUserId())));
  }

  @Operation(
      summary = "Withdraws an unpaid invoice",
      description =
          "It keeps its number and its place in the sequence — a gap is what an auditor asks about."
              + " An invoice that has taken money is not withdrawn; it is credited.")
  @POST
  @Path("/invoices/{invoiceId}/void")
  public ApiResponse<BillingDtos.InvoiceFileResponse> voidInvoice(
      @PathParam("invoiceId") UUID invoiceId, @Valid BillingDtos.VoidRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(BillingMappers.toDto(svc.voidInvoice(invoiceId, req.reason())));
  }

  /**
   * The day a run is for.
   *
   * @throws ApiException 403 {@code BILLING_TEST_CLOCK_DISABLED} when a caller names a day and this
   *     deployment does not allow it; 400 when the day is not a date
   */
  private LocalDate day(String asOf) {
    if (asOf == null || asOf.isBlank()) return LocalDate.now();
    if (!testClock) {
      throw ApiException.forbidden(
          "BILLING_TEST_CLOCK_DISABLED",
          "This deployment bills for today only; naming a day is a test facility and is switched"
              + " off here");
    }
    try {
      return LocalDate.parse(asOf.strip());
    } catch (java.time.format.DateTimeParseException e) {
      throw new ApiException(
          400, "BILLING_DATE_INVALID", "asOf is a date, as 2026-09-18", List.of(), e);
    }
  }
}
