package com.shelfj.payment.api;

import com.shelfj.payment.dto.Dtos.TenderMixRowResponse;
import com.shelfj.payment.mapper.Mappers;
import com.shelfj.payment.service.TenderMixService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Parsing;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The tender-mix report — one of the four the readiness review still listed as missing.
 *
 * <p>Lives in payment-svc because the tenders do. reporting-svc's {@code sales_facts} holds one
 * gross amount per order and no tender at all, so a split payment of £20 cash and £30 card is a
 * single £50 row there — the exact detail this report exists to show.
 *
 * <p>Under {@code /admin/} so the authorisation filter gates it by path rather than by a role check
 * written into the method: how a business is paid is management information, and the by-path form
 * is what stops a method added to this class later from shipping open (SJ-D10, SJ-D11).
 */
@Path("/admin/reports")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Tender Mix")
public class TenderMixResource {

  @Inject TenderMixService service;
  @Inject TenantContext ctx;

  @Operation(
      summary = "How the take split across payment methods",
      description =
          "Captured and refunded amounts per tender method, with each method's share of the net."
              + " Refunds are subtracted within their own method rather than netted globally: a"
              + " card sale refunded to store credit is not a zero-card day, and the split is what"
              + " a merchant statement reconciles against. Failed tenders are counted alongside,"
              + " because a method whose failures are climbing against healthy volume is a"
              + " terminal problem that no sales report would show. from and to are optional and"
              + " take a full ISO-8601 instant (2026-01-31T00:00:00Z), not a bare date.")
  @APIResponse(responseCode = "200", description = "One row per method, largest net first")
  @APIResponse(
      responseCode = "400",
      description = "Unparseable timestamp, or from is not before to")
  @GET
  @Path("/tender-mix")
  public Response tenderMix(@QueryParam("from") String from, @QueryParam("to") String to) {
    List<TenderMixRowResponse> rows =
        service
            .tenderMix(
                ctx.requireTenantId(),
                Parsing.optionalInstant(from, "from"),
                Parsing.optionalInstant(to, "to"))
            .stream()
            .map(Mappers::toTenderMixRow)
            .toList();
    return Response.ok(ApiResponse.ok(rows, ApiResponse.Meta.of(ctx.requestId()))).build();
  }
}
