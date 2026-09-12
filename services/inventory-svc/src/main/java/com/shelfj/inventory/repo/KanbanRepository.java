package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.KanbanCard;
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
 * Kanban replenishment cards (Gap #18), including their order-modifier fields (min/max qty, lot
 * multiplier) — those live on the same {@code kanban_cards} row. Extracted from {@code
 * InventoryRepository}: self-contained, no coupling to the receive/adjust/consume hot path.
 */
@ApplicationScoped
public class KanbanRepository extends BaseOutboxRepository {

  /**
   * Inserts a kanban card.
   *
   * @param card the kanban card to persist
   * @param event the outbox row to commit alongside the write
   * @return the kanban card as stored
   */
  public KanbanCard createKanbanCard(KanbanCard card, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "INSERT INTO kanban_cards (id, tenant_id, store_id, variant_id, kanban_type,"
                  + " status, reorder_qty, source_store_id, supplier_ref, notes)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                  + " RETURNING id, tenant_id, store_id, variant_id, kanban_type, status,"
                  + "   reorder_qty, source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                  + "   lot_multiplier, created_at, triggered_at, replenished_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, card.id());
            ps.setObject(2, card.tenantId());
            ps.setObject(3, card.storeId());
            ps.setObject(4, card.variantId());
            ps.setString(5, card.kanbanType());
            ps.setString(6, card.status());
            ps.setBigDecimal(7, card.reorderQty());
            ps.setObject(8, card.sourceStoreId());
            ps.setString(9, card.supplierRef());
            ps.setString(10, card.notes());
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.unprocessable(
                    "KANBAN_CREATE_ERROR", "kanban card creation failed");
              KanbanCard saved = mapKanbanCard(rs);
              insertOutbox(c, event);
              return saved;
            }
          }
        },
        "create kanban card");
  }

  /**
   * Marks a kanban card TRIGGERED and writes its event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cardId the card to trigger
   * @param notes free-text note recorded against the trigger
   * @param event the outbox row to commit alongside
   * @return the card in its triggered state
   */
  public KanbanCard triggerKanbanCard(UUID tenantId, UUID cardId, String notes, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE kanban_cards SET status='TRIGGERED', triggered_at=now(),"
                  + " notes=COALESCE(?,notes)"
                  + " WHERE tenant_id=? AND id=? AND status='EMPTY'"
                  + " RETURNING id, tenant_id, store_id, variant_id, kanban_type, status,"
                  + "   reorder_qty, source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                  + "   lot_multiplier, created_at, triggered_at, replenished_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, notes);
            ps.setObject(2, tenantId);
            ps.setObject(3, cardId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.conflict(
                    "KANBAN_NOT_EMPTY", "card not found or not in EMPTY status");
              KanbanCard updated = mapKanbanCard(rs);
              insertOutbox(c, event);
              return updated;
            }
          }
        },
        "trigger kanban card");
  }

  /**
   * Marks a triggered kanban card refilled and writes its event — atomically.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cardId the card to replenish
   * @param event the outbox row to commit alongside
   * @return the card back in its filled state
   */
  public KanbanCard replenishKanbanCard(UUID tenantId, UUID cardId, OutboxRow event) {
    return inTx(
        c -> {
          String sql =
              "UPDATE kanban_cards SET status='REPLENISHED', replenished_at=now()"
                  + " WHERE tenant_id=? AND id=? AND status IN ('TRIGGERED','IN_PROGRESS')"
                  + " RETURNING id, tenant_id, store_id, variant_id, kanban_type, status,"
                  + "   reorder_qty, source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                  + "   lot_multiplier, created_at, triggered_at, replenished_at";
          try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, cardId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.conflict(
                    "KANBAN_NOT_TRIGGERED", "card not found or not triggered");
              KanbanCard updated = mapKanbanCard(rs);
              insertOutbox(c, event);
              return updated;
            }
          }
        },
        "replenish kanban card");
  }

  /**
   * Looks a kanban card up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param cardId the card id
   * @return the kanban card, or empty when it does not exist in this tenant
   */
  public Optional<KanbanCard> findKanbanCard(UUID tenantId, UUID cardId) {
    return query(
            "SELECT id, tenant_id, store_id, variant_id, kanban_type, status, reorder_qty,"
                + " source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
                + " lot_multiplier, created_at, triggered_at, replenished_at"
                + " FROM kanban_cards WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, cardId);
            },
            KanbanRepository::mapKanbanCard,
            "find kanban card")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's kanban cards.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store id
   * @param status the status to set
   * @return the matching rows
   */
  public List<KanbanCard> listKanbanCards(UUID tenantId, UUID storeId, String status) {
    if (status != null && !status.isBlank()) {
      return query(
          "SELECT id, tenant_id, store_id, variant_id, kanban_type, status, reorder_qty,"
              + " source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
              + " lot_multiplier, created_at, triggered_at, replenished_at"
              + " FROM kanban_cards WHERE tenant_id=? AND store_id=? AND status=?"
              + " ORDER BY created_at DESC",
          ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, storeId);
            ps.setString(3, status);
          },
          KanbanRepository::mapKanbanCard,
          "list kanban cards by status");
    }
    return query(
        "SELECT id, tenant_id, store_id, variant_id, kanban_type, status, reorder_qty,"
            + " source_store_id, supplier_ref, notes, min_order_qty, max_order_qty,"
            + " lot_multiplier, created_at, triggered_at, replenished_at"
            + " FROM kanban_cards WHERE tenant_id=? AND store_id=? ORDER BY created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, storeId);
        },
        KanbanRepository::mapKanbanCard,
        "list kanban cards");
  }

  /**
   * Writes a kanban order modifiers back with its new values.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the kanban order modifiers to act on
   * @param minOrderQty the min order qty
   * @param maxOrderQty the max order qty
   * @param lotMultiplier the lot multiplier
   * @return the kanban order modifiers as stored
   */
  public KanbanCard updateKanbanOrderModifiers(
      UUID tenantId,
      UUID id,
      BigDecimal minOrderQty,
      BigDecimal maxOrderQty,
      BigDecimal lotMultiplier) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE kanban_cards SET min_order_qty=?,max_order_qty=?,lot_multiplier=?"
                      + " WHERE tenant_id=? AND id=?"
                      + " RETURNING id,tenant_id,store_id,variant_id,kanban_type,status,reorder_qty,"
                      + "source_store_id,supplier_ref,notes,min_order_qty,max_order_qty,lot_multiplier,"
                      + "created_at,triggered_at,replenished_at")) {
            ps.setBigDecimal(1, minOrderQty);
            ps.setBigDecimal(2, maxOrderQty);
            ps.setBigDecimal(3, lotMultiplier);
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next())
                throw ApiException.notFound("KANBAN_NOT_FOUND", "No such kanban card");
              return mapKanbanCard(rs);
            }
          }
        },
        "update kanban order modifiers");
  }

  private static KanbanCard mapKanbanCard(ResultSet rs) throws SQLException {
    OffsetDateTime triggeredAt = rs.getObject("triggered_at", OffsetDateTime.class);
    OffsetDateTime replenishedAt = rs.getObject("replenished_at", OffsetDateTime.class);
    Object srcStoreRaw = rs.getObject("source_store_id");
    UUID sourceStoreId = srcStoreRaw == null ? null : rs.getObject("source_store_id", UUID.class);
    return new KanbanCard(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("kanban_type"),
        rs.getString("status"),
        rs.getBigDecimal("reorder_qty"),
        sourceStoreId,
        rs.getString("supplier_ref"),
        rs.getString("notes"),
        rs.getBigDecimal("min_order_qty"),
        rs.getBigDecimal("max_order_qty"),
        rs.getBigDecimal("lot_multiplier"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        triggeredAt == null ? null : triggeredAt.toInstant(),
        replenishedAt == null ? null : replenishedAt.toInstant());
  }
}
