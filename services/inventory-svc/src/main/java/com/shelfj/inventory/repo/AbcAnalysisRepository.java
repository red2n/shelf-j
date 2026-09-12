package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.AbcAssignment;
import com.shelfj.inventory.domain.Domain.AbcCompileRun;
import com.shelfj.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ABC analysis (Gap #9): classification runs and per-variant assignments. Extracted from {@code
 * InventoryRepository}: self-contained, no outbox events, no coupling to any other aggregate's Java
 * code (its scoring query reads {@code demand_history}/{@code inventory_batches} directly by SQL,
 * not via another repo's methods).
 */
@ApplicationScoped
public class AbcAnalysisRepository extends BaseJdbcRepository {

  /** Insert a compile run header and bulk-upsert all assignments in one transaction. */
  public AbcCompileRun persistAbcRun(AbcCompileRun run, List<AbcAssignment> assignments) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO abc_compile_runs"
                      + " (id, tenant_id, store_id, criteria, threshold_a, threshold_ab,"
                      + "  items_compiled, compiled_at)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, run.id());
            ps.setObject(2, run.tenantId());
            ps.setObject(3, run.storeId());
            ps.setString(4, run.criteria());
            ps.setBigDecimal(5, run.thresholdA());
            ps.setBigDecimal(6, run.thresholdAB());
            ps.setInt(7, run.itemsCompiled());
            ps.setObject(8, run.compiledAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          if (!assignments.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO abc_assignments"
                        + " (id, tenant_id, store_id, variant_id, run_id, class, score, rank)"
                        + " VALUES (?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (tenant_id, store_id, variant_id)"
                        + " DO UPDATE SET run_id = EXCLUDED.run_id,"
                        + "   class = EXCLUDED.class,"
                        + "   score = EXCLUDED.score,"
                        + "   rank  = EXCLUDED.rank,"
                        + "   assigned_at = now()")) {
              for (AbcAssignment a : assignments) {
                ps.setObject(1, a.id());
                ps.setObject(2, a.tenantId());
                ps.setObject(3, a.storeId());
                ps.setObject(4, a.variantId());
                ps.setObject(5, a.runId());
                ps.setString(6, a.abcClass());
                ps.setBigDecimal(7, a.score());
                ps.setInt(8, a.rank());
                ps.addBatch();
              }
              ps.executeBatch();
            }
          }
          return run;
        },
        "persist abc run");
  }

  /**
   * Lists the tenant's abc assignments.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param abcClass the abc class
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<AbcAssignment> listAbcAssignments(
      UUID tenantId, UUID storeId, String abcClass, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, run_id, class, score, rank, assigned_at"
                + " FROM abc_assignments WHERE tenant_id = ?");
    if (storeId != null) sb.append(" AND store_id = ?");
    if (abcClass != null) sb.append(" AND class = ?");
    sb.append(" ORDER BY store_id, rank ASC LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (storeId != null) ps.setObject(i++, storeId);
          if (abcClass != null) ps.setString(i++, abcClass);
          ps.setInt(i, limit);
        },
        AbcAnalysisRepository::mapAbcAssignment,
        "list abc assignments");
  }

  /**
   * Looks an abc assignment up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param variantId the product variant concerned
   * @return the abc assignment, or empty when it does not exist in this tenant
   */
  public Optional<AbcAssignment> findAbcAssignment(UUID tenantId, UUID storeId, UUID variantId) {
    List<AbcAssignment> rows =
        query(
            "SELECT id, tenant_id, store_id, variant_id, run_id, class, score, rank, assigned_at"
                + " FROM abc_assignments WHERE tenant_id = ? AND store_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            AbcAnalysisRepository::mapAbcAssignment,
            "find abc assignment");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Fetch the data needed for ABC scoring: variant_id, total demand qty, and avg cost price. Joins
   * demand_history (DAY buckets, last 365 days) with the latest cost_price from batches. Returns
   * one row per (store, variant) pair.
   */
  public List<Object[]> abcScoringData(UUID tenantId, UUID storeId) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT dh.store_id, dh.variant_id,"
                + " COALESCE(SUM(dh.demand_qty), 0) AS total_demand,"
                + " COALESCE(AVG(b.cost_price), 1)  AS avg_cost"
                + " FROM demand_history dh"
                + " LEFT JOIN inventory_batches b"
                + "   ON b.tenant_id = dh.tenant_id AND b.store_id = dh.store_id"
                + "   AND b.variant_id = dh.variant_id AND b.cost_price IS NOT NULL"
                + " WHERE dh.tenant_id = ? AND dh.bucket_type = 'DAY'"
                + "   AND dh.bucket_date >= CURRENT_DATE - INTERVAL '365 days'");
    if (storeId != null) sb.append(" AND dh.store_id = ?");
    sb.append(" GROUP BY dh.store_id, dh.variant_id");
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sb.toString())) {
      ps.setObject(1, tenantId);
      if (storeId != null) ps.setObject(2, storeId);
      List<Object[]> rows = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new Object[] {
                rs.getObject("store_id", UUID.class),
                rs.getObject("variant_id", UUID.class),
                rs.getBigDecimal("total_demand"),
                rs.getBigDecimal("avg_cost")
              });
        }
      }
      return rows;
    } catch (SQLException e) {
      throw dbError("abc scoring data", e);
    }
  }

  private static AbcAssignment mapAbcAssignment(ResultSet rs) throws SQLException {
    return new AbcAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getString("class"),
        rs.getBigDecimal("score"),
        rs.getInt("rank"),
        rs.getObject("assigned_at", OffsetDateTime.class).toInstant());
  }
}
