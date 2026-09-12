package com.shelfj.inventory.repo;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.Domain.PhysicalInventory;
import com.shelfj.inventory.domain.Domain.PhysicalInventoryTag;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Physical inventory counts (Gap #16). Extracted from {@code InventoryRepository}: self-contained —
 * {@code completePhysicalInventory} writes {@code stock_movements} directly by SQL rather than
 * through the core repo's shared internals, so it doesn't have that coupling.
 */
@ApplicationScoped
public class PhysicalInventoryRepository extends BaseOutboxRepository {

  /**
   * Inserts a physical inventory.
   *
   * @param pi the physical to persist
   * @param event the outbox row to commit alongside the write
   * @return the physical inventory as stored
   */
  public PhysicalInventory createPhysicalInventory(PhysicalInventory pi, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO physical_inventories (id, tenant_id, store_id, status, notes)"
                  + " VALUES (?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, pi.id());
            ps.setObject(2, pi.tenantId());
            ps.setObject(3, pi.storeId());
            ps.setString(4, pi.status());
            ps.setString(5, pi.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw dbError("create physical inventory", new SQLException());
              PhysicalInventory saved = mapPhysicalInventory(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create physical inventory");
  }

  /**
   * Looks a physical inventory up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the physical inventory to act on
   * @return the physical inventory, or empty when it does not exist in this tenant
   */
  public Optional<PhysicalInventory> findPhysicalInventory(UUID tenantId, UUID id) {
    var rows =
        query(
            "SELECT id, tenant_id, store_id, status, notes, started_at, completed_at"
                + " FROM physical_inventories WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PhysicalInventoryRepository::mapPhysicalInventory,
            "find physical inventory");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Lists the tenant's physical inventories.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @return the matching rows
   */
  public List<PhysicalInventory> listPhysicalInventories(UUID tenantId, UUID storeId) {
    return query(
        "SELECT id, tenant_id, store_id, status, notes, started_at, completed_at"
            + " FROM physical_inventories WHERE tenant_id=?"
            + (storeId != null ? " AND store_id=?" : "")
            + " ORDER BY started_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          if (storeId != null) ps.setObject(2, storeId);
        },
        PhysicalInventoryRepository::mapPhysicalInventory,
        "list physical inventories");
  }

  /**
   * Lists the tenant's tags.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param physicalInventoryId the physical inventory id
   * @return the matching rows
   */
  public List<PhysicalInventoryTag> listTags(UUID tenantId, UUID physicalInventoryId) {
    return query(
        "SELECT id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty,"
            + " counted_qty, adjustment_qty, status, counted_at"
            + " FROM physical_inventory_tags WHERE tenant_id=? AND physical_inventory_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, physicalInventoryId);
        },
        PhysicalInventoryRepository::mapTag,
        "list physical inventory tags");
  }

  /**
   * Adds a count tag to a physical inventory, snapshotting the system quantity to count against.
   *
   * @param tag the tag to persist; its {@code id} must already be a UUIDv7
   * @return the tag as stored
   */
  public PhysicalInventoryTag addTag(PhysicalInventoryTag tag) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO physical_inventory_tags"
                  + " (id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty)"
                  + " VALUES (?,?,?,?,?,?) RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tag.id());
            ps.setObject(2, tag.tenantId());
            ps.setObject(3, tag.physicalInventoryId());
            ps.setObject(4, tag.variantId());
            ps.setObject(5, tag.zoneId());
            ps.setBigDecimal(6, tag.systemQty());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) throw dbError("add pi tag", new SQLException());
              return mapTag(rs);
            }
          }
        },
        "add physical inventory tag");
  }

  /**
   * Records the counted quantity on one tag and marks it COUNTED.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param physicalInventoryId the count the tag belongs to, also matched
   * @param tagId the tag being counted
   * @param countedQty the quantity actually found
   * @return the tag with its recorded count
   */
  public PhysicalInventoryTag countTag(
      UUID tenantId, UUID physicalInventoryId, UUID tagId, BigDecimal countedQty) {
    return inTx(
        c -> {
          String sql =
              "UPDATE physical_inventory_tags SET counted_qty=?, status='COUNTED', counted_at=now()"
                  + " WHERE tenant_id=? AND physical_inventory_id=? AND id=? RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, countedQty);
            ps.setObject(2, tenantId);
            ps.setObject(3, physicalInventoryId);
            ps.setObject(4, tagId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("TAG_NOT_FOUND", "Physical inventory tag not found");
              return mapTag(rs);
            }
          }
        },
        "count pi tag");
  }

  /**
   * Completes a physical inventory, posting each tag's variance and writing the event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param piId the physical inventory to complete
   * @param event the outbox row to commit alongside
   * @return the completed physical inventory
   */
  public PhysicalInventory completePhysicalInventory(UUID tenantId, UUID piId, OutboxRow event) {
    return inTx(
        c -> {
          // Create stock_movement for each COUNTED tag where adjustment != 0
          String tagSql =
              "SELECT id, tenant_id, physical_inventory_id, variant_id, zone_id, system_qty,"
                  + " counted_qty, adjustment_qty, status, counted_at"
                  + " FROM physical_inventory_tags"
                  + " WHERE tenant_id=? AND physical_inventory_id=?"
                  + " AND status='COUNTED' AND counted_qty IS NOT NULL"
                  + " AND counted_qty <> system_qty";
          try (PreparedStatement ps = c.prepareStatement(tagSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                PhysicalInventoryTag tag = mapTag(rs);
                BigDecimal adj = tag.adjustmentQty();
                String moveType = adj.compareTo(BigDecimal.ZERO) > 0 ? "RECEIVE" : "ISSUE";
                UUID movId = Ids.newId();
                try (PreparedStatement mps =
                    c.prepareStatement(
                        "INSERT INTO stock_movements"
                            + " (id, tenant_id, store_id, variant_id, qty, type, ref_type, ref_id)"
                            + " SELECT ?,?,store_id,?,?,?,?,?"
                            + " FROM physical_inventories WHERE id=?")) {
                  mps.setObject(1, movId);
                  mps.setObject(2, tenantId);
                  mps.setObject(3, tag.variantId());
                  mps.setBigDecimal(4, adj.abs());
                  mps.setString(5, moveType);
                  mps.setString(6, "PHYSICAL_INVENTORY");
                  mps.setObject(7, piId);
                  mps.setObject(8, piId);
                  mps.executeUpdate();
                }
              }
            }
          }
          // Mark all COUNTED tags as ADJUSTED
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE physical_inventory_tags SET status='ADJUSTED'"
                      + " WHERE tenant_id=? AND physical_inventory_id=? AND status='COUNTED'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            ps.executeUpdate();
          }
          // Mark header COMPLETED
          String doneSql =
              "UPDATE physical_inventories SET status='COMPLETED', completed_at=now()"
                  + " WHERE tenant_id=? AND id=? AND status<>'COMPLETED' RETURNING *";
          try (PreparedStatement ps = c.prepareStatement(doneSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, piId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw new ApiException(
                    409,
                    "PI_ALREADY_COMPLETED",
                    "Physical inventory already completed",
                    List.of(),
                    null);
              PhysicalInventory done = mapPhysicalInventory(rs);
              insertOutbox(c, event);
              return done;
            }
          }
        },
        "complete physical inventory");
  }

  private static PhysicalInventory mapPhysicalInventory(ResultSet rs) throws SQLException {
    OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
    return new PhysicalInventory(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("status"),
        rs.getString("notes"),
        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
        completed == null ? null : completed.toInstant());
  }

  private static PhysicalInventoryTag mapTag(ResultSet rs) throws SQLException {
    OffsetDateTime countedAt = rs.getObject("counted_at", OffsetDateTime.class);
    return new PhysicalInventoryTag(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("physical_inventory_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getBigDecimal("system_qty"),
        rs.getBigDecimal("counted_qty"),
        rs.getBigDecimal("adjustment_qty"),
        rs.getString("status"),
        countedAt == null ? null : countedAt.toInstant());
  }
}
