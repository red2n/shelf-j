package com.shelfj.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain records for tenant-svc (Tenant → Stores → Zones). */
public final class Domain {

  private Domain() {}

  public record Tenant(
      UUID id,
      String name,
      String legalName,
      String status,
      UUID planId,
      UUID ownerUserId,
      String country,
      String currency,
      Instant createdAt,
      Instant updatedAt) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
  }

  public record Store(
      UUID id,
      UUID tenantId,
      String name,
      String code,
      String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      java.math.BigDecimal geoLat,
      java.math.BigDecimal geoLng,
      String timezone,
      String businessHours,
      String status,
      boolean isDefault,
      boolean showPrices,
      // CSV subset of PAYMENT_METHODS, e.g. "CASH,CARD,UPI" — the tenders this store accepts.
      String enabledPaymentMethods,
      Instant createdAt,
      Instant updatedAt) {
    public static final String TYPE_STORE = "STORE";
    public static final String TYPE_WAREHOUSE = "WAREHOUSE";
    public static final java.util.List<String> PAYMENT_METHODS =
        java.util.List.of("CASH", "CARD", "UPI", "WALLET");
    public static final String DEFAULT_PAYMENT_METHODS = "CASH,CARD";
  }

  public record Zone(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String code,
      String type,
      String status,
      Instant createdAt,
      Instant updatedAt) {
    public static final String TYPE_DEFAULT = "DEFAULT";
  }

  public record StaffAssignment(
      UUID id, UUID tenantId, UUID userId, UUID storeId, String role, Instant createdAt) {}

  /** Paired result of creating a store and its default zone atomically. */
  public record StoreWithZone(Store store, Zone defaultZone) {}

  // ── Gap #53: Inventory org parameters ────────────────────────────────────

  public record TenantInventoryConfig(
      UUID id,
      UUID tenantId,
      boolean lotControlEnabled,
      boolean serialControlEnabled,
      boolean gradeControlEnabled,
      boolean expiryTrackingEnabled,
      String costingMethod,
      String defaultUom,
      boolean reorderAlertEnabled,
      boolean autoReserveOnOrder,
      Instant createdAt,
      Instant updatedAt) {
    public static final String COSTING_FIFO = "FIFO";
    public static final String COSTING_AVERAGE = "AVERAGE";
    public static final String COSTING_STANDARD = "STANDARD";
  }

  public record TenantWithStore(Tenant tenant, Store store) {}
}
