package com.storeql.payment.api;

import com.storeql.ids.Ids;
import com.storeql.payment.dto.TerminalDtos;
import com.storeql.payment.mapper.TerminalMappers;
import com.storeql.payment.service.TerminalService;
import com.storeql.web.ApiException;
import com.storeql.web.ApiResponse;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
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
 * Taking a card on a terminal, from the till (07.16).
 *
 * <p><b>Its own class, rooted at {@code /payments/terminal}, and that is not cosmetic.</b> JAX-RS
 * chooses ONE resource class by the best match on its root path and then looks for the method only
 * inside that class. These routes first lived on a class rooted at {@code "/"} with absolute paths
 * on each method, which made every one of them unreachable: {@code PaymentResource} is rooted at
 * {@code /payments}, that beat {@code "/"} for a URI beginning {@code /payments/}, and the request
 * 404'd without ever being offered to this code. A root of {@code /payments/terminal} has more
 * literal characters than {@code /payments}, so it wins the URIs that are actually ours.
 *
 * <p>It was found by running the service and asking it, not by a test: the integration test calls
 * the service object directly, so it never exercised a route. The k6 flow does, and would have
 * caught it on its first run.
 *
 * <p><b>No route here accepts a card number.</b> The terminal reads the card; this platform sends
 * an amount and receives a verdict.
 */
@Path("/payments/terminal")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Card terminals")
public class TerminalPaymentResource {

  @Inject TerminalService svc;
  @Inject TenantContext ctx;

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
  public Response sale(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
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
  @Path("/{id}/refunds")
  public Response refund(
      @jakarta.ws.rs.HeaderParam(com.storeql.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
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
  @Path("/{id}/cancel")
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
  @Path("/by-order/{orderId}")
  public ApiResponse<List<TerminalDtos.AttemptResponse>> byOrder(
      @PathParam("orderId") UUID orderId) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return ApiResponse.ok(TerminalMappers.attempts(svc.attemptsOf(ctx.requireTenantId(), orderId)));
  }

  @Operation(summary = "One terminal attempt")
  @GET
  @Path("/{id}")
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

  private static UUID uuid(String value, String field) {
    try {
      return Ids.parse(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "TERMINAL_ID_INVALID", field + " is not an id", List.of(), e);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
