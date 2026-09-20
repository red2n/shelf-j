package com.storeql.tenant.repo;

import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Plans;
import com.storeql.tenant.domain.Plans.Grant;
import com.storeql.tenant.domain.Plans.Plan;
import com.storeql.tenant.domain.Plans.PlanChange;
import com.storeql.tenant.domain.Plans.Price;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * The platform's price list (21.8). Plans, their prices and what they include are the same for
 * every business, so nothing here is tenant-scoped; what a business is on is {@code
 * tenants.plan_id}, and how it got there is {@code tenant_plan_changes}, append-only.
 *
 * <p>No outbox: a plan is the platform's own, and what another service needs of it — what a
 * business is entitled to — it reads through {@code Entitlements}, cached for a short while, the
 * way it reads a tenant's profile. An event would be a second copy of the same fact to keep true.
 */
@ApplicationScoped
public class PlanRepository extends BaseJdbcRepository {

  private static final String PLAN_COLUMNS =
      "SELECT id, code, name, description, status, billing_interval, trial_days, is_default,"
          + " is_public, sort_order, created_by, created_at, updated_at FROM plans";

  private static final String INSERT_PLAN =
      "INSERT INTO plans (id, code, name, description, status, billing_interval, trial_days,"
          + " is_default, is_public, sort_order, created_by, created_at, updated_at)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String UPDATE_PLAN =
      "UPDATE plans SET name = ?, description = ?, billing_interval = ?, trial_days = ?,"
          + " is_public = ?, sort_order = ?, updated_at = ? WHERE id = ?";

  // ── plans ───────────────────────────────────────────────────────────────────

  public Plan create(Plan p) {
    exec(
        INSERT_PLAN,
        ps -> {
          OffsetDateTime now = p.createdAt().atOffset(ZoneOffset.UTC);
          ps.setObject(1, p.id());
          ps.setString(2, p.code());
          ps.setString(3, p.name());
          ps.setString(4, p.description());
          ps.setString(5, p.status());
          ps.setString(6, p.billingInterval());
          ps.setInt(7, p.trialDays());
          ps.setBoolean(8, p.isDefault());
          ps.setBoolean(9, p.isPublic());
          ps.setInt(10, p.sortOrder());
          ps.setObject(11, p.createdBy());
          ps.setObject(12, now);
          ps.setObject(13, now);
        },
        "create plan");
    return p;
  }

