package com.shelfj.service;

import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

/**
 * {@code /admin/tenant-data}: what this service holds for the caller's business, its rows page by
 * page, and the import of those pages into a fresh business (21.14, EU Data Act ch.VI). A subclass
 * gives it its path; the owner alone may take the business's data out or put it in, because the
 * export carries every staff member's and customer's records.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public abstract class TenantDataResource {

  static final int DEFAULT_PAGE = 500;

  @Inject protected TenantContext ctx;
  @Inject protected TenantDataRepository data;

  /**
   * The manifest: every table exported with its columns, key, row count and checksum, and what is
   * left out with the reason.
   */
  @GET
  public ApiResponse<TenantDataRepository.Manifest> manifest() {
    return ApiResponse.ok(data.manifest(owner()), ApiResponse.Meta.of(ctx.requestId()));
  }

  /**
   * One page of a table, in key order; {@code meta.nextCursor} fetches the next.
   *
   * @param limit 1 to {@link TenantDataRepository#MAX_PAGE}, {@value #DEFAULT_PAGE} by default
   */
  @GET
  @Path("/tables/{table}")
  public ApiResponse<TenantDataRepository.Page> page(
      @PathParam("table") String table,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = owner();
    TenantDataRepository.Page page = data.page(tenantId, table, after, clamp(limit));
    return ApiResponse.ok(page, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /**
   * Loads a page, as {@code {"rows": [...]}} exactly as it was served, into the caller's business.
   */
  @POST
  @Path("/tables/{table}")
  public ApiResponse<TenantDataRepository.Imported> importPage(
      @PathParam("table") String table, JsonObject body) {
    UUID tenantId = owner();
    JsonValue rows = body == null ? null : body.get("rows");
    if (!(rows instanceof JsonArray array)) {
      throw ApiException.badRequest(
          "TENANT_DATA_IMPORT_INVALID", "send {\"rows\": [...]}, a page as it was served");
    }
    return ApiResponse.ok(
        data.importPage(tenantId, table, array), ApiResponse.Meta.of(ctx.requestId()));
  }

  private UUID owner() {
    ctx.requireAnyRole("OWNER");
    return ctx.requireTenantId();
  }

  static int clamp(Integer limit) {
    if (limit == null || limit < 1) return DEFAULT_PAGE;
    return Math.min(limit, TenantDataRepository.MAX_PAGE);
  }
}
