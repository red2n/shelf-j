package com.storeql.tenant.repo;

import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import com.storeql.tenant.domain.Meters;
import com.storeql.tenant.domain.Meters.Alert;
import com.storeql.tenant.domain.Meters.UsagePeriod;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a business used (21.10): one record per thing counted, append-only; what each period billed,
 * written in the invoice's own transaction; and the thresholds it reached, once each.
 *
 * <p>No outbox. Usage is counted here from other services' events and read back by them through
 * {@code Quotas}; announcing it again would be a second copy of the same count to keep true.
 */
@ApplicationScoped
public class UsageRepository extends BaseJdbcRepository {

  /** An open end, since Instant.MAX does not fit a timestamptz. */
  private static final Instant FAR = Instant.parse("9999-12-31T00:00:00Z");

  private static final String SUM =
      "SELECT COALESCE(SUM(quantity), 0) FROM usage_records"
          + " WHERE tenant_id = ? AND meter = ? AND recorded_at >= ? AND recorded_at < ?";

  private static final String PERIOD_COLUMNS =
      "SELECT id, tenant_id, subscription_id, meter, period_start, period_end, used, included,"
          + " overage, currency, unit_amount, amount, not_charged, invoice_id, created_at"
          + " FROM usage_periods";

  private static final String ALERT_COLUMNS =
      "SELECT id, tenant_id, meter, period_start, threshold, used, included, raised_at"
          + " FROM usage_alerts";

  private static final String INSERT_PERIOD =
      "INSERT INTO usage_periods (id, tenant_id, subscription_id, meter, period_start, period_end,"
          + " used, included, overage, currency, unit_amount, amount, not_charged, invoice_id,"
          + " created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  /**
   * What one record did.
   *
   * @param fresh false when the thing was already counted — a redelivered event
   * @param used the meter's total this period, including this record
   * @param raised the thresholds this record was the first to reach
   */
  public record Recorded(boolean fresh, long used, List<Integer> raised) {
    public Recorded {
      raised = List.copyOf(raised);
    }
  }

