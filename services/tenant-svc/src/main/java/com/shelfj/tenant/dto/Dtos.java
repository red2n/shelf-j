package com.shelfj.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request/response DTOs for tenant-svc. No tenant_id in requests — it comes from the JWT/context.
 */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  public record CreateTenantRequest(
      @NotBlank String businessName,
      String legalName,
      @NotBlank @Size(min = 2, max = 2) String country,
      @NotBlank @Size(min = 3, max = 3) String currency) {}

  public record UpdateTenantRequest(@NotBlank String businessName, String legalName) {}

  public record CreateStoreRequest(
      @NotBlank String name,
      @NotBlank String code,
      String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      BigDecimal geoLat,
      BigDecimal geoLng,
      String timezone,
      String businessHours) {}

  public record UpdateStoreRequest(
      @NotBlank String name,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      BigDecimal geoLat,
      BigDecimal geoLng,
      String timezone,
      String businessHours) {}

  public record PatchStatusRequest(@NotBlank String status) {}

  public record CreateZoneRequest(@NotBlank String name, @NotBlank String code, String type) {}

  public record UpdateZoneRequest(@NotBlank String name, @NotBlank String code, String type) {}

  public record AssignStaffRequest(
      @NotBlank String userId, @NotBlank String storeId, @NotBlank String role) {}

  // ── responses ────────────────────────────────────────────────────────────────

  public record TenantResponse(
      String id,
      String name,
      String legalName,
      String status,
      String country,
      String currency,
      String createdAt,
      String updatedAt) {}

  public record StoreResponse(
      String id,
      String name,
      String code,
      String type,
      String status,
      boolean isDefault,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      BigDecimal geoLat,
      BigDecimal geoLng,
      String timezone,
      String businessHours,
      String createdAt,
      String updatedAt) {}

  public record ZoneResponse(
      String id,
      String storeId,
      String name,
      String code,
      String type,
      String status,
      String createdAt,
      String updatedAt) {}

  public record StaffResponse(
      String id, String userId, String storeId, String role, String assignedAt) {}

  public record OnboardingStatus(
      boolean tenantActive, boolean hasDefaultStore, List<String> nextSteps) {}

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  public record UpsertInventoryConfigRequest(
      Boolean lotControlEnabled,
      Boolean serialControlEnabled,
      Boolean gradeControlEnabled,
      Boolean expiryTrackingEnabled,
      String costingMethod,
      String defaultUom,
      Boolean reorderAlertEnabled,
      Boolean autoReserveOnOrder) {}

  public record TenantInventoryConfigResponse(
      String id,
      String tenantId,
      boolean lotControlEnabled,
      boolean serialControlEnabled,
      boolean gradeControlEnabled,
      boolean expiryTrackingEnabled,
      String costingMethod,
      String defaultUom,
      boolean reorderAlertEnabled,
      boolean autoReserveOnOrder,
      String createdAt,
      String updatedAt) {}
}
