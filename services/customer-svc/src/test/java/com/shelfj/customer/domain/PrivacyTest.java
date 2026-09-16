package com.shelfj.customer.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The rules a person's privacy keys on (13.12). */
class PrivacyTest {

  @Test
  void aChildIsUnderEighteenOnTheDayAndOfAgeFromTheirBirthday() {
    LocalDate today = LocalDate.of(2026, 9, 16);
    assertThat(Privacy.isChild(LocalDate.of(2008, 9, 17), today), is(true));
    assertThat("eighteen today", Privacy.isChild(LocalDate.of(2008, 9, 16), today), is(false));
    assertThat(Privacy.isChild(LocalDate.of(2000, 1, 1), today), is(false));
    assertThat("no date of birth is not a child", Privacy.isChild(null, today), is(false));
  }

  @Test
  void aRequestIsDueAfterThePublishedPeriodCountedInDays() {
    Instant opened = Instant.parse("2026-09-16T23:30:00Z");
    assertThat(Privacy.dueOn(opened, 30), is(LocalDate.of(2026, 10, 16)));
    assertThat(Privacy.dueOn(opened, Privacy.MAX_RESPONSE_DAYS), is(LocalDate.of(2026, 12, 15)));
    Privacy.Request r =
        new Privacy.Request(
            null,
            null,
            null,
            "ACCESS",
            null,
            null,
            null,
            opened,
            LocalDate.of(2026, 10, 16),
            "OPEN",
            null,
            null,
            null);
    assertThat(r.overdue(LocalDate.of(2026, 10, 16)), is(false));
    assertThat(r.overdue(LocalDate.of(2026, 10, 17)), is(true));
    Privacy.Request done =
        new Privacy.Request(
            null,
            null,
            null,
            "ACCESS",
            null,
            null,
            null,
            opened,
            LocalDate.of(2026, 10, 16),
            "RESOLVED",
            "sent",
            opened,
            null);
    assertThat("settled is never overdue", done.overdue(LocalDate.of(2027, 1, 1)), is(false));
  }

  @Test
  void theLanguagesAreEnglishAndTheEighthSchedulesTwentyTwo() {
    assertThat(Privacy.LANGUAGE_ORDER, hasSize(23));
    assertThat(Privacy.LANGUAGES.size(), is(23));
    assertThat(Privacy.language(" HI "), is("hi"));
    assertThat(Privacy.language("sat"), is("sat"));
    assertThat("French is not offered", Privacy.language("fr"), nullValue());
    assertThat(Privacy.language(null), nullValue());
  }

  @Test
  void trackingPurposesAreTheOnesAChildIsSparedAndLoyaltyIsNot() {
    assertThat(Privacy.TRACKING.contains(Privacy.PURPOSE_MARKETING), is(true));
    assertThat(Privacy.TRACKING.contains(Privacy.PURPOSE_PERSONALISATION), is(true));
    assertThat(Privacy.TRACKING.contains(Privacy.PURPOSE_ANALYTICS), is(true));
    assertThat(Privacy.TRACKING.contains(Privacy.PURPOSE_LOYALTY), is(false));
    assertThat(Privacy.Settings.none(null).responseDays(), is(Privacy.DEFAULT_RESPONSE_DAYS));
    assertThat(Privacy.Settings.none(null).hasGrievanceContact(), is(false));
  }
}
