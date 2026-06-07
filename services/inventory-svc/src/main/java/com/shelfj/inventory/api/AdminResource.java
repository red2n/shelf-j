package com.shelfj.inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.shelfj.inventory.dto.Dtos.AdjustRequest;
import com.shelfj.inventory.dto.Dtos.BatchResponse;
import com.shelfj.inventory.dto.Dtos.LevelResponse;
import com.shelfj.inventory.dto.Dtos.ReceiveRequest;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Admin inventory ops: receive (manual), adjust, levels. Tenant-scoped. */
@Path("/admin/inventory")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    @Inject InventoryService service;
    @Inject TenantContext ctx;

    @POST @Path("/receive")
    public Response receive(ReceiveRequest req) {
        Validations.validate(req);
        UUID tenantId = ctx.requireTenantId();
        LocalDate expiry = req.expiryDate() == null || req.expiryDate().isBlank() ? null : parseDate(req.expiryDate());
        var batch = service.receive(tenantId, uuid(req.storeId(), "storeId"), uuid(req.variantId(), "variantId"),
                req.qty(), req.batchNo(), req.costPrice(), expiry, "MANUAL", null);
        return Response.status(Response.Status.CREATED).entity(ApiResponse.ok(Mappers.toBatch(batch))).build();
    }

    @POST @Path("/adjust")
    public ApiResponse<String> adjust(AdjustRequest req) {
        Validations.validate(req);
        UUID tenantId = ctx.requireTenantId();
        service.adjust(tenantId, uuid(req.storeId(), "storeId"), uuid(req.variantId(), "variantId"),
                req.delta(), req.reason());
        return ApiResponse.ok("adjusted");
    }

    @GET @Path("/levels")
    public ApiResponse<List<LevelResponse>> levels(@QueryParam("store") String store) {
        UUID tenantId = ctx.requireTenantId();
        UUID storeId = store == null || store.isBlank() ? null : uuid(store, "store");
        List<LevelResponse> items = service.levels(tenantId, storeId).stream().map(Mappers::toLevel).toList();
        return ApiResponse.ok(items, ApiResponse.Meta.of(ctx.requestId()));
    }

    private static UUID uuid(String s, String field) {
        try { return UUID.fromString(s); }
        catch (RuntimeException e) { throw ApiException.badRequest("INVALID_UUID", field + " must be a UUID"); }
    }

    private static LocalDate parseDate(String s) {
        try { return LocalDate.parse(s); }
        catch (RuntimeException e) { throw ApiException.badRequest("INVALID_DATE", "expiryDate must be yyyy-MM-dd"); }
    }
}
