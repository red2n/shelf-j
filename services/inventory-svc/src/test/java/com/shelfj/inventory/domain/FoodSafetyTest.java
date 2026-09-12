package com.shelfj.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.shelfj.ids.Ids;
import com.shelfj.inventory.domain.FoodSafety.CheckType;
import com.shelfj.inventory.domain.FoodSafety.DueStatus;
import com.shelfj.inventory.domain.FoodSafety.Kind;
import com.shelfj.inventory.domain.FoodSafety.Limits;
import com.shelfj.inventory.domain.FoodSafety.MonitoringPoint;
import com.shelfj.inventory.domain.FoodSafety.PointStatus;
import com.shelfj.inventory.domain.FoodSafety.Result;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FoodSafetyTest {

  private static final Limits CHILLED = new Limits(null, dec("8.00"));

  @Test
  void aReadingOnTheLimitPassesAndOneAboveFails() {
    assertThat(CHILLED.judge(dec("8.00")), is(Result.PASS));
    assertThat(CHILLED.judge(dec("8.01")), is(Result.FAIL));
    assertThat(new Limits(dec("63"), null).judge(dec("62.99")), is(Result.FAIL));
  }

  @Test
  void aPointMayTightenItsTypeButNeverLoosenIt() {
    assertThat(new Limits(null, dec("5")).isNoLaxerThan(CHILLED), is(true));
    assertThat(new Limits(dec("0"), dec("8")).isNoLaxerThan(CHILLED), is(true));
    assertThat(new Limits(null, dec("10")).isNoLaxerThan(CHILLED), is(false));
    // Dropping a bound the type sets is loosening it, however it is phrased.
    assertThat(new Limits(dec("0"), null).isNoLaxerThan(CHILLED), is(false));
  }

  @Test
  void tighteningATypeLaterStillBindsAnOlderPoint() {
    Limits olderPoint = new Limits(dec("0"), dec("8"));
    Limits typeNow = new Limits(null, dec("5"));
    Limits effective = olderPoint.tightestWith(typeNow);
    assertThat(effective.min(), is(dec("0")));
    assertThat(effective.max(), is(dec("5")));
    assertThat(Limits.NONE.tightestWith(Limits.NONE).max(), is(nullValue()));
  }

  @Test
  void aNeverCheckedPointIsDueOneIntervalAfterItWasSetUp() {
    Instant created = Instant.parse("2026-09-11T08:00:00Z");
    PointStatus status = status(created, null, 4);
    assertThat(status.nextDueAt(), is(Instant.parse("2026-09-11T12:00:00Z")));
    assertThat(status.dueStatus(created.plus(Duration.ofHours(1))), is(DueStatus.OK));
  }

  @Test
  void dueOpensForTheLastQuarterAndOverdueStartsAfterTheDueTime() {
    Instant last = Instant.parse("2026-09-11T08:00:00Z");
    PointStatus status = status(last.minus(Duration.ofDays(1)), last, 4);
    assertThat(status.dueStatus(Instant.parse("2026-09-11T10:59:59Z")), is(DueStatus.OK));
    assertThat(status.dueStatus(Instant.parse("2026-09-11T11:00:00Z")), is(DueStatus.DUE));
    assertThat(status.dueStatus(Instant.parse("2026-09-11T12:00:00Z")), is(DueStatus.DUE));
    assertThat(status.dueStatus(Instant.parse("2026-09-11T12:00:01Z")), is(DueStatus.OVERDUE));
  }

  private static PointStatus status(Instant created, Instant lastRecordedAt, int frequencyHours) {
    UUID tenant = Ids.newId();
    var type =
        new CheckType(
            Ids.newId(),
            null,
            "CHILLED_STORAGE",
            "Chilled",
            Kind.TEMPERATURE,
            CHILLED,
            "C",
            null,
            true,
            true);
    var point =
        new MonitoringPoint(
            Ids.newId(),
            tenant,
            Ids.newId(),
            null,
            "Dairy chiller",
            type.id(),
            CHILLED,
            frequencyHours,
            true,
            created,
            created);
    return new PointStatus(point, type, lastRecordedAt, null, null, 0);
  }

  private static BigDecimal dec(String s) {
    return new BigDecimal(s);
  }
}
