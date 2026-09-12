package com.shelfj.purchase.api;

import com.shelfj.purchase.mapper.Mappers;
import com.shelfj.purchase.service.PurchaseService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** FRS 102 / UK GAAP nominal ledger — read-only view of double-entry journal. */
@RequestScoped
@Path("/nominal-ledger")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Nominal Ledger")
public class NominalLedgerResource {

  @Inject PurchaseService svc;
  @Inject TenantContext ctx;

  /**
   * Read-only double-entry journal view, cursor-paginated.
   *
   * @param code restrict to one nominal code, or {@code null} for all
   * @param from inclusive start date as {@code yyyy-MM-dd}, or {@code null} for no lower bound
   * @param to inclusive end date as {@code yyyy-MM-dd}, or {@code null} for no upper bound
   * @param after cursor from the previous page's {@code meta.nextCursor}, or {@code null} to start
   * @param limit page size, 1..100
   * @return the page of ledger entries plus a {@code nextCursor}
   * @throws com.shelfj.web.ApiException {@code 400} when the cursor is malformed or a date is not
   *     {@code yyyy-MM-dd}
   */
  @Operation(
      summary = "List nominal ledger entries",
      description =
          "Read-only double-entry journal view, optionally filtered by nominal code and date range"
              + " (?code=&from=&to=), where from and to are yyyy-MM-dd. Cursor-paginated:"
              + " ?after=<meta.nextCursor>&limit=1-100.")
  @APIResponse(
      responseCode = "400",
      description = "Malformed pagination cursor, or from/to not in yyyy-MM-dd form")
  @GET
  public Response list(
      @QueryParam("code") String code,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    var page = svc.getNominalLedger(ctx, code, from, to, after, Cursor.clampLimit(limit));
    var entries = page.items().stream().map(Mappers::toDto).toList();
    return Response.ok(
            ApiResponse.ok(entries, new ApiResponse.Meta(ctx.requestId(), page.nextCursor())))
        .build();
  }
}
