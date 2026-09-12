package com.shelfj.tenant.repo;

import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.tenant.domain.Domain.InstrumentVerification;
import com.shelfj.tenant.domain.Domain.WeighingInstrument;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the weighing-instrument register. Every query filters by tenant_id first (golden
 * rule #3); the verification history is append-only (rule #8).
 */
@ApplicationScoped
public class WeighingInstrumentRepository extends BaseJdbcRepository {

  private static final String INSTRUMENT_COLUMNS =
      "id, tenant_id, store_id, identifier, serial_number, make, model, kind, max_capacity,"
          + " capacity_uom, scale_interval, approval_ref, zone_id, label_scheme, status,"
          + " created_at, updated_at";

  private static final String VERIFICATION_COLUMNS =
      "id, tenant_id, instrument_id, kind, performed_on, performed_by, certificate_ref, passed,"
          + " next_due, notes, recorded_by, recorded_at";

  /**
   * Inserts an instrument.
   *
   * @param i the instrument, with its id minted
   * @return the instrument as stored
   * @throws ApiException {@code INSTRUMENT_DUPLICATE} (409) when the identifier is taken at the
   *     store or the serial number in the tenant
   */
  public WeighingInstrument create(WeighingInstrument i) {
    try {
      exec(
          "INSERT INTO weighing_instruments ("
              + INSTRUMENT_COLUMNS
              + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          ps -> {
            ps.setObject(1, i.id());
            ps.setObject(2, i.tenantId());
            ps.setObject(3, i.storeId());
            ps.setString(4, i.identifier());
            ps.setString(5, i.serialNumber());
            ps.setString(6, i.make());
            ps.setString(7, i.model());
            ps.setString(8, i.kind());
            ps.setBigDecimal(9, i.maxCapacity());
            ps.setString(10, i.capacityUom());
            ps.setBigDecimal(11, i.scaleInterval());
            ps.setString(12, i.approvalRef());
            ps.setObject(13, i.zoneId());
            ps.setString(14, i.labelScheme());
            ps.setString(15, i.status());
            ps.setObject(16, i.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(17, i.updatedAt().atOffset(ZoneOffset.UTC));
          },
          "create weighing instrument");
    } catch (ApiException e) {
      throw duplicate(e);
    }
    return i;
  }

  /**
   * Rewrites an instrument's descriptive fields; status has its own path.
   *
   * @param i the instrument with its new fields
   * @return the instrument as stored
   */
  public WeighingInstrument update(WeighingInstrument i) {
    try {
      exec(
          "UPDATE weighing_instruments SET identifier=?, serial_number=?, make=?, model=?, kind=?,"
              + " max_capacity=?, capacity_uom=?, scale_interval=?, approval_ref=?, zone_id=?,"
              + " label_scheme=?, updated_at=? WHERE tenant_id=? AND id=?",
          ps -> {
            ps.setString(1, i.identifier());
            ps.setString(2, i.serialNumber());
            ps.setString(3, i.make());
            ps.setString(4, i.model());
            ps.setString(5, i.kind());
            ps.setBigDecimal(6, i.maxCapacity());
            ps.setString(7, i.capacityUom());
            ps.setBigDecimal(8, i.scaleInterval());
            ps.setString(9, i.approvalRef());
            ps.setObject(10, i.zoneId());
            ps.setString(11, i.labelScheme());
            ps.setObject(12, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(13, i.tenantId());
            ps.setObject(14, i.id());
          },
          "update weighing instrument");
    } catch (ApiException e) {
      throw duplicate(e);
    }
    return find(i.tenantId(), i.id()).orElseThrow(() -> notFound());
  }

  /**
   * Sets an instrument's status.
   *
   * @param tenantId owning tenant; the first condition
   * @param id the instrument
   * @param status the status to set
   * @return the instrument as stored
   */
  public WeighingInstrument updateStatus(UUID tenantId, UUID id, String status) {
    exec(
        "UPDATE weighing_instruments SET status=?, updated_at=? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setString(1, status);
          ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
          ps.setObject(3, tenantId);
          ps.setObject(4, id);
        },
        "update weighing instrument status");
    return find(tenantId, id).orElseThrow(() -> notFound());
  }

  /**
   * Looks an instrument up.
   *
   * @param tenantId owning tenant; the first condition
   * @param id the instrument
   * @return the instrument, or empty
   */
  public Optional<WeighingInstrument> find(UUID tenantId, UUID id) {
    return query(
            "SELECT "
                + INSTRUMENT_COLUMNS
                + " FROM weighing_instruments WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            WeighingInstrumentRepository::mapInstrument,
            "find weighing instrument")
        .stream()
        .findFirst();
  }

  /**
   * Every instrument at a store, retired ones included, in the order they were added.
   *
   * @param tenantId owning tenant; the first condition
   * @param storeId the store
   * @return the instruments
   */
  public List<WeighingInstrument> listByStore(UUID tenantId, UUID storeId) {
    return query(
        "SELECT "
            + INSTRUMENT_COLUMNS
            + " FROM weighing_instruments WHERE tenant_id=? AND store_id=? ORDER BY created_at, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        WeighingInstrumentRepository::mapInstrument,
        "list weighing instruments");
  }

  /**
   * Appends one history entry. Never updated or deleted.
   *
   * @param v the entry, with its id minted
   * @return the entry as stored
   */
  public InstrumentVerification recordVerification(InstrumentVerification v) {
    exec(
        "INSERT INTO weighing_instrument_verifications ("
            + VERIFICATION_COLUMNS
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, v.id());
          ps.setObject(2, v.tenantId());
          ps.setObject(3, v.instrumentId());
          ps.setString(4, v.kind());
          ps.setObject(5, v.performedOn());
          ps.setString(6, v.performedBy());
          ps.setString(7, v.certificateRef());
          ps.setBoolean(8, v.passed());
          ps.setObject(9, v.nextDue());
          ps.setString(10, v.notes());
          ps.setObject(11, v.recordedBy());
          ps.setObject(12, v.recordedAt().atOffset(ZoneOffset.UTC));
        },
        "record instrument verification");
    return v;
  }

  /**
   * An instrument's history, newest first. "Newest" is the date the work was done, then the moment
   * it was recorded, so a repair entered late still sits where it happened.
   *
   * @param tenantId owning tenant; the first condition
   * @param instrumentId the instrument
   * @return the entries
   */
  public List<InstrumentVerification> history(UUID tenantId, UUID instrumentId) {
    return query(
        "SELECT "
            + VERIFICATION_COLUMNS
            + " FROM weighing_instrument_verifications WHERE tenant_id=? AND instrument_id=?"
            + " ORDER BY performed_on DESC, recorded_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, instrumentId);
        },
        WeighingInstrumentRepository::mapVerification,
        "instrument history");
  }

  /**
   * The latest history entry for every instrument at a store, in one query, so a list of twenty
   * instruments does not cost twenty reads.
   *
   * @param tenantId owning tenant; the first condition
   * @param storeId the store
   * @return the latest entry per instrument
   */
  public List<InstrumentVerification> latestByStore(UUID tenantId, UUID storeId) {
    return query(
        "SELECT DISTINCT ON (v.instrument_id) "
            + VERIFICATION_COLUMNS
                .replace("id, tenant_id, instrument_id", "v.id, v.tenant_id, v.instrument_id")
                .replace(", kind", ", v.kind")
                .replace(", performed_on", ", v.performed_on")
                .replace(", performed_by", ", v.performed_by")
                .replace(", certificate_ref", ", v.certificate_ref")
                .replace(", passed", ", v.passed")
                .replace(", next_due", ", v.next_due")
                .replace(", notes", ", v.notes")
                .replace(", recorded_by", ", v.recorded_by")
                .replace(", recorded_at", ", v.recorded_at")
            + " FROM weighing_instrument_verifications v"
            + " JOIN weighing_instruments i ON i.id = v.instrument_id AND i.tenant_id = v.tenant_id"
            + " WHERE v.tenant_id=? AND i.store_id=?"
            + " ORDER BY v.instrument_id, v.performed_on DESC, v.recorded_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        WeighingInstrumentRepository::mapVerification,
        "latest verifications by store");
  }

  private static ApiException duplicate(ApiException e) {
    if (e.getCause() instanceof SQLException sqle && "23505".equals(sqle.getSQLState())) {
      return ApiException.conflict(
          "INSTRUMENT_DUPLICATE",
          "an instrument with this identifier exists at the store, or this serial number in the"
              + " business");
    }
    return e;
  }

  private static ApiException notFound() {
    return ApiException.notFound("INSTRUMENT_NOT_FOUND", "No such weighing instrument");
  }

  private static WeighingInstrument mapInstrument(ResultSet rs) throws SQLException {
    return new WeighingInstrument(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("identifier"),
        rs.getString("serial_number"),
        rs.getString("make"),
        rs.getString("model"),
        rs.getString("kind"),
        rs.getBigDecimal("max_capacity"),
        rs.getString("capacity_uom"),
        rs.getBigDecimal("scale_interval"),
        rs.getString("approval_ref"),
        rs.getObject("zone_id", UUID.class),
        rs.getString("label_scheme"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static InstrumentVerification mapVerification(ResultSet rs) throws SQLException {
    return new InstrumentVerification(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("instrument_id", UUID.class),
        rs.getString("kind"),
        rs.getObject("performed_on", LocalDate.class),
        rs.getString("performed_by"),
        rs.getString("certificate_ref"),
        rs.getBoolean("passed"),
        rs.getObject("next_due", LocalDate.class),
        rs.getString("notes"),
        rs.getObject("recorded_by", UUID.class),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
  }

  /** Exposed for the service's standing computation. */
  public static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }
}
