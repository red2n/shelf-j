package com.storeql.tenant.api;

import com.storeql.tenant.dto.Dtos.CreateIncidentRequest;
import com.storeql.tenant.dto.Dtos.IncidentResponse;
import com.storeql.tenant.dto.Dtos.IncidentSummaryResponse;
import com.storeql.tenant.dto.Dtos.IssueNoticesRequest;
import com.storeql.tenant.dto.Dtos.NoticesIssuedResponse;
import com.storeql.tenant.dto.Dtos.RecordIncidentEventRequest;
import com.storeql.tenant.mapper.Mappers;
import com.storeql.tenant.service.SecurityIncidentService;
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
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The platform's security incident register (21.15). PLATFORM_ADMIN only on every method: an
 * incident is the platform's to report, and its details are not for any one business.
 */
@Path("/platform/security-incidents")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Security incidents")
public class SecurityIncidentResource {

  @Inject SecurityIncidentService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "Open a security incident",
      description =
          "EXPLOITED_VULNERABILITY, SEVERE_INCIDENT or PERSONAL_DATA_BREACH, from when the platform"
              + " became aware (awareAt), for the businesses named in tenantIds or every business"
              + " when none are named. The statutory stages and their clocks come with it.")
  @APIResponse(responseCode = "201", description = "Opened, with its stages")
  @APIResponse(
      responseCode = "400",
      description = "Unknown kind, bad text, future awareness, unknown tenant")
  @APIResponse(responseCode = "403", description = "Not a PLATFORM_ADMIN")
  @POST
  public Response open(CreateIncidentRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toIncident(service.open(req, ctx.requireUserId()))))
        .build();
  }

  @Operation(
      summary = "List security incidents",
      description = "Newest first, with the next deadline and whether any stage is overdue.")
  @APIResponse(responseCode = "400", description = "status is not OPEN or CLOSED")
  @APIResponse(responseCode = "403", description = "Not a PLATFORM_ADMIN")
  @GET
  public ApiResponse<List<IncidentSummaryResponse>> list(
      @QueryParam("status") String status, @QueryParam("limit") Integer limit) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(
        service.list(status, limit).stream().map(Mappers::toIncidentSummary).toList());
  }

  @Operation(summary = "A security incident with its stages, timeline and notices")
  @APIResponse(responseCode = "404", description = "No such incident")
  @GET
  @Path("/{id}")
  public ApiResponse<IncidentResponse> get(@PathParam("id") UUID id) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    return ApiResponse.ok(Mappers.toIncident(service.sheet(id)));
  }

  @Operation(
      summary = "Record a report, a measure, a note or the close",
      description =
          "EARLY_WARNING_SENT, NOTIFICATION_SENT, MITIGATION_AVAILABLE, FINAL_REPORT_SENT, NOTE or"
              + " CLOSED, with the authority's reference. Each stage once; the final report only"
              + " after the notification (and, for a vulnerability, a corrective measure); closed"
              + " only when every stage is done. Append-only.")
  @APIResponse(responseCode = "201", description = "Recorded; the incident as it now stands")
  @APIResponse(responseCode = "400", description = "Not a stage of this incident, or bad input")
  @APIResponse(
      responseCode = "409",
      description = "Already recorded, too early, outstanding stages, or closed")
  @POST
  @Path("/{id}/events")
  public Response record(@PathParam("id") UUID id, RecordIncidentEventRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toIncident(service.record(id, req, ctx.requireUserId()))))
        .build();
  }

  @Operation(
      summary = "Tell the businesses affected",
      description =
          "Issues a notice to each business the incident affects that has not had one, which its"
              + " owner or manager acknowledges. Records that businesses were told the first time.")
  @APIResponse(
      responseCode = "200",
      description = "How many were new, how many exist, how many acknowledged")
  @APIResponse(responseCode = "409", description = "The incident is closed")
  @POST
  @Path("/{id}/notices")
  public ApiResponse<NoticesIssuedResponse> notices(
      @PathParam("id") UUID id, IssueNoticesRequest req) {
    ctx.requireAnyRole("PLATFORM_ADMIN");
    Validations.validate(req);
    return ApiResponse.ok(
        Mappers.toNoticesIssued(service.issueNotices(id, req, ctx.requireUserId())));
  }
}
