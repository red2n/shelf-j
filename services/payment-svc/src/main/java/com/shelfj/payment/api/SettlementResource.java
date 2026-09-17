package com.shelfj.payment.api;

import com.shelfj.payment.domain.Settlements;
import com.shelfj.payment.dto.SettlementDtos;
import com.shelfj.payment.service.SettlementService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.HttpHeaders;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Settlement reconciliation (11.10): the acquirer's payouts against the card payments this service
 * holds. Management's: it is the business's money arriving, or not arriving, at the bank.
 */
@Path("/admin/settlements")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Settlements")
public class SettlementResource {

  @Inject SettlementService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Payouts imported",
      description = "The newest import first; ?status= EXCEPTIONS, READY or RECONCILED.")
  @GET
  public ApiResponse<List<SettlementDtos.BatchResponse>> list(
      @QueryParam("status") String status,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Cursor.Page<Settlements.Batch> page = svc.list(ctx.requireTenantId(), status, after, limit);
    return ApiResponse.ok(
        page.items().stream().map(SettlementResource::toDto).toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "Import one payout's settlement file",
      description =
          "Every line is matched to the payment, refund or dispute held here. A file in which"
              + " everything matches is reconciled at once; otherwise its exceptions wait for a"
              + " decision. Takes an Idempotency-Key. OWNER or MANAGER.")
  @APIResponse(responseCode = "201", description = "Imported")
  @APIResponse(responseCode = "400", description = "The file cannot be read or does not add up")
  @APIResponse(responseCode = "409", description = "This payout has been imported already")
  @POST
  public Response importFile(
      SettlementDtos.ImportRequest req, @HeaderParam(HttpHeaders.IDEMPOTENCY_KEY) String key) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    Settlements.Batch batch = svc.importFile(ctx.requireTenantId(), ctx.requireUserId(), req, key);
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(toDto(batch))).build();
  }

  @Operation(summary = "The file layouts this service reads")
  @GET
  @Path("/formats")
  public ApiResponse<SettlementDtos.FormatsResponse> formats() {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        new SettlementDtos.FormatsResponse(svc.formats(), SettlementService.maxLines()));
  }

  @Operation(
      summary = "Card payments no settlement has covered",
      description =
          "Taken at least ?olderThanDays= ago (3 when absent), the oldest first; ?storeId= one"
              + " store.")
  @GET
  @Path("/unsettled")
  public ApiResponse<List<SettlementDtos.UnsettledResponse>> unsettled(
      @QueryParam("olderThanDays") Integer olderThanDays,
      @QueryParam("storeId") String storeId,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Cursor.Page<Settlements.Unsettled> page =
        svc.unsettled(
            ctx.requireTenantId(),
            Parsing.optionalUuid(storeId, "storeId"),
            olderThanDays,
            after,
            limit);
    Instant now = Instant.now();
    return ApiResponse.ok(
        page.items().stream()
            .map(
                u ->
                    new SettlementDtos.UnsettledResponse(
                        u.tenderId().toString(),
                        u.orderId().toString(),
                        u.storeId() == null ? null : u.storeId().toString(),
                        u.method(),
                        u.reference(),
                        u.amount(),
                        u.capturedAt().toString(),
                        Duration.between(u.capturedAt(), now).toDays()))
            .toList(),
        new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  @Operation(
      summary = "One payout with a page of its lines",
      description = "In file order; ?open=true keeps to the lines still waiting for a decision.")
  @GET
  @Path("/{id}")
  public ApiResponse<SettlementDtos.BatchFileResponse> get(
      @PathParam("id") UUID id,
      @QueryParam("open") Boolean open,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Settlements.BatchFile file =
        svc.file(ctx.requireTenantId(), id, Boolean.TRUE.equals(open), after, limit);
    return ApiResponse.ok(toDto(file), new ApiResponse.Meta(ctx.requestId(), file.nextCursor()));
  }

  @Operation(
      summary = "Decide a line that did not match",
      description =
          "MATCHED_BY_HAND points it at the payment, refund or dispute it is about;"
              + " DIFFERENCE_ACCEPTED keeps the link and accepts that the sums differ; UNALLOCATED"
              + " sends its money to unallocated receipts. Answers the lines still open.")
  @APIResponse(responseCode = "409", description = "Reconciled already, or nothing to decide")
  @POST
  @Path("/{id}/lines/{lineId}/resolve")
  public ApiResponse<SettlementDtos.BatchFileResponse> resolve(
      @PathParam("id") UUID id,
      @PathParam("lineId") UUID lineId,
      SettlementDtos.ResolveRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    return ApiResponse.ok(
        toDto(svc.resolve(ctx.requireTenantId(), ctx.requireUserId(), id, lineId, req)));
  }

  @Operation(
      summary = "Sign a payout off",
      description = "Once every exception in it has been decided. The ledger is told.")
  @APIResponse(responseCode = "409", description = "Still has exceptions, or reconciled already")
  @POST
  @Path("/{id}/reconcile")
  public ApiResponse<SettlementDtos.BatchResponse> reconcile(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(toDto(svc.reconcile(ctx.requireTenantId(), ctx.requireUserId(), id)));
  }

  private static SettlementDtos.BatchResponse toDto(Settlements.Batch b) {
    return new SettlementDtos.BatchResponse(
        b.id().toString(),
        text(b.storeId()),
        b.provider(),
        b.reference(),
        b.format(),
        b.currency(),
        b.payoutDate().toString(),
        b.declaredNet(),
        b.salesAmount(),
        b.refundAmount(),
        b.chargebackAmount(),
        b.feeAmount(),
        b.netAmount(),
        b.lineCount(),
        b.openExceptions(),
        b.status(),
        b.importedBy().toString(),
        b.importedAt().toString(),
        text(b.reconciledBy()),
        text(b.reconciledAt()));
  }

  private static SettlementDtos.BatchFileResponse toDto(Settlements.BatchFile f) {
    return new SettlementDtos.BatchFileResponse(
        toDto(f.batch()),
        f.lines().stream()
            .map(
                l ->
                    new SettlementDtos.LineResponse(
                        l.id().toString(),
                        l.lineNo(),
                        l.type(),
                        l.reference(),
                        l.originalReference(),
                        l.gross(),
                        l.fee(),
                        l.net(),
                        text(l.occurredAt()),
                        l.matchStatus(),
                        l.open(),
                        text(l.tenderId()),
                        text(l.refundId()),
                        text(l.disputeId()),
                        text(l.storeId()),
                        l.expectedAmount(),
                        l.resolution(),
                        text(l.resolvedBy()),
                        text(l.resolvedAt()),
                        l.note()))
            .toList());
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
