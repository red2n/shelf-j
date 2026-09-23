package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The statistical demand forecast (06.x) as pure arithmetic over one item's daily demand at one
 * store: a zero-filled series, oldest first, ending on the last day of history.
 *
 * <p>Two methods, chosen by the shape of the demand as the replenishment literature classifies it
 * (Syntetos and Boylan): a series with demand on most days is <b>smooth</b> and gets simple
 * exponential smoothing with a day-of-week profile once there are four weeks to learn one from; a
 * series with demand on fewer than three days in four (average inter-demand interval above 1.32) is
 * <b>intermittent</b> and gets Croston's method with the Syntetos–Boylan approximation, which
 * forecasts the demand per day as the smoothed size over the smoothed interval, corrected for
 * Croston's bias. Fewer than fourteen days of history is a plain mean and says so.
 *
 * <p>The smoothing constant is not a setting: it is chosen from a small grid by forecasting the
 * last quarter of the history (at least a week, at most four) from the rest and keeping the alpha
 * that missed it least. The same hold-out gives the forecast's own report on itself — MAPE over the
 * days that had demand, bias as a percentage of what was sold, and MASE against a naïve
 * one-day-back forecast — each null rather than zero when it cannot be honestly computed.
 */
public final class Forecasting {

  public static final String METHOD_MEAN = "MEAN";
  public static final String METHOD_SES = "SES";
  public static final String METHOD_CROSTON_SBA = "CROSTON_SBA";

  /** Below this, the only honest forecast is the mean, with no claim of accuracy. */
  public static final int MIN_HISTORY_DAYS = 14;

  /** A day-of-week profile needs four weeks to say anything about a Saturday. */
  static final int PROFILE_MIN_DAYS = 28;

  /** Average days between demands above which demand is intermittent (Syntetos–Boylan). */
  static final double INTERMITTENT_ADI = 1.32;

  private static final double[] ALPHAS = {0.05, 0.1, 0.2, 0.3, 0.5};
  private static final int SCALE = 4;
  private static final double PROFILE_FLOOR = 0.2;
  private static final double PROFILE_CEILING = 3.0;

  private Forecasting() {}

  /** How the forecast did on the hold-out; each figure null when it cannot be honestly computed. */
  public record Accuracy(int holdoutDays, BigDecimal mape, BigDecimal bias, BigDecimal mase) {}

  /**
   * The forecast for one item at one store: {@code points} is one expected quantity per day from
   * {@code fromDay}; {@code weekdayProfile} is seven multipliers, Monday first, or empty.
   */
  public record Forecast(
      String method,
      boolean intermittent,
      BigDecimal alpha,
      BigDecimal level,
      List<BigDecimal> weekdayProfile,
      LocalDate fromDay,
      List<BigDecimal> points,
      Accuracy accuracy,
      int historyDays) {

    public Forecast {
      weekdayProfile = List.copyOf(weekdayProfile);
      points = List.copyOf(points);
    }

    /** Expected demand over the next {@code days} days (or as many as the horizon holds). */
    public BigDecimal expectedOver(int days) {
      BigDecimal sum = BigDecimal.ZERO;
      for (int i = 0; i < Math.min(days, points.size()); i++) {
        sum = sum.add(points.get(i));
      }
      return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }
  }

  /** A fitted model: the smoothed daily level and, for smooth demand, a day-of-week profile. */
  private record Model(double level, double[] profile) {}

  /**
   * Forecasts {@code horizonDays} from the day after {@code lastDay}.
   *
   * @param daily demand per day, zero-filled, oldest first, ending on {@code lastDay}
   * @param lastDay the last day the history covers
   * @param horizonDays how many days to forecast
   * @return the forecast, with its own report on itself
   */
  public static Forecast forecast(List<BigDecimal> daily, LocalDate lastDay, int horizonDays) {
    double[] y = daily.stream().mapToDouble(BigDecimal::doubleValue).toArray();
    int n = y.length;
    LocalDate fromDay = lastDay.plusDays(1);
    int withDemand = 0;
    for (double v : y) {
      if (v > 0) withDemand++;
    }
    if (n < MIN_HISTORY_DAYS || withDemand == 0) {
      double mean = n == 0 ? 0 : sum(y) / n;
      return new Forecast(
          METHOD_MEAN,
          false,
          null,
          dec(mean, SCALE),
          List.of(),
          fromDay,
          points(new Model(mean, null), fromDay, horizonDays),
          new Accuracy(0, null, null, null),
          n);
    }
    boolean intermittent = (double) n / withDemand > INTERMITTENT_ADI;
    int holdout = Math.max(7, Math.min(28, n / 4));
    double[] train = Arrays.copyOfRange(y, 0, n - holdout);
    double[] held = Arrays.copyOfRange(y, n - holdout, n);
    LocalDate trainLast = lastDay.minusDays(holdout);

    double bestAlpha = ALPHAS[0];
    double bestMae = Double.MAX_VALUE;
    double[] bestPrediction = null;
    for (double alpha : ALPHAS) {
      Model m = fit(train, trainLast, alpha, intermittent);
      double[] prediction = predict(m, trainLast.plusDays(1), holdout);
      double mae = meanAbsoluteError(held, prediction);
      if (mae < bestMae) {
        bestMae = mae;
        bestAlpha = alpha;
        bestPrediction = prediction;
      }
    }
    Accuracy accuracy = accuracy(train, held, bestPrediction);
    Model model = fit(y, lastDay, bestAlpha, intermittent);
    return new Forecast(
        intermittent ? METHOD_CROSTON_SBA : METHOD_SES,
        intermittent,
        dec(bestAlpha, 3),
        dec(model.level(), SCALE),
        profileOf(model),
        fromDay,
        points(model, fromDay, horizonDays),
        accuracy,
        n);
  }

