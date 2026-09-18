package com.shelfj.payment.api;

import com.shelfj.payment.dto.TerminalDtos;
import com.shelfj.payment.mapper.TerminalMappers;
import com.shelfj.payment.service.TerminalService;
import com.shelfj.web.ApiException;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * EMV terminals, and taking a card on one (07.16).
 *
 * <p>Two audiences, deliberately split. The register at {@code /admin/payments/terminals} is
 * management's: which devices a business has and where. Taking a card at {@code /payments/terminal}
 * is the till's, so a cashier can do it.
 *
 * <p><b>No route here accepts a card number.</b> The terminal reads the card; this platform sends
 * an amount and receives a verdict.
 */
@Path("/")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Card terminals")
public class TerminalResource {

  @Inject TerminalService svc;
  @Inject TenantContext ctx;

  // ── the register ────────────────────────────────────────────────────────────

  @Operation(
      summary = "Register an EMV terminal for a store",
      description =
          "A terminal belongs to a store: it is a physical object on a counter, and a payment taken on"
              + " it is taken there. The vendor must be configured on this deployment — said here,"
              + " rather than at the till with a customer waiting. SIMULATED is always available and"
              + " says on the record that nothing left the building.")
  @APIResponse(responseCode = "201", description = "Terminal registered")
  @APIResponse(responseCode = "409", description = "Vendor not configured, or label/serial taken")
  @POST
  @Path("/admin/payments/terminals")
  public Response register(TerminalDtos.RegisterRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    // Belt to the gateway's braces: nothing card-shaped may arrive by any route, including a label.
    TerminalService.refuseCardData(req.label(), req.serial());
    UUID storeId = uuid(req.storeId(), "storeId");
    ctx.requireStoreAccess(storeId);
    var t =
        svc.register(
            ctx.requireTenantId(),
            storeId,
            req.label(),
            req.vendor(),
            req.serial(),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(TerminalMappers.toDto(t))).build();
  }

  @Operation(
      summary = "The terminals this business has",
      description =
          "Retired ones included, so history reads: a payment points at the device it was taken on.")
  @GET
  @Path("/admin/payments/terminals")
  public ApiResponse<List<TerminalDtos.TerminalResponse>> list() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(TerminalMappers.terminals(svc.list(ctx.requireTenantId())));
  }

  @Operation(
      summary = "Retire a terminal",
      description =
          "Never deleted: payments point at it. Its label is then free for the device that replaces"
              + " it, which is what happens when one is swapped after a fault.")
  @POST
  @Path("/admin/payments/terminals/{id}/retire")
  public ApiResponse<TerminalDtos.TerminalResponse> retire(
      @PathParam("id") UUID id, TerminalDtos.RetireRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        TerminalMappers.toDto(
            svc.retire(ctx.requireTenantId(), id, req == null ? null : req.reason())));
  }

  // ── taking a card ───────────────────────────────────────────────────────────

  @Operation(
      summary = "Take a card on a terminal",
      description =
          "Sends the amount to the device and returns what it said. The attempt is recorded BEFORE the"
              + " terminal is asked, with the Idempotency-Key, so a retried press finds the first"
              + " attempt instead of starting a second EMV transaction on a real card — which is the"
              + " canonical double-charge. An APPROVED attempt is the only one that records a tender."
              + " A TIMED_OUT attempt records none and is never retried automatically: the card may"
              + " have been charged, so the cashier reads the terminal's screen and the attempt is"
              + " reconciled against the acquirer's settlement file.")
  @APIResponse(responseCode = "201", description = "The terminal answered — read `state`")
  @APIResponse(responseCode = "409", description = "Terminal retired, or its vendor unavailable")
  @POST
  @Path("/payments/terminal")
  public Response sale(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      TerminalDtos.SaleRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    Validations.validate(req);
    var attempt =
        svc.sale(
            ctx.requireTenantId(),
            uuid(req.terminalId(), "terminalId"),
            uuid(req.orderId(), "orderId"),
            req.amount(),
            req.currency().toUpperCase(java.util.Locale.ROOT),
            ctx.requireUserId(),
            blankToNull(idempotencyKey));
    return Response.status(201).entity(ApiResponse.ok(TerminalMappers.toDto(attempt))).build();
  }

  @Operation(
      summary = "Put money back on the card that paid",
      description =
          "Linked to the original attempt rather than taking a card again: an unlinked refund is how"
              + " card fraud is done, and most acquirers refuse them outright.")
  @POST
  @Path("/payments/terminal/{id}/refunds")
  public Response refund(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      @PathParam("id") UUID id,
      TerminalDtos.RefundRequest req) {
    // Management's, like every other refund on this platform: the money goes out, not in.
    ctx.requireAnyRole("MANAGER", "OWNER");
    Validations.validate(req);
    var attempt =
        svc.refund(
            ctx.requireTenantId(),
            id,
            req.amount(),
            ctx.requireUserId(),
            blankToNull(idempotencyKey));
    return Response.status(201).entity(ApiResponse.ok(TerminalMappers.toDto(attempt))).build();
  }

  @Operation(
      summary = "Stop the terminal asking for a card",
      description =
          "For a tender the cashier abandoned. Best effort by nature — the cardholder may have"
              + " completed it meanwhile — so a cancel that races an approval loses, and the attempt"
              + " comes back approved.")
  @POST
  @Path("/payments/terminal/{id}/cancel")
  public ApiResponse<TerminalDtos.AttemptResponse> cancel(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return ApiResponse.ok(TerminalMappers.toDto(svc.cancel(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Every terminal attempt against an order",
      description =
          "Declines included, oldest first: a declined card followed by a cash tender reads in the"
              + " order it happened, which is what a cashier and an auditor both need.")
  @GET
  @Path("/payments/terminal/by-order/{orderId}")
  public ApiResponse<List<TerminalDtos.AttemptResponse>> byOrder(
      @PathParam("orderId") UUID orderId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return ApiResponse.ok(TerminalMappers.attempts(svc.attemptsOf(ctx.requireTenantId(), orderId)));
  }

  @Operation(summary = "One terminal attempt")
  @GET
  @Path("/payments/terminal/{id}")
  public ApiResponse<TerminalDtos.AttemptResponse> one(@PathParam("id") UUID id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return ApiResponse.ok(
        TerminalMappers.toDto(
            svc.attempt(ctx.requireTenantId(), id)
                .orElseThrow(
                    () ->
                        ApiException.notFound(
                            "TERMINAL_ATTEMPT_NOT_FOUND", "No such payment on a terminal"))));
  }

  /** The vendors this deployment can talk to, so a screen offers only those. */
  @Operation(
      summary = "The terminal vendors this deployment can talk to",
      description =
          "A vendor whose credentials are not configured is not offered, so a business is not asked to"
              + " pair a device the platform cannot reach.")
  @GET
  @Path("/admin/payments/terminals/vendors")
  public ApiResponse<List<String>> vendors() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(svc.availableVendors());
  }

  private static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "TERMINAL_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