  /**
   * Counts one thing, once, and raises each threshold the period has now reached, once.
   *
   * @param periodStart the first day of the period it falls in
   * @param included what the plan includes of the meter, or null when there is no ceiling to reach
   */
  public Recorded record(
      UUID tenantId,
      String meter,
      long quantity,
      String sourceRef,
      Instant at,
      LocalDate periodStart,
      Long included) {
    return inTx(
        c -> {
          // One business's meter is counted one record at a time, so each sees every record before
          // it and a threshold is raised by exactly the record that reaches it. Without this, two
          // orders at the same instant each see nineteen of twenty and neither raises 100%.
          // Released
          // at commit; a different business or meter never waits on it.
          try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            ps.setLong(1, lockKey(tenantId, meter));
            ps.execute();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO usage_records (id, tenant_id, meter, quantity, source_ref,"
                      + " recorded_at) VALUES (?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, meter, source_ref) DO NOTHING")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setString(3, meter);
            ps.setLong(4, quantity);
            ps.setString(5, sourceRef);
            ps.setObject(6, at.atOffset(ZoneOffset.UTC));
            if (ps.executeUpdate() == 0) return new Recorded(false, 0, List.of());
          }
          long used = sum(c, tenantId, meter, startOf(periodStart), Instant.MAX);
          List<Integer> raised = new ArrayList<>(2);
          for (int threshold : Meters.reached(used, included)) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO usage_alerts (id, tenant_id, meter, period_start, threshold, used,"
                        + " included, raised_at) VALUES (?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (tenant_id, meter, period_start, threshold) DO NOTHING")) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setString(3, meter);
              ps.setObject(4, periodStart);
              ps.setInt(5, threshold);
              ps.setLong(6, used);
              ps.setLong(7, included);
              ps.setObject(8, at.atOffset(ZoneOffset.UTC));
              if (ps.executeUpdate() == 1) raised.add(threshold);
            }
          }
          return new Recorded(true, used, raised);
        },
        "record usage");
  }

  /** How much of a meter a business used from one instant up to (not including) another. */
  public long used(UUID tenantId, String meter, Instant from, Instant to) {
    return inTx(c -> sum(c, tenantId, meter, from, to), "read usage");
  }

  /** How much of a meter earlier periods of a subscription have already billed. */
  public long billed(UUID subscriptionId, String meter) {
    return query(
            "SELECT COALESCE(SUM(used), 0) FROM usage_periods"
                + " WHERE subscription_id = ? AND meter = ?",
            ps -> {
              ps.setObject(1, subscriptionId);
              ps.setString(2, meter);
            },
            rs -> rs.getLong(1),
            "read billed usage")
        .get(0);
  }

  /** The meters of one subscription's period that are already closed. */
  public java.util.Set<String> closed(UUID subscriptionId, LocalDate periodStart) {
    return java.util.Set.copyOf(
        query(
            "SELECT meter FROM usage_periods WHERE subscription_id = ? AND period_start = ?",
            ps -> {
              ps.setObject(1, subscriptionId);
              ps.setObject(2, periodStart);
            },
            rs -> rs.getString(1),
            "read closed usage periods"));
  }

  /** A business's closed periods, newest first. */
  public List<UsagePeriod> periods(UUID tenantId, int limit) {
    return query(
        PERIOD_COLUMNS + " WHERE tenant_id = ? ORDER BY period_start DESC, meter LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setInt(2, limit);
        },
        UsageRepository::readPeriod,
        "list usage periods");
  }

  /** The thresholds a business reached in one period. */
  public List<Alert> alerts(UUID tenantId, LocalDate periodStart) {
    return query(
        ALERT_COLUMNS
            + " WHERE tenant_id = ? AND period_start = ? ORDER BY raised_at, meter, threshold",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, periodStart);
        },
        UsageRepository::readAlert,
        "list usage alerts");
  }

  /**
   * Every threshold reached, across businesses, newest first. The platform's read — who is about to
   * outgrow a plan — and the one query here with no business to filter by.
   */
  public List<Alert> recentAlerts(int limit) {
    return query(
        ALERT_COLUMNS + " ORDER BY raised_at DESC, id LIMIT ?",
        ps -> ps.setInt(1, limit),
        UsageRepository::readAlert,
        "list usage alerts across businesses");
  }

  private static Alert readAlert(ResultSet rs) throws SQLException {
    return new Alert(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getString(3),
        rs.getObject(4, LocalDate.class),
        rs.getInt(5),
        rs.getLong(6),
        rs.getLong(7),
        rs.getObject(8, OffsetDateTime.class).toInstant());
  }

  /** Closes periods no invoice carries: a subscription ended with nothing over its allowance. */
  public void close(List<UsagePeriod> periods) {
    inTx(
        c -> {
          insertPeriods(c, periods, null);
          return true;
        },
        "close usage periods");
  }

  /**
   * Writes what periods billed, inside the transaction of the invoice that carries them, so an
   * invoice and its usage stand or fall together.
   *
   * @param invoiceId the invoice, or null when there is none
   */
  public static void insertPeriods(Connection c, List<UsagePeriod> periods, UUID invoiceId)
      throws SQLException {
    if (periods.isEmpty()) return;
    try (PreparedStatement ps = c.prepareStatement(INSERT_PERIOD)) {
      for (UsagePeriod p : periods) {
        ps.setObject(1, p.id());
        ps.setObject(2, p.tenantId());
        ps.setObject(3, p.subscriptionId());
        ps.setString(4, p.meter());
        ps.setObject(5, p.periodStart());
        ps.setObject(6, p.periodEnd());
        ps.setLong(7, p.used());
        ps.setObject(8, p.included());
        ps.setLong(9, p.overage());
        ps.setString(10, p.currency());
        ps.setBigDecimal(11, p.unitAmount());
        ps.setBigDecimal(12, p.amount());
        ps.setString(13, p.notCharged());
        ps.setObject(14, invoiceId);
        ps.setObject(15, p.createdAt().atOffset(ZoneOffset.UTC));
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** A lock key for one business's meter. A collision only makes two counts take turns. */
  static long lockKey(UUID tenantId, String meter) {
    return tenantId.getMostSignificantBits()
        ^ tenantId.getLeastSignificantBits()
        ^ meter.hashCode();
  }

  /** The first instant of a day, as usage is counted: in UTC. */
  public static Instant startOf(LocalDate day) {
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private static long sum(Connection c, UUID tenantId, String meter, Instant from, Instant to)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SUM)) {
      ps.setObject(1, tenantId);
      ps.setString(2, meter);
      ps.setObject(3, from.atOffset(ZoneOffset.UTC));
      ps.setObject(4, (to.equals(Instant.MAX) ? FAR : to).atOffset(ZoneOffset.UTC));
      try (ResultSet rs = ps.executeQuery()) {
        // An aggregate always answers one row; none would be a driver fault, not zero usage.
        if (!rs.next()) throw new SQLException("a sum answered no row");
        return rs.getLong(1);
      }
    }
  }

  private static UsagePeriod readPeriod(ResultSet rs) throws SQLException {
    return new UsagePeriod(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getObject(3, UUID.class),
        rs.getString(4),
        rs.getObject(5, LocalDate.class),
        rs.getObject(6, LocalDate.class),
        rs.getLong(7),
        rs.getObject(8, Long.class),
        rs.getLong(9),
        rs.getString(10),
        rs.getBigDecimal(11),
        rs.getBigDecimal(12),
        rs.getString(13),
        rs.getObject(14, UUID.class),
        rs.getObject(15, OffsetDateTime.class).toInstant());
  }
}
