package com.storeql.order.api;

import com.storeql.ids.Ids;
import com.storeql.order.dto.EReportingDtos;
import com.storeql.order.mapper.EReportingMappers;
import com.storeql.order.service.EReportingService;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * {@code /admin/ereporting}: the transactions an invoice does not cover (18.9, second limb).
 *
 * <p>France's reform has two limbs, and issuing every invoice correctly satisfies only one. What a
 * shop sells to the public, and what it sells abroad, is reported to the administration through the
 * same platform — three times a month on the ordinary monthly VAT regime.
 *
 * <p>The calendar is tenant-svc's: it derives the periods and holds what was filed, and its entry
 * for these two returns points here. This service is told a period and reports it.
 */
@Path("/admin/ereporting")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "E-reporting")
public class EReportingResource {

  @Inject EReportingService svc;
  @Inject TenantContext ctx;

  @Operation(
      summary = "What a period would report",
      description =
          "Before anything is sent: the days with their operation counts and money by rate, the"
              + " cross-border sales one by one, and every currency the period took money in — a"
              + " report is per currency, so a business that traded in two owes two, and listing them"
              + " is how nothing is left out silently. A period with nothing in it says so and is"
              + " still reportable: silence is indistinguishable from a platform that stopped"
              + " working.")
  @APIResponse(
      responseCode = "400",
      description = "Unknown return, or a period that cannot be reported")
  @GET
  @Path("/preview")
  public ApiResponse<EReportingDtos.PreviewResponse> preview(
      @QueryParam("return") String returnCode,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("currency") String currency) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        EReportingMappers.toDto(
            svc.preview(
                ctx.requireTenantId(), returnCode, day(from, "from"), day(to, "to"), currency)));
  }

  @Operation(
      summary = "Report a period",
      description =
          "Built from this service's own sales, kept byte for byte, and deposited with the platform"
              + " the business sends on. Only a network that carries reports takes one — France's"
              + " platform does, a Peppol access point does not — because a network that silently"
              + " accepted a report would leave a business believing it had reported. The answer"
              + " carries the payload's SHA-256: put that on the filing you record against the"
              + " calendar, and the filing and the bytes can be tied together afterwards.")
  @APIResponse(
      responseCode = "201",
      description = "Reported; read the status for what the network said")
  @APIResponse(
      responseCode = "409",
      description =
          "The duty does not bind, no network is set, the network takes no reports, or the period"
              + " already has a submission that stands")
  @POST
  @Path("/submissions")
  public Response submit(EReportingDtos.SubmitRequest req) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    Validations.validate(req);
    var s =
        svc.submit(
            ctx.requireTenantId(),
            req.returnCode(),
            day(req.periodStart(), "periodStart"),
            day(req.periodEnd(), "periodEnd"),
            req.currency(),
            req.corrects() == null || req.corrects().isBlank() ? null : uuid(req.corrects()),
            ctx.requireUserId());
    return Response.status(201).entity(ApiResponse.ok(EReportingMappers.toDto(s))).build();
  }

  @Operation(
      summary = "What has been reported",
      description = "Newest period first, corrections and all: a superseded submission stays.")
  @GET
  @Path("/submissions")
  public ApiResponse<List<EReportingDtos.SubmissionResponse>> submissions(
      @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(
        EReportingMappers.submissions(
            svc.submissions(ctx.requireTenantId(), limit == null ? 50 : limit)));
  }

  @Operation(summary = "One submission")
  @APIResponse(responseCode = "404", description = "No such submission")
  @GET
  @Path("/submissions/{id}")
  public ApiResponse<EReportingDtos.SubmissionResponse> submission(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    return ApiResponse.ok(EReportingMappers.toDto(svc.submission(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "The document as it was transmitted",
      description =
          "Byte for byte, so it can be checked against the digest on the filing. XML, downloaded.")
  @APIResponse(responseCode = "404", description = "No such submission")
  @GET
  @Path("/submissions/{id}/document")
  @Produces(MediaType.APPLICATION_XML)
  public Response document(@PathParam("id") UUID id) {
    ctx.requireAnyRole("OWNER", "MANAGER");
    var s = svc.submission(ctx.requireTenantId(), id);
    return Response.ok(s.payload())
        .header(
            "Content-Disposition",
            "attachment; filename=\"" + s.returnCode() + "-" + s.periodStart() + ".xml\"")
        .build();
  }

  private static LocalDate day(String value, String field) {
    if (value == null || value.isBlank()) {
      throw ApiException.badRequest("EREPORTING_DATE_REQUIRED", field + " is required");
    }
    try {
      return LocalDate.parse(value.strip());
    } catch (RuntimeException e) {
      throw new ApiException(
          400, "EREPORTING_DATE_INVALID", field + " is written as 2026-09-01", List.of(), e);
    }
  }

  private static UUID uuid(String value) {
    try {
      return Ids.parse(value.strip());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "EREPORTING_ID_INVALID", "corrects is not an id", List.of(), e);
    }
  }
}
