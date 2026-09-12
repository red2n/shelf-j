package com.shelfj.inventory.repo;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.Domain.PickingRule;
import com.shelfj.inventory.domain.Domain.PickingRuleAssignment;
import com.shelfj.inventory.domain.Domain.PickingRuleZonePriority;
import com.shelfj.service.BaseJdbcRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Picking rules, zone priorities, and rule assignments (Gap #38). Extracted from {@code
 * InventoryRepository}: self-contained, no outbox events. {@code previewPickOrder} (the method that
 * actually reads {@code inventory_batches} in rule order) stayed behind in the core repo since it
 * needs {@code mapBatch} — see that class's picking-rules section comment.
 */
@ApplicationScoped
public class PickingRuleRepository extends BaseJdbcRepository {

  /**
   * Inserts a picking rule.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param name the name to set
   * @param strategy the picking strategy
   * @param gradePreference the grade preference
   * @return the picking rule as stored
   */
  public PickingRule createPickingRule(
      UUID tenantId, String name, String strategy, String gradePreference) {
    Instant now = Instant.now();
    UUID id = Ids.newId();
    exec(
        "INSERT INTO picking_rules (id,tenant_id,name,strategy,grade_preference,status,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setString(3, name);
          ps.setString(4, strategy);
          ps.setString(5, gradePreference);
          ps.setString(6, PickingRule.ACTIVE);
          ps.setObject(7, now.atOffset(ZoneOffset.UTC));
          ps.setObject(8, now.atOffset(ZoneOffset.UTC));
        },
        "create picking rule");
    return findPickingRule(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PICKING_RULE_NOT_FOUND", "Picking rule not found"));
  }

