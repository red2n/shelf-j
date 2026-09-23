package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.inventory.domain.Forecasting.Forecast;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statistical demand forecast (06.x) as pure arithmetic over a zero-filled daily series: what
 * it says about a steady seller, an intermittent one, a weekly pattern, and how honestly it reports
 * its own accuracy on a hold-out.
 */
class ForecastingTest {

  private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 20);

  private static List<BigDecimal> series(double... values) {
    List<BigDecimal> out = new ArrayList<>();
    for (double v : values) out.add(BigDecimal.valueOf(v));
    return out;
  }

  private static List<BigDecimal> constant(int days, double value) {
    return new ArrayList<>(Collections.nCopies(days, BigDecimal.valueOf(value)));
  }

  private static double d(BigDecimal b) {
    return b.doubleValue();
  }

  @Test
  @DisplayName(
      "A steady seller forecasts itself: five a day stays five a day, and the hold-out says so")
  void steadyDemandForecastsItself() {
    Forecast f = Forecasting.forecast(constant(60, 5), SUNDAY, 28);
    assertThat(f.method(), is(Forecasting.METHOD_SES));
    assertThat(f.intermittent(), is(false));
    assertThat(f.historyDays(), is(60));
    assertThat(f.fromDay(), is(SUNDAY.plusDays(1)));
    assertThat(f.points().size(), is(28));
    assertThat(d(f.level()), closeTo(5.0, 0.01));
    for (BigDecimal p : f.points()) assertThat(d(p), closeTo(5.0, 0.01));
    assertThat(d(f.expectedOver(7)), closeTo(35.0, 0.1));
    assertThat(f.accuracy().holdoutDays(), greaterThanOrEqualTo(7));
    assertThat(d(f.accuracy().mape()), closeTo(0.0, 0.01));
    assertThat(d(f.accuracy().bias()), closeTo(0.0, 0.01));
  }

  @Test
  @DisplayName(
      "One unit every fourth day is intermittent: Croston with the SBA correction, a quarter a day less a little")
  void intermittentDemandUsesCrostonSba() {
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 84; i++) daily.add(i % 4 == 3 ? BigDecimal.ONE : BigDecimal.ZERO);
    Forecast f = Forecasting.forecast(daily, SUNDAY, 28);
    assertThat(f.method(), is(Forecasting.METHOD_CROSTON_SBA));
    assertThat(f.intermittent(), is(true));
    assertThat(f.weekdayProfile().isEmpty(), is(true));
    // 28 days at a quarter a day is 7; the SBA correction (1 - alpha/2) takes a little off.
    assertThat(d(f.expectedOver(28)), greaterThan(5.0));
    assertThat(d(f.expectedOver(28)), lessThanOrEqualTo(7.0));
    for (BigDecimal p : f.points()) assertThat(d(p), greaterThan(0.0));
  }

  @Test
  @DisplayName(
      "Weekends that sell double show in the profile, and the week's points still add up to the week")
  void weekendsCarryMoreOfTheWeek() {
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 56; i++) {
      DayOfWeek dow = SUNDAY.minusDays(55 - i).getDayOfWeek();
      boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
      daily.add(BigDecimal.valueOf(weekend ? 20 : 10));
    }
    Forecast f = Forecasting.forecast(daily, SUNDAY, 14);
    assertThat(f.method(), is(Forecasting.METHOD_SES));
    assertThat(f.weekdayProfile().size(), is(7));
    // Monday first, Sunday last.
    assertThat(d(f.weekdayProfile().get(0)), lessThan(0.9));
    assertThat(d(f.weekdayProfile().get(5)), greaterThan(1.3));
    assertThat(d(f.weekdayProfile().get(6)), greaterThan(1.3));
    // fromDay is a Monday: the first five points are weekdays, the sixth and seventh the weekend.
    assertThat(f.fromDay().getDayOfWeek(), is(DayOfWeek.MONDAY));
    assertThat(d(f.points().get(5)), greaterThan(d(f.points().get(0))));
    assertThat(d(f.expectedOver(7)), closeTo(90.0, 9.0));
    assertThat(d(f.accuracy().mape()), lessThan(15.0));
  }

  @Test
  @DisplayName("Fewer than fourteen days is a mean, with no claim of accuracy")
  void tooLittleHistoryIsAMeanWithNoClaimOfAccuracy() {
    Forecast f = Forecasting.forecast(series(2, 4, 6, 4, 2, 4, 6, 4, 2, 4), SUNDAY, 7);
    assertThat(f.method(), is(Forecasting.METHOD_MEAN));
    assertThat(d(f.level()), closeTo(3.8, 0.01));
    assertThat(f.accuracy().holdoutDays(), is(0));
    assertThat(f.accuracy().mape(), is(nullValue()));
    assertThat(f.accuracy().bias(), is(nullValue()));
    assertThat(f.accuracy().mase(), is(nullValue()));
    assertThat(f.points().size(), is(7));
  }

  @Test
  @DisplayName("No demand at all forecasts nothing, and says nothing about accuracy")
  void noDemandForecastsNothing() {
    Forecast f = Forecasting.forecast(constant(30, 0), SUNDAY, 7);
    assertThat(f.method(), is(Forecasting.METHOD_MEAN));
    assertThat(d(f.level()), is(0.0));
    assertThat(f.points(), everyItem(is(BigDecimal.ZERO.setScale(4))));
    assertThat(f.accuracy().mape(), is(nullValue()));
  }

  @Test
  @DisplayName(
      "A step up inside the hold-out reads as under-forecasting: a negative bias, and an error that is not zero")
  void aStepUpShowsAsNegativeBias() {
    // Four and six on alternate days, so the naive one-day-back forecast has an error to scale by;
    // then ten days at fifteen inside the hold-out.
    List<BigDecimal> daily = new ArrayList<>();
    for (int i = 0; i < 40; i++) daily.add(BigDecimal.valueOf(i % 2 == 0 ? 4 : 6));
    daily.addAll(constant(10, 15));
    Forecast f = Forecasting.forecast(daily, SUNDAY, 7);
    assertThat(d(f.accuracy().bias()), lessThan(0.0));
    assertThat(d(f.accuracy().mape()), greaterThan(0.0));
    assertThat(f.accuracy().mase(), is(org.hamcrest.Matchers.notNullValue()));
  }

  @Test
  @DisplayName("Points are never negative and always carry four decimals")
  void pointsAreNeverNegativeAndScaleIsFour() {
    Forecast f =
        Forecasting.forecast(
            series(3, 0, 0, 9, 1, 0, 2, 0, 0, 0, 5, 0, 1, 0, 0, 7, 0, 0, 2, 1), SUNDAY, 10);
    for (BigDecimal p : f.points()) {
      assertThat(p.signum(), greaterThanOrEqualTo(0));
      assertThat(p.scale(), is(4));
    }
    assertThat(f.expectedOver(3).scale(), is(4));
  }
}
