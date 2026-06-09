package com.shelfj.inventory.api;

import com.shelfj.inventory.dto.Dtos.AdjustRequest;
import com.shelfj.inventory.dto.Dtos.AggregateRequest;
import com.shelfj.inventory.dto.Dtos.AggregateResult;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.DemandBucketResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.MaterialStatusRequest;
import com.shelfj.inventory.dto.Dtos.MovementResponse;
import com.shelfj.inventory.dto.Dtos.ReceiveRequest;
import com.shelfj.inventory.dto.Dtos.RegisterSerialsRequest;
import com.shelfj.inventory.dto.Dtos.ResolveSuggestionRequest;
import com.shelfj.inventory.dto.Dtos.SerialMovementResponse;
import com.shelfj.inventory.dto.Dtos.SerialNumberResponse;
import com.shelfj.inventory.dto.Dtos.SerialStatusRequest;
import com.shelfj.inventory.dto.Dtos.SuggestionResponse;
import com.shelfj.inventory.dto.Dtos.ThresholdRequest;
import com.shelfj.inventory.dto.Dtos.ThresholdResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
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

/** Admin inventory ops: receive (manual), adjust, levels, batches, movements, thresholds. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

  @Inject InventoryService service;
  @Inject TenantContext ctx;

  // ── receive ──────────────────────────────────────────────────────────────

  @POST
  @Path("/receive")
  public Response receive(ReceiveRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    LocalDate expiry =
        req.expiryDate() == null || req.expiryDate().isBlank() ? null : parseDate(req.expiryDate());
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
            null);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toBatch(batch)))
        .build();
  }

  // ── adjust ───────────────────────────────────────────────────────────────

  @POST
  @Path("/adjust")
  public ApiResponse<String> adjust(AdjustRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    service.adjust(
        tenantId,
        uuid(req.storeId(), "storeId"),
        uuid(req.variantId(), "variantId"),
        req.delta(),
        req.reason());
    return ApiResponse.ok("adjusted");
  }

  // ── levels ───────────────────────────────────────────────────────────────

  @GET
  @Path("/levels")
  public ApiResponse<List<LevelResponse>> levels(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    List<LevelResponse> items =
        service.levels(tenantId, storeId).stream().map(Mappers::toLevel).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── batches ──────────────────────────────────────────────────────────────

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

  @GET
  @Path("/batches/{id}")
  public ApiResponse<BatchResponse> getBatch(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toBatch(service.getBatch(ctx.requireTenantId(), id)));
  }

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

  // ── thresholds ───────────────────────────────────────────────────────────

  @POST
  @Path("/thresholds")
  public Response setThreshold(ThresholdRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var t =
        service.setThreshold(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.threshold(),
            req.maxQty());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toThreshold(t)))
        .build();
  }

  @GET
  @Path("/thresholds")
  public ApiResponse<List<ThresholdResponse>> listThresholds(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    var items =
        service.listThresholds(tenantId, storeId).stream().map(Mappers::toThreshold).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── planning ─────────────────────────────────────────────────────────────

  @POST
  @Path("/planning/run")
  public ApiResponse<List<SuggestionResponse>> runMinMaxPlan(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    var items =
        service.runMinMaxPlan(tenantId, storeId).stream().map(Mappers::toSuggestion).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/planning/suggestions")
  public ApiResponse<List<SuggestionResponse>> listSuggestions(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    String st = status == null || status.isBlank() ? null : status;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listSuggestions(tenantId, storeId, st, limit).stream()
            .map(Mappers::toSuggestion)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @PUT
  @Path("/planning/suggestions/{id}/status")
  public ApiResponse<SuggestionResponse> resolveSuggestion(
      @PathParam("id") UUID id, ResolveSuggestionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toSuggestion(service.resolveSuggestion(tenantId, id, req.status())));
  }

  // ── serial number control ────────────────────────────────────────────────

  @POST
  @Path("/serials/register")
  public Response registerSerials(RegisterSerialsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var items =
        service
            .registerSerials(
                tenantId,
                uuid(req.storeId(), "storeId"),
                uuid(req.variantId(), "variantId"),
                uuid(req.batchId(), "batchId"),
                req.serials(),
                req.autoQty(),
                req.prefix())
            .stream()
            .map(Mappers::toSerial)
            .toList();
    return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(items)).build();
  }

  @GET
  @Path("/serials")
  public ApiResponse<List<SerialNumberResponse>> listSerials(
      @QueryParam("store") String store,
      @QueryParam("variant") String variant,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    UUID variantId = variant == null || variant.isBlank() ? null : uuid(variant, "variant");
    String st = status == null || status.isBlank() ? null : status;
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listSerials(tenantId, storeId, variantId, st, limit).stream()
            .map(Mappers::toSerial)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/serials/lookup")
  public ApiResponse<SerialNumberResponse> lookupSerial(@QueryParam("serial_no") String serialNo) {
    if (serialNo == null || serialNo.isBlank())
      throw new ApiException(
          400, "SERIAL_NO_REQUIRED", "serial_no query param is required", List.of(), null);
    return ApiResponse.ok(
        Mappers.toSerial(service.lookupSerialByNo(ctx.requireTenantId(), serialNo)));
  }

  @GET
  @Path("/serials/{id}")
  public ApiResponse<SerialNumberResponse> getSerial(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toSerial(service.getSerial(ctx.requireTenantId(), id)));
  }

  @GET
  @Path("/serials/{id}/history")
  public ApiResponse<List<SerialMovementResponse>> serialHistory(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    service.getSerial(tenantId, id); // 404 if not found
    var items =
        service.listSerialHistory(tenantId, id).stream().map(Mappers::toSerialMovement).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @PUT
  @Path("/serials/{id}/status")
  public ApiResponse<SerialNumberResponse> updateSerialStatus(
      @PathParam("id") UUID id, SerialStatusRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toSerial(service.updateSerialStatus(tenantId, id, req.status())));
  }

  // ── demand history ───────────────────────────────────────────────────────

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

  // ── helpers ──────────────────────────────────────────────────────────────

  private static UUID uuid(String s, String field) {
    try {
      return UUID.fromString(s);
    } catch (RuntimeException e) {
      throw new ApiException(400, "INVALID_UUID", field + " must be a UUID", List.of(), e);
    }
  }

  private static LocalDate parseDate(String s) {
    try {
      return LocalDate.parse(s);
    } catch (RuntimeException e) {
      throw new ApiException(400, "INVALID_DATE", "expiryDate must be yyyy-MM-dd", List.of(), e);
    }
  }
}