  // ── fitting ─────────────────────────────────────────────────────────────────

  private static Model fit(double[] y, LocalDate lastDay, double alpha, boolean intermittent) {
    if (intermittent) {
      return new Model(crostonSba(y, alpha), null);
    }
    double[] profile = y.length >= PROFILE_MIN_DAYS ? weekdayProfile(y, lastDay) : null;
    return new Model(ses(y, alpha), profile);
  }

  /** Simple exponential smoothing; the level starts at the mean of the first week. */
  private static double ses(double[] y, double alpha) {
    int warm = Math.min(7, y.length);
    double level = sum(Arrays.copyOfRange(y, 0, warm)) / warm;
    for (double v : y) {
      level = alpha * v + (1 - alpha) * level;
    }
    return Math.max(0, level);
  }

  /**
   * Croston's method with the Syntetos–Boylan approximation: demand sizes and the intervals between
   * them smoothed separately at each demand, forecast as size over interval, times (1 − α/2) to
   * take Croston's bias off.
   */
  private static double crostonSba(double[] y, double alpha) {
    double size = -1;
    double interval = -1;
    int sinceLast = 0;
    for (double v : y) {
      sinceLast++;
      if (v <= 0) {
        continue;
      }
      if (size < 0) {
        size = v;
        interval = sinceLast;
      } else {
        size = alpha * v + (1 - alpha) * size;
        interval = alpha * sinceLast + (1 - alpha) * interval;
      }
      sinceLast = 0;
    }
    if (size < 0 || interval <= 0) {
      return 0;
    }
    return Math.max(0, (1 - alpha / 2) * size / interval);
  }

  /**
   * Seven multipliers, Monday first: each weekday's average against the overall mean, clamped and
   * normalised so the week still adds up to the level times seven.
   */
  private static double[] weekdayProfile(double[] y, LocalDate lastDay) {
    double[] sums = new double[7];
    int[] counts = new int[7];
    for (int i = 0; i < y.length; i++) {
      int dow = lastDay.minusDays(y.length - 1L - i).getDayOfWeek().getValue() - 1;
      sums[dow] += y[i];
      counts[dow]++;
    }
    double mean = sum(y) / y.length;
    double[] profile = new double[7];
    if (mean <= 0) {
      Arrays.fill(profile, 1.0);
      return profile;
    }
    for (int d = 0; d < 7; d++) {
      double index = counts[d] == 0 ? 1.0 : (sums[d] / counts[d]) / mean;
      profile[d] = Math.min(PROFILE_CEILING, Math.max(PROFILE_FLOOR, index));
    }
    double norm = sum(profile) / 7;
    for (int d = 0; d < 7; d++) {
      profile[d] /= norm;
    }
    return profile;
  }

  // ── forecasting from a model ───────────────────────────────────────────────

  private static double[] predict(Model m, LocalDate from, int days) {
    double[] out = new double[days];
    for (int i = 0; i < days; i++) {
      out[i] = Math.max(0, m.level() * multiplier(m, from.plusDays(i)));
    }
    return out;
  }

  private static List<BigDecimal> points(Model m, LocalDate from, int days) {
    List<BigDecimal> out = new ArrayList<>(days);
    for (double v : predict(m, from, days)) {
      out.add(dec(v, SCALE));
    }
    return out;
  }

  private static double multiplier(Model m, LocalDate day) {
    return m.profile() == null ? 1.0 : m.profile()[day.getDayOfWeek().getValue() - 1];
  }

  private static List<BigDecimal> profileOf(Model m) {
    if (m.profile() == null) {
      return List.of();
    }
    List<BigDecimal> out = new ArrayList<>(7);
    for (double v : m.profile()) {
      out.add(dec(v, SCALE));
    }
    return out;
  }

  // ── the forecast's report on itself ─────────────────────────────────────────

  private static Accuracy accuracy(double[] train, double[] held, double... prediction) {
    double mae = meanAbsoluteError(held, prediction);
    double absPctSum = 0;
    int withDemand = 0;
    double errorSum = 0;
    double actualSum = 0;
    for (int i = 0; i < held.length; i++) {
      errorSum += prediction[i] - held[i];
      actualSum += held[i];
      if (held[i] > 0) {
        absPctSum += Math.abs(held[i] - prediction[i]) / held[i];
        withDemand++;
      }
    }
    double naive = 0;
    for (int i = 1; i < train.length; i++) {
      naive += Math.abs(train[i] - train[i - 1]);
    }
    naive = train.length > 1 ? naive / (train.length - 1) : 0;
    return new Accuracy(
        held.length,
        withDemand == 0 ? null : dec(100 * absPctSum / withDemand, 2),
        actualSum <= 0 ? null : dec(100 * errorSum / actualSum, 2),
        naive <= 0 ? null : dec(mae / naive, 3));
  }

  private static double meanAbsoluteError(double[] actual, double... prediction) {
    double sum = 0;
    for (int i = 0; i < actual.length; i++) {
      sum += Math.abs(actual[i] - prediction[i]);
    }
    return actual.length == 0 ? 0 : sum / actual.length;
  }

  private static double sum(double... values) {
    double s = 0;
    for (double v : values) {
      s += v;
    }
    return s;
  }

  private static BigDecimal dec(double v, int scale) {
    return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
  }
}
