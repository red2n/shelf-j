package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.DemandForecast;
import com.storeql.inventory.domain.Forecasting;
import com.storeql.inventory.domain.Forecasting.Accuracy;
import com.storeql.inventory.domain.Forecasting.Forecast;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** The demand forecasts (06.x): the daily history they are read from and the rows they become. */
@ApplicationScoped
public class ForecastRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, store_id, variant_id, method, intermittent, alpha, level, weekday_profile,"
          + " history_from, history_to, history_days, horizon_days, from_day, points, holdout_days,"
          + " mape, bias, mase, computed_at";

  private record DailyDemandRow(UUID variantId, LocalDate day, BigDecimal qty) {}

  /**
   * Daily demand buckets at a store between two days, per variant and in day order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param variantId one variant, or null for every variant with a bucket
   * @param from the first day, inclusive
   * @param to the last day, inclusive
   * @return variant → (day → quantity), days with no bucket absent
   */
  public Map<UUID, Map<LocalDate, BigDecimal>> dailyDemand(
      UUID tenantId, UUID storeId, UUID variantId, LocalDate from, LocalDate to) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT variant_id, bucket_date, demand_qty FROM demand_history"
                + " WHERE tenant_id = ? AND store_id = ? AND bucket_type = 'DAY'"
                + " AND bucket_date >= ? AND bucket_date <= ?");
    if (variantId != null) {
      sb.append(" AND variant_id = ?");
    }
    sb.append(" ORDER BY variant_id, bucket_date");
    List<DailyDemandRow> rows =
        query(
            sb.toString(),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, from);
              ps.setObject(4, to);
              if (variantId != null) {
                ps.setObject(5, variantId);
              }
            },
            rs ->
                new DailyDemandRow(
                    rs.getObject("variant_id", UUID.class),
                    rs.getObject("bucket_date", LocalDate.class),
                    rs.getBigDecimal("demand_qty")),
            "daily demand");
    Map<UUID, Map<LocalDate, BigDecimal>> out = new LinkedHashMap<>();
    for (DailyDemandRow r : rows) {
      out.computeIfAbsent(r.variantId(), k -> new TreeMap<>()).put(r.day(), r.qty());
    }
    return out;
  }

  /** Writes a run's forecasts, replacing each (store, variant)'s earlier one; the id stays. */
  public void upsertAll(List<DemandForecast> rows) {
    if (rows.isEmpty()) {
      return;
    }
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO demand_forecasts ("
                      + COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, variant_id) DO UPDATE SET"
                      + " method = EXCLUDED.method, intermittent = EXCLUDED.intermittent,"
                      + " alpha = EXCLUDED.alpha, level = EXCLUDED.level,"
                      + " weekday_profile = EXCLUDED.weekday_profile,"
                      + " history_from = EXCLUDED.history_from, history_to = EXCLUDED.history_to,"
                      + " history_days = EXCLUDED.history_days, horizon_days = EXCLUDED.horizon_days,"
                      + " from_day = EXCLUDED.from_day, points = EXCLUDED.points,"
                      + " holdout_days = EXCLUDED.holdout_days, mape = EXCLUDED.mape,"
                      + " bias = EXCLUDED.bias, mase = EXCLUDED.mase,"
                      + " computed_at = EXCLUDED.computed_at")) {
            for (DemandForecast r : rows) {
              Forecast f = r.forecast();
              ps.setObject(1, r.id());
              ps.setObject(2, r.tenantId());
              ps.setObject(3, r.storeId());
              ps.setObject(4, r.variantId());
              ps.setString(5, f.method());
              ps.setBoolean(6, f.intermittent());
              ps.setBigDecimal(7, f.alpha());
              ps.setBigDecimal(8, f.level());
              if (f.weekdayProfile().isEmpty()) {
                ps.setNull(9, Types.ARRAY);
              } else {
                ps.setArray(
                    9, c.createArrayOf("numeric", f.weekdayProfile().toArray(new BigDecimal[0])));
              }
              ps.setObject(10, r.historyFrom());
              ps.setObject(11, r.historyTo());
              ps.setInt(12, f.historyDays());
              ps.setInt(13, r.horizonDays());
              ps.setObject(14, f.fromDay());
              ps.setArray(15, c.createArrayOf("numeric", f.points().toArray(new BigDecimal[0])));
              ps.setInt(16, f.accuracy().holdoutDays());
              ps.setBigDecimal(17, f.accuracy().mape());
              ps.setBigDecimal(18, f.accuracy().bias());
              ps.setBigDecimal(19, f.accuracy().mase());
              ps.setObject(20, OffsetDateTime.ofInstant(r.computedAt(), ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "upsert forecasts");
  }

  /**
   * The forecasts at a store, newest run first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param variantId one variant, or null for all
   * @param limit at most this many rows
   * @return the matching rows
   */
  public List<DemandForecast> list(UUID tenantId, UUID storeId, UUID variantId, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT " + COLUMNS + " FROM demand_forecasts WHERE tenant_id = ? AND store_id = ?");
    if (variantId != null) {
      sb.append(" AND variant_id = ?");
    }
    sb.append(" ORDER BY computed_at DESC, variant_id LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, storeId);
          if (variantId != null) {
            ps.setObject(i++, variantId);
          }
          ps.setInt(i, limit);
        },
        ForecastRepository::map,
        "list forecasts");
  }

  /**
   * One item's forecast at one store.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param variantId the variant
   * @return the forecast, or empty when none has been run
   */
  public Optional<DemandForecast> find(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT "
                + COLUMNS
                + " FROM demand_forecasts WHERE tenant_id = ? AND store_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            ForecastRepository::map,
            "find forecast")
        .stream()
        .findFirst();
  }

  private static DemandForecast map(ResultSet rs) throws SQLException {
    Forecast f =
        new Forecast(
            rs.getString("method"),
            rs.getBoolean("intermittent"),
            rs.getBigDecimal("alpha"),
            rs.getBigDecimal("level"),
            decimals(rs.getArray("weekday_profile")),
            rs.getObject("from_day", LocalDate.class),
            decimals(rs.getArray("points")),
            new Accuracy(
                rs.getInt("holdout_days"),
                rs.getBigDecimal("mape"),
                rs.getBigDecimal("bias"),
                rs.getBigDecimal("mase")),
            rs.getInt("history_days"));
    return new DemandForecast(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("history_from", LocalDate.class),
        rs.getObject("history_to", LocalDate.class),
        rs.getInt("horizon_days"),
        f,
        rs.getObject("computed_at", OffsetDateTime.class).toInstant());
  }

  private static List<BigDecimal> decimals(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    List<BigDecimal> out = new ArrayList<>();
    for (Object o : (Object[]) array.getArray()) {
      out.add((BigDecimal) o);
    }
    return out;
  }

  /** Kept so the method catalogue reads in one place. */
  static List<String> methods() {
    return List.of(Forecasting.METHOD_MEAN, Forecasting.METHOD_SES, Forecasting.METHOD_CROSTON_SBA);
  }
}
