package com.shelfj.order.api;

import com.shelfj.order.dto.Dtos.RecordAgeCheckRequest;
import com.shelfj.order.mapper.Mappers;
import com.shelfj.order.service.OrderService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The due-diligence record behind age-restricted sales: the till writes one entry per check, and a
 * manager reads the register a licensing officer asks for.
 *
 * <p>Two paths on purpose. {@code POST /pos/age-checks} is a till write and needs any staff role
 * (the cashier making the check). {@code GET /admin/pos/age-checks} is the register and is
 * management-only, like every other {@code /admin/} read that is not a shop-floor surface — a
 * cashier records checks, a manager answers for them.
 */
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Age checks")
@Path("/pos/age-checks")
public class AgeCheckResource {

  @Inject OrderService svc;
  @Inject TenantContext ctx;

  /**
   * Records one age check.
   *
   * @param req the check as the till made it
   * @return {@code 201} with the record
   * @throws ApiException {@code 400} for a refusal with no reason, a pass with one, or a value
   *     outside the vocabulary; {@code 403} for a store the cashier is not assigned to
   */
  @Operation(
      summary = "Record an age check",
      description =
          "One entry per check the till made, pass or refusal, with the rule that applied at the"
              + " time. Append-only: a wrong entry is answered by another entry. A refusal must say"
              + " why; that is the record a licensing officer asks for first.")
  @APIResponse(responseCode = "201", description = "Recorded")
  @APIResponse(responseCode = "400", description = "Validation failed")
  @APIResponse(responseCode = "403", description = "Not this cashier's store")
  @POST
  public Response record(RecordAgeCheckRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER");
    Validations.validate(req);
    var saved = svc.recordAgeCheck(req, ctx);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toDto(saved), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }
}
