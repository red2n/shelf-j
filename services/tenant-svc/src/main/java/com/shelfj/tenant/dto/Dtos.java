package com.shelfj.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
      String businessHours,
      // null → defaults to true (show prices). false = availability-only storefront.
      Boolean showPrices,
      // null → defaults to CASH,CARD. Subset of CASH, CARD, UPI, WALLET.
      List<String> enabledPaymentMethods) {}

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
      String businessHours,
      Boolean showPrices,
      // null → keep current. Subset of CASH, CARD, UPI, WALLET; must not be empty.
      List<String> enabledPaymentMethods) {}

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
      boolean showPrices,
      List<String> enabledPaymentMethods,
      String createdAt,
      String updatedAt) {}

  /** Public storefront config for a store (what the guest shop needs to render). */
  public record StorefrontConfigResponse(
      String storeId,
      String storeName,
      String status,
      boolean showPrices,
      List<String> enabledPaymentMethods,
      String line1,
      String city,
      String country,
      String pincode,
      String phone) {}

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

  // ── Combined onboarding (tenant + first store in one call) ───────────────

  public record OnboardRequest(
      // tenant
      @NotBlank String businessName,
      String legalName,
      @NotBlank @Size(min = 2, max = 2) String country,
      @NotBlank @Size(min = 3, max = 3) String currency,
      // first store
      @NotBlank String storeName,
      @NotBlank String storeCode,
      String storeType,
      String storeLine1,
      String storeCity,
      String storeCountry,
      String storePincode,
      String storeTimezone) {}

  public record OnboardResponse(TenantResponse tenant, StoreResponse store) {}

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  public record UpsertInventoryConfigRequest(
      Boolean lotControlEnabled,
      Boolean serialControlEnabled,
      Boolean gradeControlEnabled,
      Boolean expiryTrackingEnabled,
      // Optional, but if supplied must be one of the supported costing methods (was stored
      // verbatim).
      @Pattern(regexp = "FIFO|AVERAGE|STANDARD", message = "must be FIFO, AVERAGE or STANDARD")
          String costingMethod,
      @Size(max = 16) String defaultUom,
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
