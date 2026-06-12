package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.SerialMovement;
import com.shelfj.inventory.domain.Domain.SerialNumber;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Serial number persistence. Extracted from InventoryRepository (self-contained section). */
@ApplicationScoped
public class SerialRepository extends BaseOutboxRepository {

  /** Bulk-insert serial numbers + initial genealogy movement + outbox, atomically. */
  public List<SerialNumber> registerSerials(List<SerialNumber> serials, OutboxRow event) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO serial_numbers"
                      + " (id, tenant_id, store_id, variant_id, batch_id, serial_no, status, received_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, serial_no) DO NOTHING")) {
            for (SerialNumber s : serials) {
              ps.setObject(1, s.id());
              ps.setObject(2, s.tenantId());
              ps.setObject(3, s.storeId());
              ps.setObject(4, s.variantId());
              ps.setObject(5, s.batchId());
              ps.setString(6, s.serialNo());
              ps.setString(7, s.status());
              ps.setObject(8, s.receivedAt().atOffset(ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO serial_movements"
                      + " (id, tenant_id, serial_id, from_status, to_status, ref_type)"
                      + " VALUES (?,?,?,NULL,?,?)")) {
            for (SerialNumber s : serials) {
              ps.setObject(1, UUID.randomUUID());
              ps.setObject(2, s.tenantId());
              ps.setObject(3, s.id());
              ps.setString(4, SerialNumber.IN_STOCK);
              ps.setString(5, "RECEIVE");
              ps.addBatch();
            }
            ps.executeBatch();
          }
          insertOutbox(c, event);
          return serials;
        },
        "register serials");
  }

  public List<SerialNumber> listSerials(
      UUID tenantId, UUID storeId, UUID variantId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (variantId != null) sb.append(" AND variant_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY received_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        SerialRepository::mapSerial,
        "list serials");
  }

  public Optional<SerialNumber> findSerial(UUID tenantId, UUID serialId) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, serialId);
            },
            SerialRepository::mapSerial,
            "get serial");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  public Optional<SerialNumber> findSerialByNo(UUID tenantId, String serialNo) {
    var list =
        query(
            "SELECT id, tenant_id, store_id, variant_id, batch_id, serial_no, status,"
                + " received_at, sold_at FROM serial_numbers WHERE tenant_id = ? AND serial_no = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, serialNo);
            },
            SerialRepository::mapSerial,
            "lookup serial by no");
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** Transition serial to a new status; records genealogy movement + outbox, atomically. */
  public Optional<SerialNumber> updateSerialStatus(
      UUID tenantId, UUID serialId, String newStatus, OutboxRow event) {
    return inTx(
        c -> {
          String oldStatus;
          try (var ps =
              c.prepareStatement(
                  "SELECT status FROM serial_numbers"
                      + " WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, serialId);
            try (var rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<SerialNumber>empty();
              oldStatus = rs.getString("status");
            }
          }
          SerialNumber updated;
          try (var ps =
              c.prepareStatement(
                  "UPDATE serial_numbers"
                      + " SET status = ?,"
                      + " sold_at = CASE WHEN ? = 'SOLD' THEN now() ELSE sold_at END"
                      + " WHERE tenant_id = ? AND id = ?"
                      + " RETURNING id, tenant_id, store_id, variant_id, batch_id,"
                      + " serial_no, status, received_at, sold_at")) {
            ps.setString(1, newStatus);
            ps.setString(2, newStatus);
            ps.setObject(3, tenantId);
            ps.setObject(4, serialId);
            try (var rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<SerialNumber>empty();
              updated = mapSerial(rs);
            }
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO serial_movements"
                      + " (id, tenant_id, serial_id, from_status, to_status)"
                      + " VALUES (?,?,?,?,?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setObject(3, serialId);
            ps.setString(4, oldStatus);
            ps.setString(5, newStatus);
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return Optional.of(updated);
        },
        "update serial status");
  }

  public List<SerialMovement> listSerialHistory(UUID tenantId, UUID serialId) {
    return query(
        "SELECT id, tenant_id, serial_id, from_status, to_status, ref_type, ref_id, created_at"
            + " FROM serial_movements WHERE tenant_id = ? AND serial_id = ?"
            + " ORDER BY created_at ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, serialId);
        },
        SerialRepository::mapSerialMovement,
        "list serial history");
  }

  private static SerialNumber mapSerial(ResultSet rs) throws SQLException {
    OffsetDateTime soldOdt = rs.getObject("sold_at", OffsetDateTime.class);
    return new SerialNumber(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("batch_id", UUID.class),
        rs.getString("serial_no"),
        rs.getString("status"),
        rs.getObject("received_at", OffsetDateTime.class).toInstant(),
        soldOdt == null ? null : soldOdt.toInstant());
  }

  private static SerialMovement mapSerialMovement(ResultSet rs) throws SQLException {
    return new SerialMovement(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("serial_id", UUID.class),
        rs.getString("from_status"),
        rs.getString("to_status"),
        rs.getString("ref_type"),
        rs.getObject("ref_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
