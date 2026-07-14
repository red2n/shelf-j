package com.shelfj.inventory.repo;

import com.shelfj.inventory.domain.Domain.Suggestion;
import com.shelfj.service.BaseOutboxRepository;
import com.shelfj.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Replenishment suggestions (min-max planning output). Extracted from {@code InventoryRepository}:
 * self-contained aggregate with its own outbox events, no coupling to any other aggregate.
 */
@ApplicationScoped
public class SuggestionRepository extends BaseOutboxRepository {

  /**
   * Insert a replenishment suggestion. Returns the suggestion if inserted; empty if an OPEN
   * suggestion already exists for the same (tenant, store, variant) — idempotent via unique partial
   * index.
   */
  public Optional<Suggestion> insertSuggestionIfAbsent(Suggestion s, OutboxRow event) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO replenishment_suggestions"
                      + " (id, tenant_id, store_id, variant_id, available_qty,"
                      + " min_qty, max_qty, suggested_qty, status, created_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, variant_id)"
                      + " WHERE status = 'OPEN' DO NOTHING")) {
            ps.setObject(1, s.id());
            ps.setObject(2, s.tenantId());
            ps.setObject(3, s.storeId());
            ps.setObject(4, s.variantId());
            ps.setBigDecimal(5, s.availableQty());
            ps.setBigDecimal(6, s.minQty());
            ps.setBigDecimal(7, s.maxQty());
            ps.setBigDecimal(8, s.suggestedQty());
            ps.setString(9, s.status());
            ps.setObject(10, s.createdAt().atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() == 0) return Optional.<Suggestion>empty();
          }
          insertOutbox(c, event);
          return Optional.of(s);
        },
        "insert suggestion");
  }

  public List<Suggestion> listSuggestions(UUID tenantId, UUID storeId, String status, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT id, tenant_id, store_id, variant_id, available_qty, min_qty, max_qty,"
                + " suggested_qty, status, created_at, resolved_at"
                + " FROM replenishment_suggestions WHERE tenant_id = ?");
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
        SuggestionRepository::mapSuggestion,
        "list suggestions");
  }

  /** Transition an OPEN suggestion → ORDERED or CANCELLED; emits outbox event. */
  public Optional<Suggestion> resolveSuggestion(
      UUID tenantId, UUID suggId, String newStatus, OutboxRow event) {
    return inTx(
        c -> {
          Suggestion updated;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE replenishment_suggestions"
                      + " SET status = ?, resolved_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'"
                      + " RETURNING id, tenant_id, store_id, variant_id, available_qty,"
                      + " min_qty, max_qty, suggested_qty, status, created_at, resolved_at")) {
            ps.setString(1, newStatus);
            ps.setObject(2, tenantId);
            ps.setObject(3, suggId);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) return Optional.<Suggestion>empty();
              updated = mapSuggestion(rs);
            }
          }
          insertOutbox(c, event);
          return Optional.of(updated);
        },
        "resolve suggestion");
  }

  private static Suggestion mapSuggestion(ResultSet rs) throws SQLException {
    OffsetDateTime resolvedOdt = rs.getObject("resolved_at", OffsetDateTime.class);
    return new Suggestion(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("available_qty"),
        rs.getBigDecimal("min_qty"),
        rs.getBigDecimal("max_qty"),
        rs.getBigDecimal("suggested_qty"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        resolvedOdt == null ? null : resolvedOdt.toInstant());
  }
}
