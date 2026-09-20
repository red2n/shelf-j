package com.storeql.purchase.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.purchase.domain.PeriodControl.Period;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A period is the calendar month its date is in; a month with no period is not controlled. */
class PeriodControlTest {

  private static final Period JUNE_CLOSED = new Period(LocalDate.of(2026, 6, 1), "CLOSED");
  private static final Period JULY_OPEN = new Period(LocalDate.of(2026, 7, 1), "OPEN");

  @Test
  @DisplayName("A date in a closed month is refused; in an open month it is allowed")
  void closedAndOpen() {
    var periods = List.of(JUNE_CLOSED, JULY_OPEN);
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 6, 15)), is(true));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 6, 1)), is(true));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 6, 30)), is(true));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 7, 15)), is(false));
    assertThat(PeriodControl.statusOn(periods, LocalDate.of(2026, 7, 2)), is(Optional.of("OPEN")));
  }

  @Test
  @DisplayName("Closing June does not close August: a month nobody opened is not controlled")
  void unopenedMonthIsFree() {
    var periods = List.of(JUNE_CLOSED);
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 8, 1)), is(false));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 5, 31)), is(false));
    assertThat(PeriodControl.statusOn(periods, LocalDate.of(2026, 8, 1)), is(Optional.empty()));
  }

  @Test
  @DisplayName("No periods at all: nothing is refused")
  void noPeriods() {
    assertThat(PeriodControl.closedOn(List.of(), LocalDate.of(2026, 6, 15)), is(false));
  }

  @Test
  @DisplayName("The same month of another year is another month")
  void yearMatters() {
    var periods = List.of(new Period(LocalDate.of(2025, 6, 1), "CLOSED"));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 6, 15)), is(false));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2025, 6, 15)), is(true));
  }

  @Test
  @DisplayName("Status is compared without regard to case, and a null date is skipped")
  void tolerantOfShape() {
    var periods =
        List.of(new Period(null, "CLOSED"), new Period(LocalDate.of(2026, 6, 10), "closed"));
    assertThat(PeriodControl.closedOn(periods, LocalDate.of(2026, 6, 15)), is(true));
  }
}
