package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AggregateRequest;
import com.shelfj.inventory.dto.Dtos.AggregateResult;
import com.shelfj.inventory.dto.Dtos.DemandBucketResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Demand history: SALE-movement aggregation into buckets. Extracted from AdminResource. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DemandHistoryResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  @POST
  @Path("/demand/aggregate")
  public ApiResponse<AggregateResult> aggregateDemand(AggregateRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId =
        req != null && req.storeId() != null && !req.storeId().isBlank()
            ? uuid(req.storeId(), "storeId")
            : null;
    String bucketType = req != null && req.bucketType() != null ? req.bucketType() : "WEEK";
    LocalDate since = null;
    if (req != null && req.since() != null && !req.since().isBlank()) {
      since = parseDate(req.since());
    }
    int bucketsUpserted = service.aggregateDemand(tenantId, storeId, bucketType, since);
    return ApiResponse.ok(new AggregateResult(bucketsUpserted, bucketType));
  }

  @GET
  @Path("/demand/history")
  public ApiResponse<List<DemandBucketResponse>> listDemandHistory(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("bucket_type") String bucketType,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    String bt = bucketType == null || bucketType.isBlank() ? null : bucketType;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listDemandHistory(tenantId, storeId, variantId, bt, limit).stream()
            .map(Mappers::toDemandBucket)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }

  private static LocalDate parseDate(String s) {
    return com.shelfj.web.Parsing.date(s, "expiryDate");
  }
}
