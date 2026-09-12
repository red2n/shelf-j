package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.CycleCountHeader;
import com.shelfj.inventory.domain.Domain.CycleCountLine;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cycle counting (Gap #10): headers, lines, and the on-hand lookups used to build them. Extracted
 * from {@code InventoryRepository}. {@code applyAdjustments} stayed behind in the core repository
 * because it calls the shared FIFO/batch-mutation internals ({@code deductFifo}, {@code
 * insertBatch}, {@code insertMovement}) that the receive/adjust/consume hot path also uses — it
 * isn't safely separable without duplicating or exposing that locking-sensitive logic. {@link
 * #mapCycleCountLine} is duplicated in the core repo for that method's own use.
 */
@ApplicationScoped
public class CycleCountRepository extends BaseJdbcRepository {

  /**
   * Inserts a cycle count header.
   *
   * @param header the count header to persist
   * @param lines the lines to store
   * @return the cycle count header as stored
   */
  public CycleCountHeader createCycleCountHeader(
      CycleCountHeader header, List<CycleCountLine> lines) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO cycle_count_headers"
                      + " (id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                      + "  status, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, header.id());
            ps.setObject(2, header.tenantId());
            ps.setObject(3, header.storeId());
            ps.setString(4, header.name());
            ps.setString(5, header.abcClasses());
            ps.setBigDecimal(6, header.tolerancePct());
            ps.setString(7, header.status());
            ps.setObject(8, header.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          if (!lines.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO cycle_count_lines"
                        + " (id, tenant_id, header_id, store_id, variant_id, system_qty)"
                        + " VALUES (?,?,?,?,?,?)")) {
              for (CycleCountLine l : lines) {
                ps.setObject(1, l.id());
                ps.setObject(2, l.tenantId());
                ps.setObject(3, l.headerId());
                ps.setObject(4, l.storeId());
                ps.setObject(5, l.variantId());
                ps.setBigDecimal(6, l.systemQty());
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return header;
        },
        "create cycle count");
  }

  /**
   * Lists the tenant's cycle count headers.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param status the status to set
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<CycleCountHeader> listCycleCountHeaders(
      UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                + " status, created_at, completed_at"
                + " FROM cycle_count_headers WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (status != null) sb.append(" AND status = ?");
    sb.append(" ORDER BY created_at DESC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (status != null) ps.setString(i++, status);
          ps.setInt(i, limit);
        },
        CycleCountRepository::mapCycleCountHeader,
        "list cycle count headers");
  }

  /**
   * Looks a cycle count header up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param headerId the header id
   * @return the cycle count header, or empty when it does not exist in this tenant
   */
  public Optional<CycleCountHeader> findCycleCountHeader(UUID tenantId, UUID headerId) {
    List<CycleCountHeader> rows =
        query(
            "SELECT id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                + " status, created_at, completed_at"
                + " FROM cycle_count_headers WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, headerId);
            },
            CycleCountRepository::mapCycleCountHeader,
            "find cycle count header");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists the tenant's cycle count lines.
   *
   * @param headerId the header id
   * @return the matching rows
   */
  public List<CycleCountLine> listCycleCountLines(UUID headerId) {
    return query(
        "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
            + " counted_qty, variance, variance_pct, status, counted_at"
            + " FROM cycle_count_lines WHERE header_id = ? ORDER BY variant_id",
        ps -> ps.setObject(1, headerId),
        CycleCountRepository::mapCycleCountLine,
        "list cycle count lines");
  }

  /**
   * Looks a cycle count line up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param lineId the line id
   * @return the cycle count line, or empty when it does not exist in this tenant
   */
  public Optional<CycleCountLine> findCycleCountLine(UUID tenantId, UUID lineId) {
    List<CycleCountLine> rows =
        query(
            "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
                + " counted_qty, variance, variance_pct, status, counted_at"
                + " FROM cycle_count_lines WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, lineId);
            },
            CycleCountRepository::mapCycleCountLine,
            "find cycle count line");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Record counted_qty + computed variance on one line; set status COUNTED. */
  public Optional<CycleCountLine> enterCount(
      UUID tenantId,
      UUID lineId,
      BigDecimal countedQty,
      BigDecimal variance,
      BigDecimal variancePct) {
    List<CycleCountLine> rows =
        query(
            "UPDATE cycle_count_lines"
                + " SET counted_qty = ?, variance = ?, variance_pct = ?,"
                + "     status = 'COUNTED', counted_at = now()"
                + " WHERE tenant_id = ? AND id = ? AND status IN ('OPEN','COUNTED')"
                + " RETURNING id, tenant_id, header_id, store_id, variant_id, system_qty,"
                + "   counted_qty, variance, variance_pct, status, counted_at",
            ps -> {
              ps.setBigDecimal(1, countedQty);
              ps.setBigDecimal(2, variance);
              ps.setBigDecimal(3, variancePct);
              ps.setObject(4, tenantId);
              ps.setObject(5, lineId);
            },
            CycleCountRepository::mapCycleCountLine,
            "enter count");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Bulk-set status on lines; returns count updated. */
  public int bulkUpdateLineStatus(UUID headerId, List<UUID> lineIds, String newStatus) {
    if (lineIds.isEmpty()) return 0;
    try (var c = dataSource.getConnection()) {
      StringBuilder sb =
          new StringBuilder(
              "UPDATE cycle_count_lines SET status = ? WHERE header_id = ? AND id = ANY(?)");
      try (var ps = c.prepareStatement(sb.toString())) {
        ps.setString(1, newStatus);
        ps.setObject(2, headerId);
        ps.setArray(3, c.createArrayOf("uuid", lineIds.toArray()));
        return ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw dbError("bulk update line status", e);
    }
  }

  /** Update header status. Returns updated header or empty if not found. */
  public Optional<CycleCountHeader> updateHeaderStatus(
      UUID tenantId, UUID headerId, String newStatus) {
    List<CycleCountHeader> rows =
        query(
            "UPDATE cycle_count_headers SET status = ?,"
                + " completed_at = CASE WHEN ? IN ('ADJUSTED','CLOSED') THEN now()"
                + "                     ELSE completed_at END"
                + " WHERE tenant_id = ? AND id = ?"
                + " RETURNING id, tenant_id, store_id, name, abc_classes, tolerance_pct,"
                + "   status, created_at, completed_at",
            ps -> {
              ps.setString(1, newStatus);
              ps.setString(2, newStatus);
              ps.setObject(3, tenantId);
              ps.setObject(4, headerId);
            },
            CycleCountRepository::mapCycleCountHeader,
            "update header status");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /** Returns on-hand available qty for a (store, variant). Used when generating lines. */
  public BigDecimal onHandQty(UUID tenantId, UUID storeId, UUID variantId) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT COALESCE(SUM(remaining_qty),0) AS q"
                    + " FROM inventory_batches"
                    + " WHERE tenant_id=? AND store_id=? AND variant_id=?"
                    + " AND material_status='AVAILABLE'")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setObject(3, variantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal("q") : BigDecimal.ZERO;
      }
    } catch (SQLException e) {
      throw dbError("on-hand qty", e);
    }
  }

  /**
   * Bulk on-hand query for a set of variants in one store — avoids N+1 when building cycle count
   * lines.
   */
  public Map<UUID, BigDecimal> onHandQtyBatch(
      UUID tenantId, UUID storeId, Collection<UUID> variantIds) {
    if (variantIds.isEmpty()) return Map.of();
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT variant_id, COALESCE(SUM(remaining_qty),0) AS q"
                    + " FROM inventory_batches"
                    + " WHERE tenant_id=? AND store_id=? AND variant_id=ANY(?)"
                    + " AND material_status='AVAILABLE'"
                    + " GROUP BY variant_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, storeId);
      ps.setArray(3, c.createArrayOf("uuid", variantIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        while (rs.next()) {
          result.put(rs.getObject("variant_id", UUID.class), rs.getBigDecimal("q"));
        }
        return result;
      }
    } catch (SQLException e) {
      throw dbError("on-hand qty batch", e);
    }
  }

  /** Bulk fetch of cycle count lines for multiple headers — avoids N+1 in listCycleCounts. */
  public Map<UUID, List<CycleCountLine>> listCycleCountLinesByHeaders(Collection<UUID> headerIds) {
    if (headerIds.isEmpty()) return Map.of();
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT id, tenant_id, header_id, store_id, variant_id, system_qty,"
                    + " counted_qty, variance, variance_pct, status, counted_at"
                    + " FROM cycle_count_lines WHERE header_id=ANY(?) ORDER BY variant_id")) {
      ps.setArray(1, c.createArrayOf("uuid", headerIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        Map<UUID, List<CycleCountLine>> result = new HashMap<>();
        while (rs.next()) {
          CycleCountLine line = mapCycleCountLine(rs);
          result.computeIfAbsent(line.headerId(), k -> new java.util.ArrayList<>()).add(line);
        }
        return result;
      }
    } catch (SQLException e) {
      throw dbError("list cycle count lines by headers", e);
    }
  }

  private static CycleCountHeader mapCycleCountHeader(ResultSet rs) throws SQLException {
    OffsetDateTime completedOdt = rs.getObject("completed_at", OffsetDateTime.class);
    return new CycleCountHeader(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("name"),
        rs.getString("abc_classes"),
        rs.getBigDecimal("tolerance_pct"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        completedOdt == null ? null : completedOdt.toInstant());
  }

  static CycleCountLine mapCycleCountLine(ResultSet rs) throws SQLException {
    OffsetDateTime countedOdt = rs.getObject("counted_at", OffsetDateTime.class);
    return new CycleCountLine(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("header_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("system_qty"),
        rs.getBigDecimal("counted_qty"),
        rs.getBigDecimal("variance"),
        rs.getBigDecimal("variance_pct"),
        rs.getString("status"),
        countedOdt == null ? null : countedOdt.toInstant());
  }
}