  /** Changes what may be changed on a plan; its code, status and default-ness move on their own. */
  public boolean update(Plan p) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(UPDATE_PLAN)) {
            ps.setString(1, p.name());
            ps.setString(2, p.description());
            ps.setString(3, p.billingInterval());
            ps.setInt(4, p.trialDays());
            ps.setBoolean(5, p.isPublic());
            ps.setInt(6, p.sortOrder());
            ps.setObject(7, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(8, p.id());
            return ps.executeUpdate() == 1;
          }
        },
        "update plan");
  }

  public Optional<Plan> find(UUID id) {
    return query(
            PLAN_COLUMNS + " WHERE id = ?",
            ps -> ps.setObject(1, id),
            PlanRepository::mapPlan,
            "load plan")
        .stream()
        .findFirst();
  }

  public Optional<Plan> findByCode(String code) {
    return query(
            PLAN_COLUMNS + " WHERE upper(code) = upper(?)",
            ps -> ps.setString(1, code),
            PlanRepository::mapPlan,
            "load plan by code")
        .stream()
        .findFirst();
  }

  /**
   * The price list.
   *
   * @param onlySold the plans a business can be put on today
   * @param onlyPublic the plans a visitor may read
   */
  public List<Plan> list(boolean onlySold, boolean onlyPublic) {
    return query(
        PLAN_COLUMNS
            + " WHERE (? = false OR status = 'ACTIVE') AND (? = false OR is_public)"
            + " ORDER BY sort_order, code",
        ps -> {
          ps.setBoolean(1, onlySold);
          ps.setBoolean(2, onlyPublic);
        },
        PlanRepository::mapPlan,
        "list plans");
  }

  public Optional<Plan> defaultPlan() {
    return query(
            PLAN_COLUMNS + " WHERE is_default",
            ps -> {},
            PlanRepository::mapPlan,
            "load the default plan")
        .stream()
        .findFirst();
  }

  /**
   * Moves a plan between statuses, from the one it must be in.
   *
   * @return false when it was not in {@code from}
   */
  public boolean moveStatus(UUID id, String from, String to) {
    return inTx(
        c -> {
          if (Plans.ACTIVE.equals(from) && !Plans.ACTIVE.equals(to)) {
            // A plan that stops being sold stops being the default; the platform picks another.
            clearDefault(c, id);
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE plans SET status = ?, updated_at = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, to);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, id);
            ps.setString(4, from);
            return ps.executeUpdate() == 1;
          }
        },
        "move plan status");
  }

  /**
   * Makes one plan the default and no other, in one transaction: two defaults would mean two
   * answers to what a business signing up is sold.
   *
   * @return false when the plan is not one that is sold
   */
  public boolean makeDefault(UUID id) {
    return inTx(
        c -> {
          clearDefault(c, null);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE plans SET is_default = true, updated_at = ? WHERE id = ?"
                      + " AND status = 'ACTIVE'")) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, id);
            return ps.executeUpdate() == 1;
          }
        },
        "make plan the default");
  }

  private static void clearDefault(Connection c, UUID onlyThisOne) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE plans SET is_default = false, updated_at = ? WHERE is_default"
                + " AND (CAST(? AS UUID) IS NULL OR id = ?)")) {
      ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
      ps.setObject(2, onlyThisOne);
      ps.setObject(3, onlyThisOne);
      ps.executeUpdate();
    }
  }

  // ── prices ──────────────────────────────────────────────────────────────────

  /** Adds a price from a date. An existing row for that plan, currency and date is replaced. */
  public void setPrice(Price p) {
    exec(
        "INSERT INTO plan_prices (id, plan_id, currency, amount, effective_from, created_by,"
            + " created_at) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (plan_id, currency, effective_from)"
            + " DO UPDATE SET amount = EXCLUDED.amount, created_by = EXCLUDED.created_by,"
            + " created_at = EXCLUDED.created_at",
        ps -> {
          ps.setObject(1, p.id());
          ps.setObject(2, p.planId());
          ps.setString(3, p.currency());
          ps.setBigDecimal(4, p.amount());
          ps.setObject(5, p.effectiveFrom());
          ps.setObject(6, p.createdBy());
          ps.setObject(7, p.createdAt().atOffset(ZoneOffset.UTC));
        },
        "set plan price");
  }

  public List<Price> prices(UUID planId) {
    return query(
        "SELECT id, plan_id, currency, amount, effective_from, created_by, created_at"
            + " FROM plan_prices WHERE plan_id = ? ORDER BY currency, effective_from DESC",
        ps -> ps.setObject(1, planId),
        rs ->
            new Price(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class),
                rs.getObject(6, UUID.class),
                rs.getObject(7, OffsetDateTime.class).toInstant()),
        "list plan prices");
  }

  /** What a plan cost in a currency on a day: the latest price that had taken effect by then. */
  public Optional<BigDecimal> priceOn(UUID planId, String currency, LocalDate day) {
    return query(
            "SELECT amount FROM plan_prices WHERE plan_id = ? AND currency = ?"
                + " AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
            ps -> {
              ps.setObject(1, planId);
              ps.setString(2, currency);
              ps.setObject(3, day);
            },
            rs -> rs.getBigDecimal(1),
            "read a plan's price")
        .stream()
        .findFirst();
  }

  // ── what a plan includes ────────────────────────────────────────────────────

  /** Replaces what a plan grants, whole: a key left out is a key the plan no longer names. */
  public void setGrants(UUID planId, List<Grant> grants) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM plan_entitlements WHERE plan_id = ?")) {
            ps.setObject(1, planId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO plan_entitlements (plan_id, key, limit_value, enabled)"
                      + " VALUES (?,?,?,?)")) {
            for (Grant g : grants) {
              ps.setObject(1, planId);
              ps.setString(2, g.key());
              ps.setObject(3, g.limitValue());
              ps.setObject(4, g.enabled());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "set what a plan includes");
  }

  public List<Grant> grants(UUID planId) {
    return query(
        "SELECT key, limit_value, enabled FROM plan_entitlements WHERE plan_id = ? ORDER BY key",
        ps -> ps.setObject(1, planId),
        rs ->
            new Grant(rs.getString(1), rs.getObject(2, Long.class), rs.getObject(3, Boolean.class)),
        "list what a plan includes");
  }

  // ── which plan a business is on ─────────────────────────────────────────────

  /**
   * Puts a business on a plan and records how it got there, in one transaction.
   *
   * @param expected the plan it must be on now, so two changes cannot pass each other; {@code
   *     Optional.empty()} means it must be on none
   * @return false when it was on something else
   */
  public boolean changeTenantPlan(
      UUID tenantId, Optional<UUID> expected, UUID toPlanId, UUID changedBy, String reason) {
    return inTx(
        c -> {
          UUID from = expected.orElse(null);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE tenants SET plan_id = ?, updated_at = ? WHERE id = ?"
                      + " AND plan_id IS NOT DISTINCT FROM CAST(? AS UUID)")) {
            ps.setObject(1, toPlanId);
            ps.setObject(2, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(3, tenantId);
            ps.setObject(4, from);
            if (ps.executeUpdate() != 1) return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO tenant_plan_changes (id, tenant_id, from_plan_id, to_plan_id,"
                      + " changed_by, reason, changed_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, from);
            ps.setObject(4, toPlanId);
            ps.setObject(5, changedBy);
            ps.setString(6, reason);
            ps.setObject(7, Instant.now().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return true;
        },
        "put a business on a plan");
  }

  public List<PlanChange> changes(UUID tenantId, int limit) {
    return query(
        "SELECT id, from_plan_id, to_plan_id, changed_by, reason, changed_at"
            + " FROM tenant_plan_changes WHERE tenant_id = ? ORDER BY changed_at DESC, id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        rs ->
            new PlanChange(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getString(5),
                rs.getObject(6, OffsetDateTime.class).toInstant()),
        "list a business's plan changes");
  }

  /** How many businesses are on a plan: a plan with subscribers is retired, never deleted. */
  public int subscribers(UUID planId) {
    List<Integer> rows =
        query(
            "SELECT count(*) FROM tenants WHERE plan_id = ?",
            ps -> ps.setObject(1, planId),
            rs -> rs.getInt(1),
            "count a plan's subscribers");
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /** How many stores a business has, for a limit that is this service's own to enforce. */
  public long storeCount(UUID tenantId) {
    List<Long> rows =
        query(
            "SELECT count(*) FROM stores WHERE tenant_id = ? AND status <> 'CLOSED'",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getLong(1),
            "count a business's stores");
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /** How many people work for a business: people, not assignments. */
  public long staffCount(UUID tenantId) {
    List<Long> rows =
        query(
            "SELECT count(DISTINCT user_id) FROM staff_assignments WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getLong(1),
            "count a business's staff");
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /** Whether this person already works here, at any store. */
  public boolean alreadyStaff(UUID tenantId, UUID userId) {
    return !query(
            "SELECT 1 FROM staff_assignments WHERE tenant_id = ? AND user_id = ? LIMIT 1",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, userId);
            },
            rs -> rs.getInt(1),
            "check whether somebody already works here")
        .isEmpty();
  }

  private static Plan mapPlan(ResultSet rs) throws SQLException {
    return new Plan(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6),
        rs.getInt(7),
        rs.getBoolean(8),
        rs.getBoolean(9),
        rs.getInt(10),
        rs.getObject(11, UUID.class),
        rs.getObject(12, OffsetDateTime.class).toInstant(),
        rs.getObject(13, OffsetDateTime.class).toInstant());
  }
}