  /**
   * Looks a picking rule up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the picking rule to act on
   * @return the picking rule, or empty when it does not exist in this tenant
   */
  public Optional<PickingRule> findPickingRule(UUID tenantId, UUID id) {
    return query(
            "SELECT id,tenant_id,name,strategy,grade_preference,status,created_at,updated_at"
                + " FROM picking_rules WHERE tenant_id=? AND id=?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            PickingRuleRepository::mapPickingRule,
            "find picking rule")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's picking rules.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<PickingRule> listPickingRules(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,name,strategy,grade_preference,status,created_at,updated_at"
            + " FROM picking_rules WHERE tenant_id=? AND status='ACTIVE' ORDER BY name LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        PickingRuleRepository::mapPickingRule,
        "list picking rules");
  }

  /**
   * Soft-deletes a picking rule by marking it inactive.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the picking rule to act on
   * @return the picking rule in its deactivated state
   */
  public PickingRule deactivatePickingRule(UUID tenantId, UUID id) {
    Instant now = Instant.now();
    exec(
        "UPDATE picking_rules SET status='INACTIVE', updated_at=? WHERE tenant_id=? AND id=?",
        ps -> {
          ps.setObject(1, now.atOffset(ZoneOffset.UTC));
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "deactivate picking rule");
    return findPickingRule(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PICKING_RULE_NOT_FOUND", "Picking rule not found"));
  }

  /**
   * Replaces a rule's zone priority order wholesale.
   *
   * <p>Deletes then reinserts rather than merging, so the stored order is exactly what the caller
   * sent and a removed zone does not linger.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param ruleId the picking rule to configure
   * @param items the zones in the order they should be picked from
   */
  public void replaceZonePriorities(
      UUID tenantId, UUID ruleId, List<PickingRuleZonePriority> items) {
    exec(
        "DELETE FROM picking_rule_zone_priorities WHERE tenant_id=? AND rule_id=?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, ruleId);
        },
        "delete zone priorities");
    for (PickingRuleZonePriority p : items) {
      exec(
          "INSERT INTO picking_rule_zone_priorities (id,tenant_id,rule_id,zone_id,priority)"
              + " VALUES (?,?,?,?,?)",
          ps -> {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, ruleId);
            ps.setObject(4, p.zoneId());
            ps.setInt(5, p.priority());
          },
          "insert zone priority");
    }
  }

  /**
   * Lists the tenant's zone priorities.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param ruleId the rule id
   * @return the matching rows
   */
  public List<PickingRuleZonePriority> listZonePriorities(UUID tenantId, UUID ruleId) {
    return query(
        "SELECT id,tenant_id,rule_id,zone_id,priority FROM picking_rule_zone_priorities"
            + " WHERE tenant_id=? AND rule_id=? ORDER BY priority ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, ruleId);
        },
        PickingRuleRepository::mapZonePriority,
        "list zone priorities");
  }

  /**
   * Inserts a picking rule assignment.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param ruleId the rule id
   * @param scopeType the scope type
   * @param scopeId the scope id
   * @return the picking rule assignment as stored
   */
  public PickingRuleAssignment createPickingRuleAssignment(
      UUID tenantId, UUID ruleId, String scopeType, UUID scopeId) {
    Instant now = Instant.now();
    UUID id = Ids.newId();
    exec(
        "INSERT INTO picking_rule_assignments (id,tenant_id,rule_id,scope_type,scope_id,created_at)"
            + " VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (tenant_id,scope_type,scope_id) DO UPDATE"
            + " SET rule_id=EXCLUDED.rule_id",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, tenantId);
          ps.setObject(3, ruleId);
          ps.setString(4, scopeType);
          if (scopeId != null) ps.setObject(5, scopeId);
          else ps.setNull(5, Types.OTHER);
          ps.setObject(6, now.atOffset(ZoneOffset.UTC));
        },
        "create picking rule assignment");
    return findPickingRuleAssignment(tenantId, scopeType, scopeId)
        .orElseThrow(
            () ->
                ApiException.notFound("ASSIGNMENT_NOT_FOUND", "Picking rule assignment not found"));
  }

  /**
   * Looks a picking rule assignment up by id.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param scopeType the scope type
   * @param scopeId the scope id
   * @return the picking rule assignment, or empty when it does not exist in this tenant
   */
  public Optional<PickingRuleAssignment> findPickingRuleAssignment(
      UUID tenantId, String scopeType, UUID scopeId) {
    return query(
            "SELECT id,tenant_id,rule_id,scope_type,scope_id,created_at"
                + " FROM picking_rule_assignments WHERE tenant_id=? AND scope_type=?"
                + " AND (scope_id=? OR (scope_id IS NULL AND ?::uuid IS NULL))",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, scopeType);
              if (scopeId != null) ps.setObject(3, scopeId);
              else ps.setNull(3, Types.OTHER);
              if (scopeId != null) ps.setObject(4, scopeId);
              else ps.setNull(4, Types.OTHER);
            },
            PickingRuleRepository::mapPickingRuleAssignment,
            "find picking rule assignment")
        .stream()
        .findFirst();
  }

  /**
   * Lists the tenant's picking rule assignments.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param limit maximum rows
   * @return the matching rows
   */
  public List<PickingRuleAssignment> listPickingRuleAssignments(UUID tenantId, int limit) {
    return query(
        "SELECT id,tenant_id,rule_id,scope_type,scope_id,created_at"
            + " FROM picking_rule_assignments WHERE tenant_id=? ORDER BY scope_type, scope_id"
            + " LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        PickingRuleRepository::mapPickingRuleAssignment,
        "list picking rule assignments");
  }

  /**
   * Deletes a picking rule assignment.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the picking rule assignment to act on
   * @return {@code true} when a row was removed, {@code false} when nothing matched
   */
  public boolean deletePickingRuleAssignment(UUID tenantId, UUID id) {
    Instant[] found = {null};
    query(
        "DELETE FROM picking_rule_assignments WHERE tenant_id=? AND id=? RETURNING id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, id);
        },
        rs -> {
          found[0] = Instant.now();
          return found[0];
        },
        "delete picking rule assignment");
    return found[0] != null;
  }

  /** Resolve the most-specific applicable picking rule for a (tenant, store, variant) triple. */
  public Optional<PickingRule> resolvePickingRule(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT pr.id,pr.tenant_id,pr.name,pr.strategy,pr.grade_preference,pr.status,"
                + "pr.created_at,pr.updated_at"
                + " FROM picking_rule_assignments pra"
                + " JOIN picking_rules pr ON pr.id=pra.rule_id AND pr.status='ACTIVE'"
                + " WHERE pra.tenant_id=?"
                + " AND ((pra.scope_type='PRODUCT' AND pra.scope_id=?)"
                + "   OR (pra.scope_type='STORE' AND pra.scope_id=?)"
                + "   OR (pra.scope_type='GLOBAL'))"
                + " ORDER BY CASE pra.scope_type WHEN 'PRODUCT' THEN 1"
                + "           WHEN 'STORE' THEN 2 ELSE 3 END LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setObject(3, storeId);
            },
            PickingRuleRepository::mapPickingRule,
            "resolve picking rule")
        .stream()
        .findFirst();
  }

  private static PickingRule mapPickingRule(ResultSet rs) throws SQLException {
    return new PickingRule(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("strategy"),
        rs.getString("grade_preference"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static PickingRuleZonePriority mapZonePriority(ResultSet rs) throws SQLException {
    return new PickingRuleZonePriority(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("rule_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getInt("priority"));
  }

  private static PickingRuleAssignment mapPickingRuleAssignment(ResultSet rs) throws SQLException {
    return new PickingRuleAssignment(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("rule_id", UUID.class),
        rs.getString("scope_type"),
        rs.getObject("scope_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
