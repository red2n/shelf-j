package com.shelfj.notification.api;

import com.shelfj.notification.mapper.Mappers;
import com.shelfj.notification.service.NotificationService;
import com.shelfj.web.ApiResponse;
import com.shelfj.web.TenantContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Thin JAX-RS resource for the back-office alert and notification feeds — validate, delegate to
 * {@link NotificationService}, wrap in envelope. No logic here.
 */
@Path("/admin/notifications")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Notifications")
public class AdminResource {

  @Inject NotificationService service;
  @Inject TenantContext ctx;

  /**
   * List shortage alerts for the tenant, newest first.
   *
   * @param storeId restrict to one store, or {@code null} for every store; ignored when {@code
   *     variantId} is given
   * @param variantId restrict to one variant across all stores, or {@code null}
   * @param limit page size; values outside 1..100 fall back to 20 rather than being rejected
   * @return the matching alerts as DTOs
   */
  @Operation(
      summary = "List shortage alerts",
      description =
          "Paginated shortage alerts for the caller's tenant, optionally filtered by store or"
              + " variant. Recorded from consumed StockBelowThreshold events, newest first.")
  @APIResponse(responseCode = "200", description = "Shortage alerts")
  @APIResponse(responseCode = "400", description = "storeId or variantId is not a valid UUID")
  @GET
  @Path("/shortage-alerts")
  public ApiResponse<Object> listShortageAlerts(
      @QueryParam("storeId") String storeId,
      @QueryParam("variantId") String variantId,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID tenantId = ctx.requireTenantId();
    int effectiveLimit = (limit < 1 || limit > 100) ? 20 : limit;

    var alerts =
        variantId != null
            ? service.listAlertsByVariant(tenantId, UUID.fromString(variantId), effectiveLimit)
            : service.listAlerts(
                tenantId, storeId != null ? UUID.fromString(storeId) : null, effectiveLimit);

    var dtos = alerts.stream().map(Mappers::toDto).toList();
    return ApiResponse.ok(dtos);
  }

  /**
   * In-app notifications feed (welcome / order-confirmation / …) for the tenant, newest first.
   *
   * @param recipient restrict to one recipient, or {@code null} for the whole tenant feed
   * @param limit page size; values outside 1..100 fall back to 20 rather than being rejected
   * @return the matching notifications as DTOs
   */
  @Operation(
      summary = "List in-app notifications",
      description =
          "In-app notifications feed (welcome / order-confirmation / shortage alert / …) for the"
              + " caller's tenant, newest first, optionally filtered by recipient.")
  @APIResponse(responseCode = "200", description = "Notifications")
  @GET
  public ApiResponse<Object> listNotifications(
      @QueryParam("recipient") String recipient,
      @QueryParam("limit") @DefaultValue("20") int limit) {
    UUID tenantId = ctx.requireTenantId();
    int effectiveLimit = (limit < 1 || limit > 100) ? 20 : limit;
    var notifications =
        service.listNotifications(
            tenantId, recipient != null && !recipient.isBlank() ? recipient : null, effectiveLimit);
    return ApiResponse.ok(notifications.stream().map(Mappers::toDto).toList());
  }
}
