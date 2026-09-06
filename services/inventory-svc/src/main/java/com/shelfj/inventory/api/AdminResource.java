package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AdjustRequest;
import com.shelfj.inventory.dto.Dtos.BatchReceiveRequest;
import com.shelfj.inventory.dto.Dtos.BatchReceiveResult;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.LevelSummaryResponse;
import com.shelfj.inventory.dto.Dtos.MaterialStatusRequest;
import com.shelfj.inventory.dto.Dtos.MovementResponse;
import com.shelfj.inventory.dto.Dtos.PurgeMovementsRequest;
import com.shelfj.inventory.dto.Dtos.PurgeResult;
import com.shelfj.inventory.dto.Dtos.ReceiveRequest;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.Cursor;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
 * Admin inventory ops: receive (manual), adjust, levels, batches, movements, purge. This is the
 * core "stock movement" surface — everything else (thresholds, planning, serials, demand history,
 * transfers, move orders, ABC analysis, safety stock, lot genealogy, cycle counting, physical
 * inventory, costing, kanban, ROP/EOQ, reference data, planning config, picking rules) was
 * extracted to its own {@code *Resource} class in this package (F2 audit finding: split by
 * sub-domain path group). All share the same {@code @Path("/admin/inventory")} class-level path;
 * JAX-RS routes by the combined class+method path, so this is safe as long as no two classes
 * declare the same method-level {@code @Path} (they don't — each endpoint moved to exactly one
 * class, verbatim).
 */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Stock Operations")
