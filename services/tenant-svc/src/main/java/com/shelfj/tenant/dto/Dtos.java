package com.shelfj.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for tenant-svc. No tenant_id in requests — it comes from the JWT/context.
 */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "CreateTenantRequest", description = "Create the business (tenant).")
  public record CreateTenantRequest(
      @NotBlank String businessName,
      String legalName,
      @Schema(description = "ISO 3166-1 alpha-2 country code.") @NotBlank @Size(min = 2, max = 2)
          String country,
      @Schema(description = "ISO 4217 currency code.") @NotBlank @Size(min = 3, max = 3)
          String currency) {}

  @Schema(name = "UpdateTenantRequest")
  public record UpdateTenantRequest(@NotBlank String businessName, String legalName) {}

  @Schema(name = "CreateStoreRequest", description = "Create a store (STORE or WAREHOUSE).")
  public record CreateStoreRequest(
      @NotBlank String name,
      @NotBlank String code,
      @Schema(description = "STORE or WAREHOUSE. Defaults to STORE.") String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      @Schema(description = "Store latitude, for geo/delivery-area features.") BigDecimal geoLat,
      @Schema(description = "Store longitude, for geo/delivery-area features.") BigDecimal geoLng,
      String timezone,
      @Schema(description = "Free-form business hours (e.g. serialized weekly schedule).")
          String businessHours,
      @Schema(
              description =
                  "Null defaults to true (show prices). false = availability-only storefront.")
          Boolean showPrices,
      @Schema(description = "Null defaults to CASH,CARD. Subset of CASH, CARD, UPI, WALLET.")
          List<String> enabledPaymentMethods) {}

  @Schema(name = "UpdateStoreRequest")
  public record UpdateStoreRequest(
      @NotBlank String name,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      @Schema(description = "Store latitude, for geo/delivery-area features.") BigDecimal geoLat,
      @Schema(description = "Store longitude, for geo/delivery-area features.") BigDecimal geoLng,
      String timezone,
      String businessHours,
      Boolean showPrices,
      @Schema(
              description =
                  "Null keeps current value. Subset of CASH, CARD, UPI, WALLET; must not be"
                      + " empty.")
          List<String> enabledPaymentMethods) {}

  @Schema(name = "PatchStatusRequest")
  public record PatchStatusRequest(
      @Schema(description = "New status, e.g. ACTIVE or INACTIVE.") @NotBlank String status) {}

  @Schema(name = "CreateZoneRequest", description = "Create a zone (aisle/rack/etc.) in a store.")
  public record CreateZoneRequest(
      @NotBlank String name,
      @NotBlank String code,
      @Schema(description = "e.g. AISLE, COLD_ROOM, BACK_STORE. Defaults to AISLE.") String type) {}

  @Schema(name = "UpdateZoneRequest")
  public record UpdateZoneRequest(@NotBlank String name, @NotBlank String code, String type) {}

  @Schema(name = "AssignStaffRequest", description = "Assign a staff user a role at a store.")
  public record AssignStaffRequest(
      @Schema(description = "UUID of the user to assign (must already exist in iam-svc).") @NotBlank
          String userId,
      @Schema(description = "UUID of the store the role applies to.") @NotBlank String storeId,
      @Schema(description = "Role name, e.g. OWNER, MANAGER, STAFF.") @NotBlank String role) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "TenantResponse")
  public record TenantResponse(
      String id,
      String name,
      String legalName,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      String country,
      String currency,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "StoreResponse")
  public record StoreResponse(
      String id,
      String name,
      String code,
      @Schema(description = "STORE or WAREHOUSE.") String type,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      @Schema(description = "True if this is the tenant's default store.") boolean isDefault,
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
  @Schema(
      name = "StorefrontConfigResponse",
      description = "Public storefront config for a store (what the guest shop needs to render).")
  public record StorefrontConfigResponse(
      String storeId,
      String storeName,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      boolean showPrices,
      List<String> enabledPaymentMethods,
      String line1,
      String city,
      String country,
      String pincode,
      String phone) {}

  @Schema(name = "ZoneResponse")
  public record ZoneResponse(
      String id,
      String storeId,
      String name,
      String code,
      @Schema(description = "e.g. AISLE, COLD_ROOM, BACK_STORE, DEFAULT.") String type,
      @Schema(description = "ACTIVE or INACTIVE.") String status,
      String createdAt,
      String updatedAt) {}

  @Schema(name = "StaffResponse")
  public record StaffResponse(
      String id, String userId, String storeId, String role, String assignedAt) {}

  @Schema(name = "OnboardingStatus", description = "Setup-checklist state for the tenant.")
  public record OnboardingStatus(
      boolean tenantActive, boolean hasDefaultStore, List<String> nextSteps) {}

  // ── Combined onboarding (tenant + first store in one call) ───────────────

  @Schema(
      name = "OnboardRequest",
      description = "Combined onboarding: create tenant + first store in one call.")
  public record OnboardRequest(
      // tenant
      @NotBlank String businessName,
      String legalName,
      @Schema(description = "ISO 3166-1 alpha-2 country code.") @NotBlank @Size(min = 2, max = 2)
          String country,
      @Schema(description = "ISO 4217 currency code.") @NotBlank @Size(min = 3, max = 3)
          String currency,
      // first store
      @NotBlank String storeName,
      @NotBlank String storeCode,
      @Schema(description = "STORE or WAREHOUSE. Defaults to STORE.") String storeType,
      String storeLine1,
      String storeCity,
      String storeCountry,
      String storePincode,
      @Schema(description = "Defaults to UTC.") String storeTimezone) {}

  @Schema(name = "OnboardResponse")
  public record OnboardResponse(TenantResponse tenant, StoreResponse store) {}

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  @Schema(
      name = "UpsertInventoryConfigRequest",
      description =
          "Per-tenant inventory-control parameters. Every field is optional — unset fields keep"
              + " their current (or default) value.")
  public record UpsertInventoryConfigRequest(
      Boolean lotControlEnabled,
      Boolean serialControlEnabled,
      Boolean gradeControlEnabled,
      Boolean expiryTrackingEnabled,
      @Schema(description = "One of FIFO, AVERAGE, STANDARD.")
          @Pattern(regexp = "FIFO|AVERAGE|STANDARD", message = "must be FIFO, AVERAGE or STANDARD")
          String costingMethod,
      @Schema(description = "Default unit-of-measure code, e.g. EA.") @Size(max = 16)
          String defaultUom,
      Boolean reorderAlertEnabled,
      Boolean autoReserveOnOrder) {}

  @Schema(name = "TenantInventoryConfigResponse")
  public record TenantInventoryConfigResponse(
      String id,
      String tenantId,
      boolean lotControlEnabled,
      boolean serialControlEnabled,
      boolean gradeControlEnabled,
      boolean expiryTrackingEnabled,
      @Schema(description = "FIFO, AVERAGE, or STANDARD.") String costingMethod,
      String defaultUom,
      boolean reorderAlertEnabled,
      boolean autoReserveOnOrder,
      String createdAt,
      String updatedAt) {}

  // ── delivery areas ─────────────────────────────────────────────────────────

  @Schema(
      name = "CreateDeliveryAreaRequest",
      description = "Map a pincode (postal code) to this store for home delivery fulfilment.")
  public record CreateDeliveryAreaRequest(
      @NotBlank @Size(max = 32) String pincode,
      @Schema(description = "Lower number = higher priority when multiple stores cover a pincode.")
          Integer priority) {}

  @Schema(name = "DeliveryAreaResponse")
  public record DeliveryAreaResponse(
      String id, String storeId, String pincode, int priority, String createdAt) {}

  @Schema(name = "FulfilmentResolveResponse")
  public record FulfilmentResolveResponse(
      @Schema(description = "Store that should fulfil a DELIVERY order for the given pincode.")
          String storeId,
      String storeName,
      String storeCode,
      String pincode,
      int priority) {}

  @Schema(
      name = "CurrencyRepublishResponse",
      description = "How many tenants had their declared currency re-announced.")
  public record CurrencyRepublishResponse(
      @Schema(
              description =
                  "Tenants announced. Zero means no tenant in scope has a currency recorded, not"
                      + " that the replay failed.")
          int tenantsAnnounced) {}

  // ── weighing instruments (Weights and Measures Act 1985) ────────────────────

  @Schema(name = "CreateWeighingInstrumentRequest")
  public record CreateWeighingInstrumentRequest(
      @NotBlank @Schema(description = "The shop's own name for it, unique within the store.")
          String identifier,
      @NotBlank @Schema(description = "As on the plate; unique within the tenant.")
          String serialNumber,
      String make,
      String model,
      @Schema(description = "COUNTER (default), LABELLING, PLATFORM or HANGING.") String kind,
      @Schema(description = "Max capacity as marked on the plate.") BigDecimal maxCapacity,
      @Schema(description = "UOM for maxCapacity, e.g. KG.") String capacityUom,
      @Schema(description = "The verification scale interval e, as marked.")
          BigDecimal scaleInterval,
      @Schema(description = "Type-approval / conformity certificate reference.") String approvalRef,
      @Schema(description = "The zone it stands in, if any.") String zoneId,
      @Schema(
              description =
                  "LABELLING only: JSON {prefixes:[\"20\",…], itemDigits:4|5, valueKind:"
                      + " \"PRICE\"|\"WEIGHT\", valueDecimals:n, priceCheckDigit:bool} describing"
                      + " the barcode the scale prints.")
          String labelScheme) {}

  @Schema(name = "UpdateWeighingInstrumentRequest")
  public record UpdateWeighingInstrumentRequest(
      @NotBlank String identifier,
      @NotBlank String serialNumber,
      String make,
      String model,
      String kind,
      BigDecimal maxCapacity,
      String capacityUom,
      BigDecimal scaleInterval,
      String approvalRef,
      String zoneId,
      String labelScheme) {}

  @Schema(
      name = "RecordVerificationRequest",
      description = "One entry in the instrument's history. Append-only.")
  public record RecordVerificationRequest(
      @NotBlank @Schema(description = "INITIAL, RE_VERIFICATION, INSPECTION or REPAIR.")
          String kind,
      @NotBlank @Schema(description = "ISO date the work was done.") String performedOn,
      @NotBlank @Schema(description = "The verifier or inspector, by name and organisation.")
          String performedBy,
      String certificateRef,
      @NotNull @Schema(description = "Whether the instrument was passed. A REPAIR is never a pass.")
          Boolean passed,
      @Schema(description = "ISO date it is due again; null when verified until repaired.")
          String nextDue,
      String notes) {}

  @Schema(name = "InstrumentVerificationResponse")
  public record InstrumentVerificationResponse(
      String id,
      String kind,
      String performedOn,
      String performedBy,
      String certificateRef,
      boolean passed,
      String nextDue,
      String notes,
      String recordedAt) {}

  @Schema(
      name = "WeighingInstrumentResponse",
      description =
          "An instrument and its standing for trade. certified is derived from the history on"
              + " every read: in service, latest entry a pass, and not yet due again.")
  public record WeighingInstrumentResponse(
      String id,
      String storeId,
      String identifier,
      String serialNumber,
      String make,
      String model,
      String kind,
      BigDecimal maxCapacity,
      String capacityUom,
      BigDecimal scaleInterval,
      String approvalRef,
      String zoneId,
      String labelScheme,
      @Schema(description = "IN_SERVICE, OUT_OF_SERVICE or RETIRED.") String status,
      @Schema(description = "May be used for trade today.") boolean certified,
      @Schema(
              description =
                  "Why, in a word: CERTIFIED, NEVER_VERIFIED, FAILED, REPAIRED_SINCE, OVERDUE,"
                      + " OUT_OF_SERVICE, RETIRED.")
          String standing,
      InstrumentVerificationResponse latestVerification,
      String createdAt,
      String updatedAt) {}
}
