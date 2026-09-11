package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.Recall.Hazard;
import com.shelfj.inventory.domain.Recall.Kind;
import com.shelfj.inventory.domain.Recall.Source;
import com.shelfj.inventory.dto.RecallDtos.CloseRequest;
import com.shelfj.inventory.dto.RecallDtos.OpenRecallRequest;
import com.shelfj.inventory.dto.RecallDtos.ReasonRequest;
import com.shelfj.inventory.dto.RecallDtos.RecallResponse;
import com.shelfj.inventory.dto.RecallDtos.ScopeLineRequest;
import com.shelfj.inventory.mapper.RecallMappers;
import com.shelfj.inventory.service.RecallService;
import com.shelfj.inventory.service.RecallService.OpenRecall;
import com.shelfj.inventory.service.RecallService.ScopeLine;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
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

  @Operation(
      summary = "Open a withdrawal or recall",
      description =
          "Every batch in scope, at every store, is taken off sale in the same transaction."
              + " A batch whose lot or date is not known is held too, as possibly affected. Stock"
              + " that arrives later under an open recall is held as it arrives. The reference is"
              + " unique, so a retried open is refused rather than opening a second recall.")
  @APIResponse(responseCode = "201", description = "Opened")
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
                req.items().stream().map(RecallSetupResource::toScopeLine).toList()));
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(RecallMappers.toRecall(detail), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

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

  private static LocalDate optionalDate(String value, String field) {
    return value == null || value.isBlank() ? null : Parsing.date(value, field);
  }
}