public class AdminResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  // ── receive ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "Receive stock manually into a new batch",
      description =
          "Creates a new batch/lot for a variant at a store and records the StockReceived event."
              + " Supports Idempotency-Key so a retried receipt does not double-count stock.")
  @APIResponse(responseCode = "201", description = "Batch received")
  @POST
  @Path("/receive")
  public Response receive(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      ReceiveRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    LocalDate expiry =
        req.expiryDate() == null || req.expiryDate().isBlank() ? null : parseDate(req.expiryDate());
    UUID zoneId =
        req.zoneId() == null || req.zoneId().isBlank() ? null : uuid(req.zoneId(), "zoneId");
    var batch =
        service.receive(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.qty(),
            req.batchNo(),
            req.costPrice(),
            expiry,
            "MANUAL",
            null,
            zoneId,
            idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toBatch(batch)))
        .build();
  }

  @Operation(
      summary = "Receive multiple items in one call",
      description =
          "Best-effort bulk receive: each line is processed independently, and any per-line"
              + " failure is collected in the result instead of aborting the whole batch.")
  @POST
  @Path("/receive/batch")
  public ApiResponse<BatchReceiveResult> receiveBatch(BatchReceiveRequest req) {
    if (req == null || req.items() == null || req.items().isEmpty()) {
      return ApiResponse.ok(new BatchReceiveResult(0, List.of()));
    }
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    int received = 0;
    var errors = new java.util.ArrayList<String>();
    for (var item : req.items()) {
      try {
        service.receive(
            tenantId,
            uuid(item.storeId(), "storeId"),
            uuid(item.variantId(), "variantId"),
            item.qty(),
            null,
            null,
            null,
            "MANUAL",
            null,
            null,
            null);
        received++;
      } catch (Exception e) {
        errors.add(item.variantId() + ": " + e.getMessage());
      }
    }
    return ApiResponse.ok(new BatchReceiveResult(received, errors));
  }

  // ── adjust ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "Adjust stock by a signed delta",
      description =
          "Manual correction (e.g. stock-take variance, damage write-off) recorded as a"
              + " StockAdjusted movement. Supports Idempotency-Key.")
  @POST
  @Path("/adjust")
  public ApiResponse<String> adjust(
      @jakarta.ws.rs.HeaderParam(com.shelfj.web.HttpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
      AdjustRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    service.adjust(
        tenantId,
        uuid(req.storeId(), "storeId"),
        uuid(req.variantId(), "variantId"),
        req.delta(),
        req.reason(),
        req.reasonCode(),
        ctx.userId(),
        idempotencyKey);
    return ApiResponse.ok("adjusted");
  }

  // ── levels ───────────────────────────────────────────────────────────────

  @Operation(
      summary = "List stock levels",
      description =
          "Cursor-paginated on-hand/reserved/available quantities per variant, optionally"
              + " filtered by store.")
  @GET
  @Path("/levels")
  public ApiResponse<List<LevelResponse>> levels(
      @QueryParam("store") String store,
      @QueryParam("after") String after,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int clamped = Cursor.clampLimit(limit);
    var page = service.levelsPage(tenantId, storeId, after, clamped);
    List<LevelResponse> items = page.levels().stream().map(Mappers::toLevel).toList();
    return ApiResponse.ok(items, new ApiResponse.Meta(ctx.requestId(), page.nextCursor()));
  }

  /** Aggregate KPI counts (total SKUs + low-stock) without paging the full levels list. */
  @Operation(
      summary = "Get stock level KPI summary",
      description = "Aggregate SKU count and low-stock count without paging the full levels list.")
  @GET
  @Path("/levels/summary")
  public ApiResponse<LevelSummaryResponse> levelsSummary(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    return ApiResponse.ok(
        Mappers.toLevelSummary(service.levelsSummary(tenantId, storeId)),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── batches ──────────────────────────────────────────────────────────────

  @Operation(
      summary = "List batches",
      description = "Filterable by store, variant, and material status.")
  @GET
  @Path("/batches")
  public ApiResponse<List<BatchResponse>> listBatches(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("material_status") String materialStatus,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    String ms = materialStatus == null || materialStatus.isBlank() ? null : materialStatus;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listBatches(tenantId, storeId, variantId, ms, limit).stream()
            .map(Mappers::toBatch)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @Operation(summary = "Get a batch by id")
  @APIResponse(responseCode = "404", description = "No such batch")
  @GET
  @Path("/batches/{id}")
  public ApiResponse<BatchResponse> getBatch(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toBatch(service.getBatch(ctx.requireTenantId(), id)));
  }

  @Operation(
      summary = "Update a batch's material status",
      description = "Sets a hold/release-style material status (e.g. QUARANTINE) with a reason.")
  @APIResponse(responseCode = "404", description = "No such batch")
  @PUT
  @Path("/batches/{id}/material-status")
  public ApiResponse<BatchResponse> updateMaterialStatus(
      @PathParam("id") UUID id, MaterialStatusRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var batch = service.updateMaterialStatus(tenantId, id, req.materialStatus(), req.reason());
    return ApiResponse.ok(Mappers.toBatch(batch));
  }

  // ── movements ────────────────────────────────────────────────────────────

  @Operation(
      summary = "List stock movements",
      description = "Append-only movement ledger, filterable by store, variant, and movement type.")
  @GET
  @Path("/movements")
  public ApiResponse<List<MovementResponse>> listMovements(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("type") String type,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listMovements(tenantId, storeId, variantId, type, limit).stream()
            .map(Mappers::toMovement)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── purge movements (Gap #30) ───────────────────────────────────────────

  @Operation(
      summary = "Purge old stock movements",
      description =
          "Permanently deletes movement history older than the given instant (Gap #30 — used for"
              + " data retention housekeeping, not exposed to regular admin users).")
  @POST
  @Path("/movements/purge")
  public ApiResponse<PurgeResult> purgeMovements(PurgeMovementsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    java.time.Instant before = java.time.Instant.parse(req.before());
    int purged = service.purgeMovementsBefore(tenantId, before);
    return ApiResponse.ok(new PurgeResult(purged));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }

  private static LocalDate parseDate(String s) {
    return com.shelfj.web.Parsing.date(s, "expiryDate");
  }
}
