package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.ContainerRefundRequest;
import com.shelfj.order.dto.Dtos.ContainerRefundResponse;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Deposits paid back at the till for drinks containers brought back (09.16). The scheme in force
 * where the store trades says which containers it takes back and for how much; the refund leaves
 * the till session's drawer through payment-svc, once per refund.
 */
@RequestScoped
@Path("/orders/container-refunds")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Deposit return")
public class ContainerRefundResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Pays the deposit back on containers brought to the till.
   *
   * @param idempotencyKey the till's key for this refund; a retry with the same key pays out once
   * @param req the containers by material and volume
   * @return the refund, with the amount for each kind
   */
  @Operation(
      summary = "Refund the deposit on containers brought back",
      description =
          "Records a refund of the return-scheme deposit on drinks containers brought back to the"
              + " till, at the amount the scheme in force where the store trades sets for each"
              + " container it takes back, and pays it out of the till session's drawer through"
              + " payment-svc once. CASHIER, MANAGER or OWNER; Idempotency-Key required.")
  @APIResponse(responseCode = "201", description = "Refund recorded")
  @APIResponse(
      responseCode = "400",
      description =
          "No lines, too many kinds or containers, a container the scheme does not take back, or"
              + " no Idempotency-Key")
  @APIResponse(
      responseCode = "409",
      description = "No scheme in force where the store trades, or the store is not trading")
  @POST
  public Response refund(
      @HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      ContainerRefundRequest req) {
    Validations.validate(req);
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw ApiException.badRequest(
          "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required to refund a deposit");
    }
    var refund = svc.refundContainers(req, ctx, idempotencyKey.trim());
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(refund))).build();
  }

  /**
   * A refund by id.
   *
   * @param id the refund
   * @return the refund with its lines
   */
  @Operation(summary = "Get a container deposit refund")
  @APIResponse(responseCode = "200", description = "The refund")
  @APIResponse(responseCode = "404", description = "Not this business's refund")
  @GET
  @Path("/{id}")
  public ApiResponse<ContainerRefundResponse> get(@PathParam("id") String id) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER");
    return ApiResponse.ok(
        Mappers.toDto(svc.containerRefund(ctx.requireTenantId(), Parsing.uuid(id, "id"))));
  }
}
