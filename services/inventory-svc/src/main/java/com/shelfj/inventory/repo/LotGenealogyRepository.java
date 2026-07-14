package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.LotGenealogyLink;
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
 * Lot genealogy links (Gap #11). Extracted from {@code InventoryRepository}: self-contained, no
 * outbox events, no coupling to any other aggregate, so it only needs the JDBC infra inherited from
 * {@link BaseJdbcRepository}.
 */
@ApplicationScoped
public class LotGenealogyRepository extends BaseJdbcRepository {

  public LotGenealogyLink createLotLink(LotGenealogyLink link) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO lot_genealogy"
                  + " (id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes)"
                  + " VALUES (?,?,?,?,?,?,?)"
                  + " ON CONFLICT (tenant_id, parent_batch_id, child_batch_id) DO NOTHING"
                  + " RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, link.id());
            ps.setObject(2, link.tenantId());
            ps.setObject(3, link.parentBatchId());
            ps.setObject(4, link.childBatchId());
            ps.setBigDecimal(5, link.qty());
            ps.setString(6, link.relationType());
            ps.setString(7, link.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409, "LOT_LINK_EXISTS", "Genealogy link already exists", List.of(), null);
              return mapLotLink(rs);
            }
          }
        },
        "create lot link");
  }

  public List<LotGenealogyLink> findAncestors(UUID tenantId, UUID batchId) {
    String sql =
        "WITH RECURSIVE anc(id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + " relation_type, notes, created_at) AS ("
            + "  SELECT id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes, created_at"
            + "  FROM lot_genealogy WHERE tenant_id=? AND child_batch_id=?"
            + "  UNION ALL"
            + "  SELECT g.* FROM lot_genealogy g JOIN anc ON g.tenant_id=anc.tenant_id"
            + "   AND g.child_batch_id=anc.parent_batch_id"
            + ") CYCLE parent_batch_id SET is_cycle USING path"
            + " SELECT id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + "  relation_type, notes, created_at FROM anc WHERE NOT is_cycle";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
        },
        LotGenealogyRepository::mapLotLink,
        "find ancestors");
  }

  public List<LotGenealogyLink> findDescendants(UUID tenantId, UUID batchId) {
    String sql =
        "WITH RECURSIVE des(id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + " relation_type, notes, created_at) AS ("
            + "  SELECT id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes, created_at"
            + "  FROM lot_genealogy WHERE tenant_id=? AND parent_batch_id=?"
            + "  UNION ALL"
            + "  SELECT g.* FROM lot_genealogy g JOIN des ON g.tenant_id=des.tenant_id"
            + "   AND g.parent_batch_id=des.child_batch_id"
            + ") CYCLE child_batch_id SET is_cycle USING path"
            + " SELECT id, tenant_id, parent_batch_id, child_batch_id, qty,"
            + "  relation_type, notes, created_at FROM des WHERE NOT is_cycle";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
        },
        LotGenealogyRepository::mapLotLink,
        "find descendants");
  }

  public List<LotGenealogyLink> findDirectLinks(UUID tenantId, UUID batchId) {
    String sql =
        "SELECT id, tenant_id, parent_batch_id, child_batch_id, qty, relation_type, notes, created_at"
            + " FROM lot_genealogy"
            + " WHERE tenant_id=? AND (parent_batch_id=? OR child_batch_id=?)"
            + " ORDER BY created_at";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, batchId);
          ps.setObject(3, batchId);
        },
        LotGenealogyRepository::mapLotLink,
        "find direct links");
  }

  private static LotGenealogyLink mapLotLink(ResultSet rs) throws SQLException {
    return new LotGenealogyLink(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("parent_batch_id", UUID.class),
        rs.getObject("child_batch_id", UUID.class),
        rs.getBigDecimal("qty"),
        rs.getString("relation_type"),
        rs.getString("notes"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
