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

  /**
   * Converts a tenant to its wire form.
   *
   * @param t the tenant to convert
   * @return its API representation
   */
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

  /**
   * Converts a store to its wire form.
   *
   * @param s the store to convert
   * @return its API representation, with the stored tender CSV split into a list
   */
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
  /**
   * Splits the stored comma-separated tender list into its parts.
   *
   * <p>Kept as CSV in the column but exposed as a list, so the wire contract does not leak the
   * storage shape.
   *
   * @param csv the stored value, which may be {@code null} or blank
   * @return the tender codes, empty when nothing is stored
   */
  public static java.util.List<String> paymentMethodsList(String csv) {
    String effective = csv == null || csv.isBlank() ? Store.DEFAULT_PAYMENT_METHODS : csv;
    return java.util.Arrays.stream(effective.split(",")).map(String::trim).toList();
  }

  /**
   * Converts a zone to its wire form.
   *
   * @param z the zone to convert
   * @return its API representation
   */
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

  /**
   * Converts a staff assignment to its wire form.
   *
   * @param s the staff assignment to convert
   * @return its API representation: the user, the store and the role granted there
   */
  public static StaffResponse toStaff(StaffAssignment s) {
    return new StaffResponse(
        s.id().toString(),
        s.userId().toString(),
        s.storeId().toString(),
        s.role(),
        ts(s.createdAt()));
  }

  /**
   * Converts the tenant's inventory-control parameters to their wire form.
   *
   * @param c the configuration to convert
   * @return its API representation
   */
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

  /**
   * Converts a delivery area to its wire form.
   *
   * @param a the delivery area to convert
   * @return its API representation: the pincode and its priority
   */
  public static DeliveryAreaResponse toDto(DeliveryArea a) {
    return new DeliveryAreaResponse(
        a.id().toString(), a.storeId().toString(), a.pincode(), a.priority(), ts(a.createdAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  /**
   * Converts one history entry to its wire form.
   *
   * @param v the entry
   * @return its API representation
   */
  public static com.shelfj.tenant.dto.Dtos.InstrumentVerificationResponse toVerification(
      com.shelfj.tenant.domain.Domain.InstrumentVerification v) {
    return new com.shelfj.tenant.dto.Dtos.InstrumentVerificationResponse(
        v.id().toString(),
        v.kind(),
        v.performedOn().toString(),
        v.performedBy(),
        v.certificateRef(),
        v.passed(),
        v.nextDue() == null ? null : v.nextDue().toString(),
        v.notes(),
        ts(v.recordedAt()));
  }

  /**
   * Converts an instrument with its standing to its wire form.
   *
   * @param w the instrument and its derived standing
   * @return its API representation
   */
  public static com.shelfj.tenant.dto.Dtos.WeighingInstrumentResponse toInstrument(
      com.shelfj.tenant.domain.Domain.WeighingInstrumentWithStanding w) {
    var i = w.instrument();
    return new com.shelfj.tenant.dto.Dtos.WeighingInstrumentResponse(
        i.id().toString(),
        i.storeId().toString(),
        i.identifier(),
        i.serialNumber(),
        i.make(),
        i.model(),
        i.kind(),
        i.maxCapacity(),
        i.capacityUom(),
        i.scaleInterval(),
        i.approvalRef(),
        i.zoneId() == null ? null : i.zoneId().toString(),
        i.labelScheme(),
        i.status(),
        w.certified(),
        w.standing(),
        w.latest() == null ? null : toVerification(w.latest()),
        ts(i.createdAt()),
        ts(i.updatedAt()));
  }
}
