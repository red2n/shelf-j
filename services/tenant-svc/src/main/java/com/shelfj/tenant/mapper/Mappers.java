package com.shelfj.tenant.mapper;

import com.shelfj.tenant.domain.Domain.DeliveryArea;
import com.shelfj.tenant.domain.Domain.StaffAssignment;
import com.shelfj.tenant.domain.Domain.Store;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.Domain.TenantInventoryConfig;
import com.shelfj.tenant.domain.Domain.Zone;
import com.shelfj.tenant.dto.Dtos.DeliveryAreaResponse;
import com.shelfj.tenant.dto.Dtos.StaffResponse;
import com.shelfj.tenant.dto.Dtos.StoreResponse;
import com.shelfj.tenant.dto.Dtos.TenantInventoryConfigResponse;
import com.shelfj.tenant.dto.Dtos.TenantResponse;
import com.shelfj.tenant.dto.Dtos.ZoneResponse;
import java.time.Instant;

/** Entity → DTO conversion (never expose entities over HTTP). */
public final class Mappers {

  private Mappers() {}

  public static TenantResponse toTenant(Tenant t) {
    return new TenantResponse(
        t.id().toString(),
        t.name(),
        t.legalName(),
        t.status(),
        t.country(),
        t.currency(),
        ts(t.createdAt()),
        ts(t.updatedAt()));
  }

  public static StoreResponse toStore(Store s) {
    return new StoreResponse(
        s.id().toString(),
        s.name(),
        s.code(),
        s.type(),
        s.status(),
        s.isDefault(),
        s.line1(),
        s.line2(),
        s.city(),
        s.state(),
        s.country(),
        s.pincode(),
        s.geoLat(),
        s.geoLng(),
        s.timezone(),
        s.businessHours(),
        s.showPrices(),
        paymentMethodsList(s.enabledPaymentMethods()),
        ts(s.createdAt()),
        ts(s.updatedAt()));
  }

  /** CSV column → JSON list; a null/blank column (pre-migration row) falls back to the default. */
  public static java.util.List<String> paymentMethodsList(String csv) {
    String effective = csv == null || csv.isBlank() ? Store.DEFAULT_PAYMENT_METHODS : csv;
    return java.util.Arrays.stream(effective.split(",")).map(String::trim).toList();
  }

  public static ZoneResponse toZone(Zone z) {
    return new ZoneResponse(
        z.id().toString(),
        z.storeId().toString(),
        z.name(),
        z.code(),
        z.type(),
        z.status(),
        ts(z.createdAt()),
        ts(z.updatedAt()));
  }

  public static StaffResponse toStaff(StaffAssignment s) {
    return new StaffResponse(
        s.id().toString(),
        s.userId().toString(),
        s.storeId().toString(),
        s.role(),
        ts(s.createdAt()));
  }

  public static TenantInventoryConfigResponse toDto(TenantInventoryConfig c) {
    return new TenantInventoryConfigResponse(
        c.id().toString(),
        c.tenantId().toString(),
        c.lotControlEnabled(),
        c.serialControlEnabled(),
        c.gradeControlEnabled(),
        c.expiryTrackingEnabled(),
        c.costingMethod(),
        c.defaultUom(),
        c.reorderAlertEnabled(),
        c.autoReserveOnOrder(),
        ts(c.createdAt()),
        ts(c.updatedAt()));
  }

  public static DeliveryAreaResponse toDto(DeliveryArea a) {
    return new DeliveryAreaResponse(
        a.id().toString(), a.storeId().toString(), a.pincode(), a.priority(), ts(a.createdAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
