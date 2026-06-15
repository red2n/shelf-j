package com.shelfj.payment.api;

import com.shelfj.payment.dto.Dtos.RecordRefundRequest;
import com.shelfj.payment.dto.Dtos.RecordTenderRequest;
import com.shelfj.payment.mapper.Mappers;
import com.shelfj.payment.service.PaymentService;
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

/** Payment tenders and refunds. */
@RequestScoped
@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentResource {

  @Inject PaymentService svc;
  @Inject TenantContext ctx;

  /** Record a payment tender for an order. Returns 201 with the tender on success. */
  @POST
  public Response record(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      RecordTenderRequest req) {
    ctx.requireAnyRole("CASHIER", "MANAGER", "OWNER", "PLATFORM_ADMIN");
    Validations.validate(req);
    var tender = svc.recordTender(req, ctx, effectiveKey(idempotencyKey, req.idempotencyKey()));
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  /**
   * Online customer payment for the guest storefront. No staff role required — reachable via the
   * gateway's storefront whitelist (tenant from {@code X-Storefront-Tenant}) or by an authenticated
   * customer. Cashless only; cash tenders are POS-staff territory via {@link #record}. Captures
   * immediately, emitting {@code PaymentCaptured} so order-svc confirms the order. (Hardening TODO:
   * verify amount against the order total via order-svc; integrate a real payment provider.)
   */
  @POST
  @Path("/online")
  public Response payOnline(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      RecordTenderRequest req) {
    Validations.validate(req);
    if (req.method() != null && "CASH".equalsIgnoreCase(req.method())) {
      throw com.shelfj.web.ApiException.badRequest(
          "PAYMENT_ONLINE_CASHLESS", "Online payments must be cashless (card/wallet)");
    }
    var tender = svc.recordTender(req, ctx, effectiveKey(idempotencyKey, req.idempotencyKey()));
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    var tender = svc.getTender(ctx.requireTenantId(), id);
    return Response.ok(ApiResponse.ok(Mappers.toDto(tender))).build();
  }

  /** List all payment tenders recorded for a given order. */
  @GET
  @Path("/by-order/{orderId}")
  public Response listByOrder(@PathParam("orderId") UUID orderId) {
    var tenders = svc.listTendersByOrder(ctx.requireTenantId(), orderId);
    return Response.ok(ApiResponse.ok(tenders.stream().map(Mappers::toDto).toList())).build();
  }

  /** Record a refund against a previously captured tender. MANAGER or above only. */
  @POST
  @Path("/by-order/{orderId}/refunds")
  public Response recordRefund(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      @PathParam("orderId") UUID orderId,
      RecordRefundRequest req) {
    ctx.requireAnyRole("MANAGER", "OWNER", "PLATFORM_ADMIN");
    Validations.validate(req);
    var refund =
        svc.recordRefund(
            ctx.requireTenantId(),
            orderId,
            req,
            effectiveKey(idempotencyKey, req.idempotencyKey()));
    return Response.status(201).entity(ApiResponse.ok(Mappers.toDto(refund))).build();
  }

  /** The standard Idempotency-Key header is authoritative; the body field is a legacy fallback. */
  private static String effectiveKey(String header, String bodyField) {
    return header != null && !header.isBlank() ? header : bodyField;
  }

  /** List all refunds for a given order. */
  @GET
  @Path("/by-order/{orderId}/refunds")
  public Response listRefunds(@PathParam("orderId") UUID orderId) {
    var refunds = svc.listRefundsByOrder(ctx.requireTenantId(), orderId);
    return Response.ok(ApiResponse.ok(refunds.stream().map(Mappers::toDto).toList())).build();
  }
}
