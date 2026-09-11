package com.shelfj.purchase.api;

import com.shelfj.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.shelfj.purchase.dto.Dtos.CancelPurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.CreatePurchaseOrderRequest;
import com.shelfj.purchase.dto.Dtos.DecidePurchaseOrderRequest;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@RequestScoped
@Path("/purchase-orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Purchase Orders")
public class PurchaseOrderResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Create a purchase order",
      description = "Creates a DRAFT purchase order for the given supplier and store.")
  @APIResponse(responseCode = "201", description = "Purchase order created")
  @APIResponse(responseCode = "404", description = "Supplier not found")
  @POST
  public Response create(CreatePurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.createPurchaseOrder(req, ctx))))
        .build();
  }

  @Operation(
      summary = "List purchase orders",
      description = "Lists purchase orders for the caller's tenant.")
  @GET
  public Response list(@jakarta.ws.rs.QueryParam("limit") Integer limit) {
    int clamped = com.shelfj.web.Cursor.clampLimit(limit);
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrders(ctx, clamped).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(summary = "Get a purchase order", description = "Returns a single purchase order.")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.getPurchaseOrder(ctx, id)))).build();
  }

  @Operation(
      summary = "Submit a purchase order to the supplier",
      description =
          "Transitions a DRAFT purchase order to SUBMITTED, or to PENDING_APPROVAL when its net"
              + " value is above the submitter's own spend authority. Either way the submission is"
              + " recorded in the order's append-only approval trail. Spend authority is configured"
              + " per currency AND per role: there is no FX handling in Shelf-J, so a ceiling"
              + " expressed in one currency cannot be meaningfully compared against an order in"
              + " another. When no authority is configured at all, approval is off and this behaves"
              + " as it did before the feature existed.")
  @APIResponse(responseCode = "200", description = "Submitted, or routed for approval")
  @APIResponse(responseCode = "400", description = "Only DRAFT orders can be submitted")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  @Path("/{id}/submit")
  public Response submit(@PathParam("id") UUID id) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.submitPurchaseOrder(ctx, id)))).build();
  }

  @Operation(
      summary = "Approve a purchase order awaiting approval",
      description =
          "Moves a PENDING_APPROVAL order to SUBMITTED. The approver's own spend authority is"
              + " checked against the same figure by the same rule that routed it here — an"
              + " approval by someone who could not have submitted it themselves would defeat the"
              + " control entirely. The total is re-read from the order rather than taken from the"
              + " request, because a rejected order can be edited before it comes back.")
  @APIResponse(responseCode = "200", description = "Approved and submitted")
  @APIResponse(responseCode = "403", description = "Above the approver's own authority")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Order is not awaiting approval")
  @POST
  @Path("/{id}/approve")
  public Response approve(@PathParam("id") UUID id, DecidePurchaseOrderRequest req) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.approvePurchaseOrder(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "Reject a purchase order awaiting approval",
      description =
          "Returns a PENDING_APPROVAL order to DRAFT so it can be corrected and resubmitted,"
              + " recording the required reason in the approval trail. Rejecting needs no spend"
              + " authority: refusing to commit money is not itself a commitment, and requiring it"
              + " would leave an order too large for anyone configured stuck in the queue for good.")
  @APIResponse(responseCode = "200", description = "Rejected and returned to DRAFT")
  @APIResponse(responseCode = "400", description = "A rejection must state a reason")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Order is not awaiting approval")
  @POST
  @Path("/{id}/reject")
  public Response reject(@PathParam("id") UUID id, DecidePurchaseOrderRequest req) {
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.rejectPurchaseOrder(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "A purchase order's approval history",
      description =
          "Every submission and every decision, newest first. Append-only: a rejection sends the"
              + " order back to DRAFT to be resubmitted, so one order can cycle through several"
              + " decisions, and a trail that kept only the last one would not be a trail. Each row"
              + " carries the figure and the authority as they stood at the time, so it still"
              + " answers 'was that person allowed to commit that much?' after the configuration"
              + " has changed.")
  @APIResponse(responseCode = "200", description = "The approval trail")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/approvals")
  public Response approvals(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.purchaseOrderApprovals(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "What the caller may commit, in one currency",
      description =
          "The caller's own spend ceiling, so a buyer learns their authority before building an"
              + " order rather than after trying to submit it. Measured on the order's NET value:"
              + " VAT is recoverable for a VAT-registered business and is therefore not spend.")
  @APIResponse(responseCode = "200", description = "The caller's authority in that currency")
  @APIResponse(responseCode = "400", description = "Not an ISO 4217 currency code")
  @GET
  @Path("/spend-authority")
  public Response spendAuthority(@QueryParam("currency") String currency) {
    var authority = svc.spendAuthority(ctx, currency);
    return Response.ok(
            ApiResponse.ok(
                Mappers.toDto(
                    authority,
                    currency.trim().toUpperCase(java.util.Locale.ROOT),
                    !svc.approvalEnabled())))
        .build();
  }

  @Operation(
      summary = "Cancel a purchase order",
      description =
          "Transitions a DRAFT or SUBMITTED purchase order to CANCELLED, recording the reason. A"
              + " RECEIVED order cannot be cancelled -- stock is already booked against it.")
  @APIResponse(responseCode = "200", description = "Purchase order cancelled")
  @APIResponse(responseCode = "400", description = "Cancellation reason missing or blank")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(
      responseCode = "409",
      description = "Purchase order is already RECEIVED or CANCELLED")
  @POST
  @Path("/{id}/cancel")
  public Response cancel(@PathParam("id") UUID id, CancelPurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.cancelPurchaseOrder(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "What is still outstanding on a purchase order",
      description =
          "Ordered against received, line by line, with the balance still due. This is what a"
              + " PARTIALLY_RECEIVED status does not tell you: a buyer chasing a supplier needs to"
              + " know *what* is missing. Receipts are matched to order lines by variant rather"
              + " than by line id, because a delivery note names products, not order rows.")
  @APIResponse(responseCode = "200", description = "One row per ordered variant")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/progress")
  public Response progress(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.purchaseOrderProgress(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }

  @Operation(
      summary = "Short-close a partially received purchase order",
      description =
          "Marks a partly delivered order CLOSED: the balance is never arriving and we have stopped"
              + " waiting. Requires a reason. Only a PARTIALLY_RECEIVED order can be short-closed —"
              + " one with nothing delivered is a cancellation, and one fully delivered is already"
              + " RECEIVED. CLOSED is deliberately not RECEIVED, because 'we got it all' and 'we"
              + " gave up on the rest' are different facts and a supplier scorecard that cannot"
              + " tell them apart is worthless.")
  @APIResponse(responseCode = "200", description = "Purchase order short-closed")
  @APIResponse(responseCode = "400", description = "Reason missing or blank")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @APIResponse(responseCode = "409", description = "Order is not PARTIALLY_RECEIVED")
  @POST
  @Path("/{id}/close")
  public Response close(@PathParam("id") UUID id, CancelPurchaseOrderRequest req) {
    Validations.validate(req);
    return Response.ok(ApiResponse.ok(Mappers.toDto(svc.closePurchaseOrderShort(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "Add a line to a purchase order",
      description = "Lines can only be added while the purchase order is DRAFT.")
  @APIResponse(responseCode = "201", description = "Line added")
  @APIResponse(responseCode = "400", description = "Purchase order is not DRAFT")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @POST
  @Path("/{id}/lines")
  public Response addLine(@PathParam("id") UUID id, AddPurchaseOrderLineRequest req) {
    Validations.validate(req);
    return Response.status(201)
        .entity(ApiResponse.ok(Mappers.toDto(svc.addPurchaseOrderLine(ctx, id, req))))
        .build();
  }

  @Operation(
      summary = "List a purchase order's lines",
      description = "Returns all lines on the given purchase order.")
  @APIResponse(responseCode = "404", description = "Purchase order not found")
  @GET
  @Path("/{id}/lines")
  public Response listLines(@PathParam("id") UUID id) {
    return Response.ok(
            ApiResponse.ok(
                svc.listPurchaseOrderLines(ctx, id).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
