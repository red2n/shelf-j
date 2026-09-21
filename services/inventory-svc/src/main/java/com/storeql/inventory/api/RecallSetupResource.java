package com.storeql.inventory.api;

import com.storeql.inventory.domain.Recall.Hazard;
import com.storeql.inventory.domain.Recall.Kind;
import com.storeql.inventory.domain.Recall.Remedy;
import com.storeql.inventory.domain.Recall.Source;
import com.storeql.inventory.dto.RecallDtos.CloseRequest;
import com.storeql.inventory.dto.RecallDtos.OpenRecallRequest;
import com.storeql.inventory.dto.RecallDtos.ReasonRequest;
import com.storeql.inventory.dto.RecallDtos.RecallResponse;
import com.storeql.inventory.dto.RecallDtos.ScopeLineRequest;
import com.storeql.inventory.mapper.RecallMappers;
import com.storeql.inventory.service.RecallService;
import com.storeql.inventory.service.RecallService.OpenRecall;
import com.storeql.inventory.service.RecallService.ScopeLine;
import com.storeql.web.ApiResponse;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Opening, closing and cancelling recalls.
 *
 * <p>Under {@code /admin/recalls} rather than {@code /admin/inventory/} on purpose: taking a
 * product off sale across every store, or putting it back, is a manager's decision, and the
 * authorisation filter gates every {@code /admin/} path to management outside the warehouse
 * subtree. Gated by path so a method added here later cannot be left open by forgetting a role
 * check (SJ-D10, SJ-D19).
 */
@Path("/admin/recalls")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Recall Management")
public class RecallSetupResource {

  @Inject RecallService service;
  @Inject TenantContext ctx;

  /**
   * Opens a withdrawal or recall.
   *
   * <p>Every batch in scope, at every store, is taken off sale in the same transaction. A batch
   * whose lot or date is not known is held too, as possibly affected. Stock that arrives later
   * under an open recall is held as it arrives. The reference is unique, so a retried open is
   * refused rather than opening a second recall.
   *
   * @param req the request body
   * @return opened ({@code 201})
   * @throws com.storeql.web.ApiException {@code 409} a recall with that reference exists
   */
  @Operation(
      summary = "Open a withdrawal or recall",
      description =
          "Every batch in scope, at every store, is taken off sale in the same transaction."
              + " A batch whose lot or date is not known is held too, as possibly affected. Stock"
              + " that arrives later under an open recall is held as it arrives. The reference is"
              + " unique, so a retried open is refused rather than opening a second recall. A"
              + " RECALL also finds every sale that drew on the packs in scope and announces each"
              + " order to order-svc, which tells the buyer and offers the remedies named here.")
  @APIResponse(responseCode = "201", description = "Opened")
  @APIResponse(
      responseCode = "400",
      description =
          "A RECALL with no remedy or contact; where GPSR binds, fewer than two remedies with no"
              + " reason, or a notice that plays the risk down")
  @APIResponse(responseCode = "409", description = "A recall with that reference exists")
  @POST
  public Response open(OpenRecallRequest req) {
    Validations.validate(req);
    var detail =
        service.open(
            new OpenRecall(
                ctx.requireTenantId(),
                ctx.requireUserId(),
                req.reference(),
                Kind.valueOf(req.kind()),
                Hazard.valueOf(req.hazard()),
                req.reason(),
                req.customerNotice(),
                Source.valueOf(req.source()),
                req.sourceReference(),
                req.items().stream().map(RecallSetupResource::toScopeLine).toList(),
                remedies(req.remedies()),
                req.singleRemedyReason(),
                req.contactPhone(),
                req.contactUrl(),
                optionalDate(req.soldFrom(), "soldFrom")));
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(RecallMappers.toRecall(detail), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  /**
   * Closes a recall.
   *
   * <p>Refused while any store still holds stock the recall took off sale with no final
   * disposition; the error names those stores.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 409} stores outstanding, or not open
   */
  @Operation(
      summary = "Close a recall",
      description =
          "Refused while any store still holds stock the recall took off sale with no final"
              + " disposition; the error names those stores.")
  @APIResponse(responseCode = "409", description = "Stores outstanding, or not open")
  @POST
  @Path("/{id}/close")
  public ApiResponse<RecallResponse> close(@PathParam("id") String id, CloseRequest req) {
    if (req != null) {
      Validations.validate(req);
    }
    var detail =
        service.close(
            ctx.requireTenantId(),
            ctx.requireUserId(),
            Parsing.uuid(id, "id"),
            req == null ? null : req.notes());
    return ApiResponse.ok(RecallMappers.toRecall(detail), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * Cancels a recall opened in error.
   *
   * <p>Puts its stock back on sale. Refused once any store has returned or destroyed stock under
   * it.
   *
   * @param id the id (path parameter)
   * @param req the request body
   * @throws com.storeql.web.ApiException {@code 409} stock already disposed of, or not open
   */
  @Operation(
      summary = "Cancel a recall opened in error",
      description =
          "Puts its stock back on sale. Refused once any store has returned or destroyed stock"
              + " under it.")
  @APIResponse(responseCode = "409", description = "Stock already disposed of, or not open")
  @POST
  @Path("/{id}/cancel")
  public ApiResponse<RecallResponse> cancel(@PathParam("id") String id, ReasonRequest req) {
    Validations.validate(req);
    var detail =
        service.cancel(
            ctx.requireTenantId(), ctx.requireUserId(), Parsing.uuid(id, "id"), req.reason());
    return ApiResponse.ok(RecallMappers.toRecall(detail), ApiResponse.Meta.of(ctx.requestId()));
  }

  private static ScopeLine toScopeLine(ScopeLineRequest line) {
    return new ScopeLine(
        Parsing.uuid(line.variantId(), "variantId"),
        line.batchNo(),
        optionalDate(line.expiryFrom(), "expiryFrom"),
        optionalDate(line.expiryTo(), "expiryTo"));
  }

  private static Set<Remedy> remedies(List<String> names) {
    return names == null
        ? Set.of()
        : names.stream().map(Remedy::valueOf).collect(Collectors.toUnmodifiableSet());
  }

  private static LocalDate optionalDate(String value, String field) {
    return value == null || value.isBlank() ? null : Parsing.date(value, field);
  }
}
