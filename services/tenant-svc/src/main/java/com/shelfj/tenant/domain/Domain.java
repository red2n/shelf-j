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
      Instant createdAt) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
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
      Instant createdAt) {
    public static final String TYPE_STORE = "STORE";
  }

  public record Zone(
      UUID id,
      UUID tenantId,
      UUID storeId,
      String name,
      String code,
      String type,
      String status,
      Instant createdAt) {
    public static final String TYPE_DEFAULT = "DEFAULT";
  }

  public record StaffAssignment(
      UUID id, UUID tenantId, UUID userId, UUID storeId, String role, Instant createdAt) {}

  /** Paired result of creating a store and its default zone atomically. */
  public record StoreWithZone(Store store, Zone defaultZone) {}
}
