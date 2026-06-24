package com.shelfj.inventory.api;

import com.shelfj.inventory.domain.Domain.MoveOrderLine;
import com.shelfj.inventory.domain.Domain.TransferOrderLine;
import com.shelfj.inventory.dto.Dtos.AbcAssignmentResponse;
import com.shelfj.inventory.dto.Dtos.AccountingPeriodResponse;
import com.shelfj.inventory.dto.Dtos.AddTagRequest;
import com.shelfj.inventory.dto.Dtos.AdjustRequest;
import com.shelfj.inventory.dto.Dtos.AggregateRequest;
import com.shelfj.inventory.dto.Dtos.AggregateResult;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.ComputeRopResult;
import com.shelfj.inventory.dto.Dtos.ComputeSafetyStockRequest;
import com.shelfj.inventory.dto.Dtos.ComputeSafetyStockResult;
import com.shelfj.inventory.dto.Dtos.CostingMethodResponse;
import com.shelfj.inventory.dto.Dtos.CountTagRequest;
import com.shelfj.inventory.dto.Dtos.CreateCycleCountRequest;
import com.shelfj.inventory.dto.Dtos.CreateKanbanCardRequest;
import com.shelfj.inventory.dto.Dtos.CreateLotLinkRequest;
import com.shelfj.inventory.dto.Dtos.CreateMoveOrderRequest;
import com.shelfj.inventory.dto.Dtos.CreatePhysicalInventoryRequest;
import com.shelfj.inventory.dto.Dtos.CreatePickingRuleAssignmentRequest;
import com.shelfj.inventory.dto.Dtos.CreatePickingRuleRequest;
import com.shelfj.inventory.dto.Dtos.CreateReasonCodeRequest;
import com.shelfj.inventory.dto.Dtos.CreateSourceTypeRequest;
import com.shelfj.inventory.dto.Dtos.CreateTransferOrderRequest;
import com.shelfj.inventory.dto.Dtos.CycleCountAdjustResult;
import com.shelfj.inventory.dto.Dtos.CycleCountApproveResult;
import com.shelfj.inventory.dto.Dtos.CycleCountHeaderResponse;
import com.shelfj.inventory.dto.Dtos.CycleCountLineResponse;
import com.shelfj.inventory.dto.Dtos.DemandBucketResponse;
import com.shelfj.inventory.dto.Dtos.EnterCountRequest;
import com.shelfj.inventory.dto.Dtos.ExpiringBatchResponse;
import com.shelfj.inventory.dto.Dtos.KanbanCardResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.LotActionResponse;
import com.shelfj.inventory.dto.Dtos.LotGenealogyLinkResponse;
import com.shelfj.inventory.dto.Dtos.LotGenealogyTreeResponse;
import com.shelfj.inventory.dto.Dtos.LotMergeRequest;
import com.shelfj.inventory.dto.Dtos.LotSplitRequest;
import com.shelfj.inventory.dto.Dtos.LotUomConversionResponse;
import com.shelfj.inventory.dto.Dtos.MaterialStatusRequest;
import com.shelfj.inventory.dto.Dtos.MoveOrderResponse;
import com.shelfj.inventory.dto.Dtos.MovementResponse;
import com.shelfj.inventory.dto.Dtos.OpenPeriodRequest;
import com.shelfj.inventory.dto.Dtos.ParLevelResponse;
import com.shelfj.inventory.dto.Dtos.PhysicalInventoryResponse;
import com.shelfj.inventory.dto.Dtos.PhysicalInventoryTagResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleAssignmentResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleResolveResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleResponse;
import com.shelfj.inventory.dto.Dtos.PickingRuleZonePriorityResponse;
import com.shelfj.inventory.dto.Dtos.PurgeMovementsRequest;
import com.shelfj.inventory.dto.Dtos.PurgeResult;
import com.shelfj.inventory.dto.Dtos.ReasonCodeResponse;
import com.shelfj.inventory.dto.Dtos.ReceiveRequest;
import com.shelfj.inventory.dto.Dtos.RegisterSerialsRequest;
import com.shelfj.inventory.dto.Dtos.ResolveSuggestionRequest;
import com.shelfj.inventory.dto.Dtos.RopPlanResponse;
import com.shelfj.inventory.dto.Dtos.RunAbcRequest;
import com.shelfj.inventory.dto.Dtos.SafetyStockParamsResponse;
import com.shelfj.inventory.dto.Dtos.SerialMovementResponse;
import com.shelfj.inventory.dto.Dtos.SerialNumberResponse;
import com.shelfj.inventory.dto.Dtos.SerialStatusRequest;
import com.shelfj.inventory.dto.Dtos.SetSafetyStockRequest;
import com.shelfj.inventory.dto.Dtos.SetZonePrioritiesRequest;
import com.shelfj.inventory.dto.Dtos.SourceTypeResponse;
import com.shelfj.inventory.dto.Dtos.SuggestionResponse;
import com.shelfj.inventory.dto.Dtos.ThresholdRequest;
import com.shelfj.inventory.dto.Dtos.ThresholdResponse;
import com.shelfj.inventory.dto.Dtos.TransferOrderResponse;
import com.shelfj.inventory.dto.Dtos.TriggerKanbanRequest;
import com.shelfj.inventory.dto.Dtos.UpdateGradeRequest;
import com.shelfj.inventory.dto.Dtos.UpdateOrderModifiersRequest;
import com.shelfj.inventory.dto.Dtos.UpsertCostingMethodRequest;
import com.shelfj.inventory.dto.Dtos.UpsertLotUomConversionRequest;
import com.shelfj.inventory.dto.Dtos.UpsertParLevelRequest;
import com.shelfj.inventory.dto.Dtos.UpsertRopPlanRequest;
import com.shelfj.inventory.dto.Dtos.UpsertZoneGlMappingRequest;
import com.shelfj.inventory.dto.Dtos.ZoneGlMappingResponse;
import com.shelfj.inventory.mapper.Mappers;
import com.shelfj.inventory.service.InventoryService;
import com.shelfj.web.ApiException;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import com.shelfj.web.Validations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

  // ── transfer orders (Gap #6) ─────────────────────────────────────────────

  @POST
  @Path("/transfers")
  public Response createTransfer(CreateTransferOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = uuid(req.fromStoreId(), "fromStoreId");
    UUID toStore = uuid(req.toStoreId(), "toStoreId");
    List<TransferOrderLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new TransferOrderLine(
                        null,
                        tenantId,
                        null,
                        uuid(l.variantId(), "variantId"),
                        l.requestedQty(),
                        null,
                        null))
            .toList();
    var wl =
        service.createTransferOrder(
            tenantId, fromStore, toStore, req.transferType(), req.notes(), lines);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines())))
        .build();
  }

  @GET
  @Path("/transfers")
  public ApiResponse<List<TransferOrderResponse>> listTransfers(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listTransferOrders(tenantId, storeId, status, limit).stream()
            .map(
                o -> Mappers.toTransferOrder(o, service.getTransferOrder(tenantId, o.id()).lines()))
            .toList());
  }

  @GET
  @Path("/transfers/{id}")
  public ApiResponse<TransferOrderResponse> getTransfer(@PathParam("id") UUID id) {
    var wl = service.getTransferOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/transfers/{id}/ship")
  public ApiResponse<TransferOrderResponse> shipTransfer(@PathParam("id") UUID id) {
    var wl = service.shipTransferOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/transfers/{id}/receive")
  public ApiResponse<TransferOrderResponse> receiveTransfer(@PathParam("id") UUID id) {
    var wl = service.receiveTransferOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/transfers/{id}/cancel")
  public ApiResponse<TransferOrderResponse> cancelTransfer(@PathParam("id") UUID id) {
    var cancelled = service.cancelTransferOrder(ctx.requireTenantId(), id);
    var wl = service.getTransferOrder(ctx.requireTenantId(), cancelled.id());
    return ApiResponse.ok(Mappers.toTransferOrder(wl.order(), wl.lines()));
  }

  // ── move orders (Gap #5) ─────────────────────────────────────────────────

  @POST
  @Path("/move-orders")
  public Response createMoveOrder(CreateMoveOrderRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID fromStore = uuid(req.fromStoreId(), "fromStoreId");
    UUID toStore = uuid(req.toStoreId(), "toStoreId");
    List<MoveOrderLine> lines =
        req.lines().stream()
            .map(
                l ->
                    new MoveOrderLine(
                        null,
                        tenantId,
                        null,
                        uuid(l.variantId(), "variantId"),
                        l.requestedQty(),
                        null))
            .toList();
    var order =
        service.createMoveOrder(
            tenantId, fromStore, toStore, req.fromZone(), req.toZone(), req.notes(), lines);
    var withLines = service.getMoveOrder(tenantId, order.id());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toMoveOrder(withLines.order(), withLines.lines())))
        .build();
  }

  @GET
  @Path("/move-orders")
  public ApiResponse<List<MoveOrderResponse>> listMoveOrders(
      @QueryParam("store") String store,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    return ApiResponse.ok(
        service.listMoveOrders(tenantId, storeId, status, limit).stream()
            .map(o -> Mappers.toMoveOrder(o, service.getMoveOrder(tenantId, o.id()).lines()))
            .toList());
  }

  @GET
  @Path("/move-orders/{id}")
  public ApiResponse<MoveOrderResponse> getMoveOrder(@PathParam("id") UUID id) {
    var wl = service.getMoveOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/move-orders/{id}/pick")
  public ApiResponse<MoveOrderResponse> pickMoveOrder(@PathParam("id") UUID id) {
    var wl = service.pickMoveOrder(ctx.requireTenantId(), id);
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  @POST
  @Path("/move-orders/{id}/cancel")
  public ApiResponse<MoveOrderResponse> cancelMoveOrder(@PathParam("id") UUID id) {
    var cancelled = service.cancelMoveOrder(ctx.requireTenantId(), id);
    var wl = service.getMoveOrder(ctx.requireTenantId(), cancelled.id());
    return ApiResponse.ok(Mappers.toMoveOrder(wl.order(), wl.lines()));
  }

  // ── ABC analysis (Gap #9) ────────────────────────────────────────────────

  @POST
  @Path("/abc/compile")
  public Response runAbcCompile(RunAbcRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId =
        req != null && req.storeId() != null && !req.storeId().isBlank()
            ? uuid(req.storeId(), "storeId")
            : null;
    String criteria = req != null ? req.criteria() : null;
    var thA = req != null ? req.thresholdA() : null;
    var thAB = req != null ? req.thresholdAB() : null;
    var result = service.runAbcCompile(tenantId, storeId, criteria, thA, thAB);
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toAbcCompileRun(result.run())))
        .build();
  }

  @GET
  @Path("/abc/assignments")
  public ApiResponse<List<AbcAssignmentResponse>> listAbcAssignments(
      @QueryParam("store") String store,
      @QueryParam("class") String abcClass,
      @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listAbcAssignments(tenantId, storeId, abcClass, limit).stream()
            .map(Mappers::toAbcAssignment)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/abc/assignments/{storeId}/{variantId}")
  public ApiResponse<AbcAssignmentResponse> getAbcAssignment(
      @PathParam("storeId") UUID storeId, @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toAbcAssignment(service.getAbcAssignment(tenantId, storeId, variantId)));
  }

  // ── safety stock (Gap #8) ────────────────────────────────────────────────

  @POST
  @Path("/safety-stock")
  public Response setSafetyStock(SetSafetyStockRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var params =
        service.setSafetyStockParams(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.method(),
            req.leadTimeDays(),
            req.serviceLevelPct(),
            req.userDefinedPct());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toSafetyStockParams(params)))
        .build();
  }

  @GET
  @Path("/safety-stock")
  public ApiResponse<List<SafetyStockParamsResponse>> listSafetyStock(
      @QueryParam("store") String store, @QueryParam("limit") Integer limitParam) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
    int limit = limitParam == null || limitParam < 1 ? 20 : Math.min(limitParam, 100);
    var items =
        service.listSafetyStockParams(tenantId, storeId, limit).stream()
            .map(Mappers::toSafetyStockParams)
            .toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/safety-stock/{storeId}/{variantId}")
  public ApiResponse<SafetyStockParamsResponse> getSafetyStock(
      @PathParam("storeId") UUID storeId, @PathParam("variantId") UUID variantId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toSafetyStockParams(service.getSafetyStockParams(tenantId, storeId, variantId)));
  }

  @POST
  @Path("/safety-stock/compute")
  public ApiResponse<ComputeSafetyStockResult> computeSafetyStock(ComputeSafetyStockRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId =
        req != null && req.storeId() != null && !req.storeId().isBlank()
            ? uuid(req.storeId(), "storeId")
            : null;
    UUID variantId =
        req != null && req.variantId() != null && !req.variantId().isBlank()
            ? uuid(req.variantId(), "variantId")
            : null;
    int updated = service.computeSafetyStock(tenantId, storeId, variantId);
    return ApiResponse.ok(new ComputeSafetyStockResult(updated, "DAY"));
  }

  // ── Lot Genealogy (Gap #11) ──────────────────────────────────────────────

  @POST
  @Path("/lot-genealogy")
  public Response createLotLink(CreateLotLinkRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var link =
        service.createLotLink(
            tenantId,
            uuid(req.parentBatchId(), "parentBatchId"),
            uuid(req.childBatchId(), "childBatchId"),
            req.qty(),
            req.relationType(),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toLotLink(link), ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/lot-genealogy/batch/{batchId}/ancestors")
  public ApiResponse<LotGenealogyTreeResponse> getAncestors(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var ancestors =
        service.findAncestors(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(
        new LotGenealogyTreeResponse(batchId.toString(), ancestors, List.of()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/lot-genealogy/batch/{batchId}/descendants")
  public ApiResponse<LotGenealogyTreeResponse> getDescendants(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var descendants =
        service.findDescendants(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(
        new LotGenealogyTreeResponse(batchId.toString(), List.of(), descendants),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/lot-genealogy/batch/{batchId}/links")
  public ApiResponse<List<LotGenealogyLinkResponse>> getDirectLinks(
      @PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    var links =
        service.findDirectLinks(tenantId, batchId).stream().map(Mappers::toLotLink).toList();
    return ApiResponse.ok(links, ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── Cycle Counting (Gap #10) ─────────────────────────────────────────────

  @POST
  @Path("/cycle-counts")
  public Response createCycleCount(CreateCycleCountRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result =
        service.createCycleCount(
            tenantId,
            uuid(req.storeId(), "storeId"),
            req.name(),
            req.abcClasses() != null ? req.abcClasses() : "A,B,C",
            req.tolerancePct() != null ? req.tolerancePct() : java.math.BigDecimal.valueOf(5));
    var resp = Mappers.toCycleCountHeader(result.header(), result.lines());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(resp, ApiResponse.Meta.of(ctx.requestId())))
        .build();
  }

  @GET
  @Path("/cycle-counts")
  public ApiResponse<List<CycleCountHeaderResponse>> listCycleCounts(
      @QueryParam("storeId") UUID storeId,
      @QueryParam("status") String status,
      @QueryParam("limit") Integer limit) {
    UUID tenantId = ctx.requireTenantId();
    int lim = limit != null ? limit : 20;
    var headers = service.listCycleCounts(tenantId, storeId, status, lim);
    var items =
        headers.stream().map(cwl -> Mappers.toCycleCountHeader(cwl.header(), cwl.lines())).toList();
    return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
  }

  @GET
  @Path("/cycle-counts/{id}")
  public ApiResponse<CycleCountHeaderResponse> getCycleCount(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var cwl = service.getCycleCount(tenantId, id);
    return ApiResponse.ok(
        Mappers.toCycleCountHeader(cwl.header(), cwl.lines()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/cycle-counts/{id}/lines/{lineId}/count")
  public ApiResponse<CycleCountLineResponse> enterCount(
      @PathParam("id") UUID headerId, @PathParam("lineId") UUID lineId, EnterCountRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var line = service.enterCount(tenantId, headerId, lineId, req.countedQty());
    return ApiResponse.ok(Mappers.toCycleCountLine(line), ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/cycle-counts/{id}/approve")
  public ApiResponse<CycleCountApproveResult> approveCycleCount(@PathParam("id") UUID headerId) {
    UUID tenantId = ctx.requireTenantId();
    var result = service.approveWithTolerance(tenantId, headerId);
    return ApiResponse.ok(
        new CycleCountApproveResult(result.autoApproved(), result.flagged()),
        ApiResponse.Meta.of(ctx.requestId()));
  }

  @POST
  @Path("/cycle-counts/{id}/adjust")
  public ApiResponse<CycleCountAdjustResult> adjustCycleCount(@PathParam("id") UUID headerId) {
    UUID tenantId = ctx.requireTenantId();
    int adjusted = service.adjustCycleCount(tenantId, headerId);
    return ApiResponse.ok(
        new CycleCountAdjustResult(adjusted), ApiResponse.Meta.of(ctx.requestId()));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static UUID uuid(String s, String field) {
    return com.shelfj.web.Parsing.uuid(s, field);
  }

  private static LocalDate parseDate(String s) {
    return com.shelfj.web.Parsing.date(s, "expiryDate");
  }

  // ── Physical Inventory (Gap #16) ─────────────────────────────────────────

  @POST
  @Path("/physical-inventories")
  public Response createPhysicalInventory(CreatePhysicalInventoryRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    var pi = service.createPhysicalInventory(tenantId, storeId, req.notes());
    var tags = service.listTags(tenantId, pi.id());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toPhysicalInventory(pi, tags)))
        .build();
  }

  @GET
  @Path("/physical-inventories")
  public ApiResponse<List<PhysicalInventoryResponse>> listPhysicalInventories(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listPhysicalInventories(tenantId, store).stream()
            .map(pi -> Mappers.toPhysicalInventory(pi, service.listTags(tenantId, pi.id())))
            .toList());
  }

  @GET
  @Path("/physical-inventories/{id}")
  public ApiResponse<PhysicalInventoryResponse> getPhysicalInventory(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var pi = service.getPhysicalInventory(tenantId, id);
    return ApiResponse.ok(Mappers.toPhysicalInventory(pi, service.listTags(tenantId, id)));
  }

  @POST
  @Path("/physical-inventories/{id}/tags")
  public ApiResponse<PhysicalInventoryTagResponse> addTag(
      @PathParam("id") UUID piId, AddTagRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID variantId = uuid(req.variantId(), "variantId");
    UUID zoneId = req.zoneId() != null ? uuid(req.zoneId(), "zoneId") : null;
    return ApiResponse.ok(
        Mappers.toTag(service.addTag(tenantId, piId, variantId, zoneId, req.systemQty())));
  }

  @POST
  @Path("/physical-inventories/{id}/tags/{tagId}/count")
  public ApiResponse<PhysicalInventoryTagResponse> countTag(
      @PathParam("id") UUID piId, @PathParam("tagId") UUID tagId, CountTagRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toTag(service.countTag(tenantId, piId, tagId, req.countedQty())));
  }

  @POST
  @Path("/physical-inventories/{id}/complete")
  public ApiResponse<PhysicalInventoryResponse> completePhysicalInventory(
      @PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    var pi = service.completePhysicalInventory(tenantId, id);
    return ApiResponse.ok(Mappers.toPhysicalInventory(pi, service.listTags(tenantId, id)));
  }

  // ── Gap #17: Costing Methods ────────────────────────────────────────────────

  @PUT
  @Path("/costing-methods")
  public ApiResponse<CostingMethodResponse> upsertCostingMethod(UpsertCostingMethodRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    return ApiResponse.ok(
        Mappers.toCostingMethod(
            service.upsertCostingMethod(tenantId, storeId, variantId, req.method())));
  }

  @GET
  @Path("/costing-methods")
  public ApiResponse<List<CostingMethodResponse>> listCostingMethods(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listCostingMethods(tenantId, storeId).stream()
            .map(Mappers::toCostingMethod)
            .toList());
  }

  @GET
  @Path("/costing-methods/by-variant")
  public ApiResponse<CostingMethodResponse> getCostingMethod(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    return ApiResponse.ok(
        Mappers.toCostingMethod(service.getCostingMethod(tenantId, storeId, variantId)));
  }

  @POST
  @Path("/accounting-periods")
  public Response openPeriod(OpenPeriodRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    var period = service.openPeriod(tenantId, storeId, req.periodName(), req.periodDate());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toPeriod(period)))
        .build();
  }

  @GET
  @Path("/accounting-periods")
  public ApiResponse<List<AccountingPeriodResponse>> listPeriods(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listPeriods(tenantId, storeId).stream().map(Mappers::toPeriod).toList());
  }

  @GET
  @Path("/accounting-periods/{id}")
  public ApiResponse<AccountingPeriodResponse> getPeriod(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toPeriod(service.getPeriod(tenantId, id)));
  }

  @POST
  @Path("/accounting-periods/{id}/close")
  public ApiResponse<AccountingPeriodResponse> closePeriod(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toPeriod(service.closePeriod(tenantId, id)));
  }

  // ── Gap #18: Kanban Replenishment ────────────────────────────────────────────

  @POST
  @Path("/kanban-cards")
  public Response createKanbanCard(CreateKanbanCardRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    UUID sourceStoreId =
        req.sourceStoreId() != null && !req.sourceStoreId().isBlank()
            ? uuid(req.sourceStoreId(), "sourceStoreId")
            : null;
    var card =
        service.createKanbanCard(
            tenantId,
            storeId,
            variantId,
            req.kanbanType(),
            req.reorderQty(),
            sourceStoreId,
            req.supplierRef(),
            req.notes());
    return Response.status(Response.Status.CREATED)
        .entity(ApiResponse.ok(Mappers.toKanbanCard(card)))
        .build();
  }

  @GET
  @Path("/kanban-cards")
  public ApiResponse<List<KanbanCardResponse>> listKanbanCards(
      @QueryParam("store") String store, @QueryParam("status") String status) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listKanbanCards(tenantId, storeId, status).stream()
            .map(Mappers::toKanbanCard)
            .toList());
  }

  @GET
  @Path("/kanban-cards/{id}")
  public ApiResponse<KanbanCardResponse> getKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.getKanbanCard(tenantId, id)));
  }

  @POST
  @Path("/kanban-cards/{id}/trigger")
  public ApiResponse<KanbanCardResponse> triggerKanbanCard(
      @PathParam("id") UUID id, TriggerKanbanRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.triggerKanbanCard(tenantId, id, req != null ? req.notes() : null)));
  }

  @POST
  @Path("/kanban-cards/{id}/replenish")
  public ApiResponse<KanbanCardResponse> replenishKanbanCard(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toKanbanCard(service.replenishKanbanCard(tenantId, id)));
  }

  // ── Gap #19: Reorder Point + EOQ ─────────────────────────────────────────────

  @PUT
  @Path("/rop-plans")
  public ApiResponse<RopPlanResponse> upsertRopPlan(UpsertRopPlanRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(req.storeId(), "storeId");
    UUID variantId = uuid(req.variantId(), "variantId");
    return ApiResponse.ok(
        Mappers.toRopPlan(
            service.upsertRopPlan(
                tenantId,
                storeId,
                variantId,
                req.leadTimeDays(),
                req.orderingCost(),
                req.holdingCostPct(),
                req.unitCost())));
  }

  @GET
  @Path("/rop-plans")
  public ApiResponse<List<RopPlanResponse>> listRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listRopPlans(tenantId, storeId).stream().map(Mappers::toRopPlan).toList());
  }

  @GET
  @Path("/rop-plans/by-variant")
  public ApiResponse<RopPlanResponse> getRopPlan(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    UUID variantId = uuid(variant, "variant");
    return ApiResponse.ok(Mappers.toRopPlan(service.getRopPlan(tenantId, storeId, variantId)));
  }

  @POST
  @Path("/rop-plans/compute")
  public ApiResponse<ComputeRopResult> computeRopPlans(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    int count = service.computeRopPlans(tenantId, storeId);
    return ApiResponse.ok(new ComputeRopResult(count));
  }

  // ── Gap #21: Transaction reason codes ────────────────────────────────────

  @POST
  @Path("/reason-codes")
  public ApiResponse<ReasonCodeResponse> createReasonCode(CreateReasonCodeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toReasonCode(service.createReasonCode(tenantId, req.code(), req.description())));
  }

  @GET
  @Path("/reason-codes")
  public ApiResponse<List<ReasonCodeResponse>> listReasonCodes() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listReasonCodes(tenantId).stream().map(Mappers::toReasonCode).toList());
  }

  @POST
  @Path("/reason-codes/{id}/activate")
  public ApiResponse<ReasonCodeResponse> activateReasonCode(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toReasonCode(service.setReasonCodeActive(tenantId, id, true)));
  }

  @POST
  @Path("/reason-codes/{id}/deactivate")
  public ApiResponse<ReasonCodeResponse> deactivateReasonCode(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toReasonCode(service.setReasonCodeActive(tenantId, id, false)));
  }

  // ── Gap #22: Transaction source types ────────────────────────────────────

  @POST
  @Path("/source-types")
  public ApiResponse<SourceTypeResponse> createSourceType(CreateSourceTypeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toSourceType(service.createSourceType(tenantId, req.code(), req.description())));
  }

  @GET
  @Path("/source-types")
  public ApiResponse<List<SourceTypeResponse>> listSourceTypes() {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listSourceTypes(tenantId).stream().map(Mappers::toSourceType).toList());
  }

  @POST
  @Path("/source-types/{id}/activate")
  public ApiResponse<SourceTypeResponse> activateSourceType(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toSourceType(service.setSourceTypeActive(tenantId, id, true)));
  }

  @POST
  @Path("/source-types/{id}/deactivate")
  public ApiResponse<SourceTypeResponse> deactivateSourceType(@PathParam("id") UUID id) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toSourceType(service.setSourceTypeActive(tenantId, id, false)));
  }

  // ── Gap #23: Lot split / merge ────────────────────────────────────────────

  @POST
  @Path("/lots/split")
  public ApiResponse<LotActionResponse> splitLot(LotSplitRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result =
        service.splitLot(
            tenantId, UUID.fromString(req.sourceBatchId()), req.qty(), req.batchNo(), req.notes());
    return ApiResponse.ok(Mappers.toLotAction(result.action()));
  }

  @POST
  @Path("/lots/merge")
  public ApiResponse<LotActionResponse> mergeLot(LotMergeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var result =
        service.mergeLot(
            tenantId,
            UUID.fromString(req.sourceBatchId()),
            UUID.fromString(req.targetBatchId()),
            req.qty(),
            req.notes());
    return ApiResponse.ok(Mappers.toLotAction(result.action()));
  }

  @GET
  @Path("/lots/{batchId}/actions")
  public ApiResponse<List<LotActionResponse>> listLotActions(@PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listLotActions(tenantId, batchId).stream().map(Mappers::toLotAction).toList());
  }

  // ── Gap #24: Expiry alert query ───────────────────────────────────────────

  @GET
  @Path("/batches/expiring")
  public ApiResponse<List<ExpiringBatchResponse>> listExpiringBatches(
      @QueryParam("store") String store, @QueryParam("withinDays") Integer withinDays) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    int days = withinDays == null ? 30 : withinDays;
    return ApiResponse.ok(
        service.listExpiringBatches(tenantId, storeId, days).stream()
            .map(Mappers::toExpiringBatch)
            .toList());
  }

  // ── Gap #25: Grade control ────────────────────────────────────────────────

  @PUT
  @Path("/batches/{id}/grade")
  public ApiResponse<BatchResponse> updateBatchGrade(
      @PathParam("id") UUID id, UpdateGradeRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(Mappers.toBatch(service.updateBatchGrade(tenantId, id, req.grade())));
  }

  // ── Gap #26: Lot UOM conversions ──────────────────────────────────────────

  @PUT
  @Path("/lots/{batchId}/uom-conversions")
  public ApiResponse<LotUomConversionResponse> upsertLotUomConversion(
      @PathParam("batchId") UUID batchId, UpsertLotUomConversionRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var c =
        service.upsertLotUomConversion(
            tenantId, batchId, req.fromUom(), req.toUom(), req.factor(), req.notes());
    return ApiResponse.ok(Mappers.toLotUomConversion(c));
  }

  @GET
  @Path("/lots/{batchId}/uom-conversions")
  public ApiResponse<List<LotUomConversionResponse>> listLotUomConversions(
      @PathParam("batchId") UUID batchId) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        service.listLotUomConversions(tenantId, batchId).stream()
            .map(Mappers::toLotUomConversion)
            .toList());
  }

  // ── Gap #27: PAR levels ───────────────────────────────────────────────────

  @PUT
  @Path("/par-levels")
  public ApiResponse<ParLevelResponse> upsertParLevel(UpsertParLevelRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    var p =
        service.upsertParLevel(
            tenantId,
            uuid(req.storeId(), "storeId"),
            uuid(req.variantId(), "variantId"),
            req.parQty(),
            req.uom(),
            req.reviewCycle());
    return ApiResponse.ok(Mappers.toParLevel(p));
  }

  @GET
  @Path("/par-levels")
  public ApiResponse<List<ParLevelResponse>> listParLevels(@QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listParLevels(tenantId, storeId).stream().map(Mappers::toParLevel).toList());
  }

  // ── Gap #28: Order modifiers ──────────────────────────────────────────────

  @PUT
  @Path("/rop-plans/{id}/order-modifiers")
  public ApiResponse<RopPlanResponse> updateRopModifiers(
      @PathParam("id") UUID id, UpdateOrderModifiersRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toRopPlan(
            service.updateRopOrderModifiers(
                tenantId, id, req.minOrderQty(), req.maxOrderQty(), req.lotMultiplier())));
  }

  @PUT
  @Path("/kanban-cards/{id}/order-modifiers")
  public ApiResponse<KanbanCardResponse> updateKanbanModifiers(
      @PathParam("id") UUID id, UpdateOrderModifiersRequest req) {
    UUID tenantId = ctx.requireTenantId();
    return ApiResponse.ok(
        Mappers.toKanbanCard(
            service.updateKanbanOrderModifiers(
                tenantId, id, req.minOrderQty(), req.maxOrderQty(), req.lotMultiplier())));
  }

  // ── Gap #30: Purge movements ──────────────────────────────────────────────

  @POST
  @Path("/movements/purge")
  public ApiResponse<PurgeResult> purgeMovements(PurgeMovementsRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    java.time.Instant before = java.time.Instant.parse(req.before());
    int purged = service.purgeMovementsBefore(tenantId, before);
    return ApiResponse.ok(new PurgeResult(purged));
  }

  // ── Gap #31: Zone GL mappings ─────────────────────────────────────────────

  @PUT
  @Path("/zone-gl-mappings")
  public ApiResponse<ZoneGlMappingResponse> upsertZoneGlMapping(UpsertZoneGlMappingRequest req) {
    Validations.validate(req);
    UUID tenantId = ctx.requireTenantId();
    UUID zoneId =
        req.zoneId() == null || req.zoneId().isBlank() ? null : UUID.fromString(req.zoneId());
    var m =
        service.upsertZoneGlMapping(
            tenantId, uuid(req.storeId(), "storeId"), zoneId, req.nominalCode(), req.description());
    return ApiResponse.ok(Mappers.toZoneGlMapping(m));
  }

  @GET
  @Path("/zone-gl-mappings")
  public ApiResponse<List<ZoneGlMappingResponse>> listZoneGlMappings(
      @QueryParam("store") String store) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = uuid(store, "store");
    return ApiResponse.ok(
        service.listZoneGlMappings(tenantId, storeId).stream()
            .map(Mappers::toZoneGlMapping)
            .toList());
  }

  // ── Picking Rules (Gap #38) ──────────────────────────────────────────────

  @POST
  @Path("/picking-rules")
  public Response createPickingRule(CreatePickingRuleRequest req) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                Mappers.toPickingRule(service.createPickingRule(ctx.requireTenantId(), req))))
        .build();
  }

  @GET
  @Path("/picking-rules")
  public ApiResponse<List<PickingRuleResponse>> listPickingRules() {
    return ApiResponse.ok(
        service.listPickingRules(ctx.requireTenantId()).stream()
            .map(Mappers::toPickingRule)
            .toList());
  }

  @GET
  @Path("/picking-rules/{id}")
  public ApiResponse<PickingRuleResponse> getPickingRule(@PathParam("id") UUID id) {
    return ApiResponse.ok(Mappers.toPickingRule(service.getPickingRule(ctx.requireTenantId(), id)));
  }

  @DELETE
  @Path("/picking-rules/{id}")
  public ApiResponse<PickingRuleResponse> deactivatePickingRule(@PathParam("id") UUID id) {
    return ApiResponse.ok(
        Mappers.toPickingRule(service.deactivatePickingRule(ctx.requireTenantId(), id)));
  }

  @PUT
  @Path("/picking-rules/{id}/zone-priorities")
  public ApiResponse<List<PickingRuleZonePriorityResponse>> setZonePriorities(
      @PathParam("id") UUID id, SetZonePrioritiesRequest req) {
    Validations.validate(req);
    return ApiResponse.ok(
        service.setZonePriorities(ctx.requireTenantId(), id, req).stream()
            .map(Mappers::toZonePriority)
            .toList());
  }

  @GET
  @Path("/picking-rules/{id}/zone-priorities")
  public ApiResponse<List<PickingRuleZonePriorityResponse>> listZonePriorities(
      @PathParam("id") UUID id) {
    return ApiResponse.ok(
        service.listZonePriorities(ctx.requireTenantId(), id).stream()
            .map(Mappers::toZonePriority)
            .toList());
  }

  @POST
  @Path("/picking-rule-assignments")
  public Response createPickingRuleAssignment(CreatePickingRuleAssignmentRequest req) {
    Validations.validate(req);
    return Response.status(Response.Status.CREATED)
        .entity(
            ApiResponse.ok(
                Mappers.toPickingRuleAssignment(
                    service.createPickingRuleAssignment(ctx.requireTenantId(), req))))
        .build();
  }

  @GET
  @Path("/picking-rule-assignments")
  public ApiResponse<List<PickingRuleAssignmentResponse>> listPickingRuleAssignments() {
    return ApiResponse.ok(
        service.listPickingRuleAssignments(ctx.requireTenantId()).stream()
            .map(Mappers::toPickingRuleAssignment)
            .toList());
  }

  @DELETE
  @Path("/picking-rule-assignments/{id}")
  public Response deletePickingRuleAssignment(@PathParam("id") UUID id) {
    service.deletePickingRuleAssignment(ctx.requireTenantId(), id);
    return Response.noContent().build();
  }

  @GET
  @Path("/picking-rules/resolve")
  public ApiResponse<PickingRuleResolveResponse> resolvePickingRule(
      @QueryParam("store") String store, @QueryParam("variant") String variant) {
    UUID tenantId = ctx.requireTenantId();
    if (store == null || variant == null) {
      throw new com.shelfj.web.ApiException(
          400, "MISSING_PARAM", "store and variant are required", List.of(), null);
    }
    return ApiResponse.ok(
        service.resolvePickingRule(tenantId, uuid(store, "store"), uuid(variant, "variant")));
  }
}
