package com.shelfj.purchase.api;

import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** FRS 102 / UK GAAP nominal ledger — read-only view of double-entry journal. */
@RequestScoped
@Path("/nominal-ledger")
@Produces(MediaType.APPLICATION_JSON)
public class NominalLedgerResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  @GET
  public Response list(
      @QueryParam("code") String code,
      @QueryParam("from") String from,
      @QueryParam("to") String to) {
    return Response.ok(
            ApiResponse.ok(
                svc.getNominalLedger(ctx, code, from, to).stream().map(Mappers::toDto).toList()))
        .build();
  }
}
