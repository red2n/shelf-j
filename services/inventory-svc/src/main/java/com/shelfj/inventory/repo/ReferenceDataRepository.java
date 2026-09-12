package com.shelfj.inventory.repo;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.Domain.ReasonCode;
import com.shelfj.inventory.domain.Domain.TransactionSourceType;
import com.shelfj.inventory.domain.Domain.ZoneGlMapping;
import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped reference / configuration lookup tables: transaction reason codes, transaction
 * source types, and zone→GL nominal-code mappings. Extracted from {@code InventoryRepository}
 * (which had grown past 3,900 lines): these are self-contained CRUD over lookup tables — no stock
 * movements, no outbox, no shared FIFO/reservation internals — so they live cleanly on their own
 * and only use the JDBC infra inherited from {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class ReferenceDataRepository extends BaseJdbcRepository {

  // ── Transaction reason codes ──────────────────────────────────────────────

  public ReasonCode insertReasonCode(UUID tenantId, String code, String description) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO transaction_reason_codes (id,tenant_id,code,description)"
                      + " VALUES (?,?,?,?)"
                      + " RETURNING id,tenant_id,code,description,active,created_at")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setString(3, code);
            ps.setString(4, description);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapReasonCode(rs);
            }
          }
        },
        "insert reason code");
  }

  public List<ReasonCode> listReasonCodes(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,code,description,active,created_at"
            + " FROM transaction_reason_codes"
            + " WHERE tenant_id=? OR tenant_id IS NULL"
            + " ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        ReferenceDataRepository::mapReasonCode,
        "list reason codes");
  }

  public ReasonCode setReasonCodeActive(UUID tenantId, UUID id, boolean active) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transaction_reason_codes SET active=? WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,code,description,active,created_at")) {
            ps.setBoolean(1, active);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("REASON_CODE_NOT_FOUND", "No such reason code");
              return mapReasonCode(rs);
            }
          }
        },
        "toggle reason code");
  }

  private static ReasonCode mapReasonCode(ResultSet rs) throws SQLException {
    return new ReasonCode(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("description"),
        rs.getBoolean("active"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Transaction source types ──────────────────────────────────────────────

  public TransactionSourceType insertSourceType(UUID tenantId, String code, String description) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO transaction_source_types (id,tenant_id,code,description)"
                      + " VALUES (?,?,?,?)"
                      + " RETURNING id,tenant_id,code,description,active,created_at")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setString(3, code);
            ps.setString(4, description);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapSourceType(rs);
            }
          }
        },
        "insert source type");
  }

  public List<TransactionSourceType> listSourceTypes(UUID tenantId) {
    return query(
        "SELECT id,tenant_id,code,description,active,created_at"
            + " FROM transaction_source_types"
            + " WHERE tenant_id=? OR tenant_id IS NULL"
            + " ORDER BY code",
        ps -> ps.setObject(1, tenantId),
        ReferenceDataRepository::mapSourceType,
        "list source types");
  }

  public TransactionSourceType setSourceTypeActive(UUID tenantId, UUID id, boolean active) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE transaction_source_types SET active=? WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,code,description,active,created_at")) {
            ps.setBoolean(1, active);
            ps.setObject(2, tenantId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("SOURCE_TYPE_NOT_FOUND", "No such source type");
              return mapSourceType(rs);
            }
          }
        },
        "toggle source type");
  }

  private static TransactionSourceType mapSourceType(ResultSet rs) throws SQLException {
    return new TransactionSourceType(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("description"),
        rs.getBoolean("active"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Zone → GL nominal-code mappings ───────────────────────────────────────

  public ZoneGlMapping upsertZoneGlMapping(
      UUID tenantId, UUID storeId, UUID zoneId, String nominalCode, String description) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO zone_gl_mappings"
                      + " (id,tenant_id,store_id,zone_id,nominal_code,description)"
                      + " VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id,store_id,zone_id)"
                      + " DO UPDATE SET nominal_code=EXCLUDED.nominal_code,"
                      + " description=EXCLUDED.description, updated_at=now()"
                      + " RETURNING id,tenant_id,store_id,zone_id,nominal_code,"
                      + "description,created_at,updated_at")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, storeId);
            ps.setObject(4, zoneId);
            ps.setString(5, nominalCode);
            ps.setString(6, description);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              return mapZoneGlMapping(rs);
            }
          }
        },
        "upsert zone gl mapping");
  }

  public List<ZoneGlMapping> listZoneGlMappings(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id,tenant_id,store_id,zone_id,nominal_code,description,created_at,updated_at"
            + " FROM zone_gl_mappings WHERE tenant_id=? AND store_id=?"
            + " ORDER BY zone_id NULLS FIRST",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        ReferenceDataRepository::mapZoneGlMapping,
        "list zone gl mappings");
  }

  private static ZoneGlMapping mapZoneGlMapping(ResultSet rs) throws SQLException {
    return new ZoneGlMapping(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getString("nominal_code"),
        rs.getString("description"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}
