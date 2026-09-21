package com.storeql.tenant.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.tenant.domain.Switching.Dates;
import com.storeql.tenant.domain.Switching.Refused;
import com.storeql.tenant.domain.Switching.Stage;
import com.storeql.tenant.domain.Switching.Switch;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class SwitchingTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
  private static final Set<String> ALL = Set.of("order-svc", "tenant-svc");

  private static Switch notice(String intent, LocalDate ends) {
    return Switching.notice(
        UUID.randomUUID(),
        UUID.randomUUID(),
        intent,
        UUID.randomUUID(),
        Switching.forNotice(intent, TODAY, ends));
  }

  private static Switch with(Switch s, Dates d, Instant extendedAt) {
    return new Switch(
        s.id(),
        s.tenantId(),
        s.intent(),
        s.noticeGivenAt(),
        s.noticeGivenBy(),
        d.noticeEndsOn(),
        d.transitionEndsOn(),
        extendedAt,
        extendedAt == null ? null : UUID.randomUUID(),
        d.retrievalEndsOn(),
        d.erasureDueOn(),
        s.cancelledAt(),
        s.cancelledBy(),
        s.cancelReason(),
        s.erasureEventId(),
        s.erasureStartedAt());
  }

  private static Switch cancelled(Switch s) {
    return closed(s, true);
  }

  private static Switch erasing(Switch s) {
    return closed(s, false);
  }

  /** The notice withdrawn, or with its erasure started. */
  private static Switch closed(Switch s, boolean withdrawn) {
    return new Switch(
        s.id(),
        s.tenantId(),
        s.intent(),
        s.noticeGivenAt(),
        s.noticeGivenBy(),
        s.noticeEndsOn(),
        s.transitionEndsOn(),
        s.extendedAt(),
        s.extendedBy(),
        s.retrievalEndsOn(),
        s.erasureDueOn(),
        withdrawn ? Instant.now() : null,
        withdrawn ? UUID.randomUUID() : null,
        withdrawn ? "changed our mind" : null,
        withdrawn ? null : UUID.randomUUID(),
        withdrawn ? null : Instant.now());
  }

  private static void refused(String code, Executable e) {
    Refused r = assertThrows(Refused.class, e);
    assertThat(r.getMessage(), r.code(), is(code));
  }

  @Test
  @DisplayName("A switch: notice, 30 days' transition, 30 days' retrieval, erasure the day after")
  void switchDates() {
    Dates d = Switching.forNotice(Switching.SWITCH, TODAY, LocalDate.of(2026, 10, 15));
    assertThat(d.transitionEndsOn(), is(LocalDate.of(2026, 11, 14)));
    assertThat(d.retrievalEndsOn(), is(LocalDate.of(2026, 12, 14)));
    assertThat(d.erasureDueOn(), is(LocalDate.of(2026, 12, 15)));

    Switch s = notice(Switching.SWITCH, LocalDate.of(2026, 10, 15));
    assertThat(Switching.stage(s, TODAY, Set.of(), ALL), is(Stage.NOTICE));
    assertThat(Switching.stage(s, LocalDate.of(2026, 10, 15), Set.of(), ALL), is(Stage.NOTICE));
    assertThat(Switching.stage(s, LocalDate.of(2026, 10, 16), Set.of(), ALL), is(Stage.TRANSITION));
    assertThat(Switching.stage(s, LocalDate.of(2026, 11, 14), Set.of(), ALL), is(Stage.TRANSITION));
    assertThat(Switching.stage(s, LocalDate.of(2026, 11, 15), Set.of(), ALL), is(Stage.RETRIEVAL));
    assertThat(Switching.stage(s, LocalDate.of(2026, 12, 14), Set.of(), ALL), is(Stage.RETRIEVAL));
    assertThat(Switching.erasureDue(s, LocalDate.of(2026, 12, 14)), is(false));
    assertThat(
        Switching.stage(s, LocalDate.of(2026, 12, 15), Set.of(), ALL), is(Stage.ERASURE_DUE));
    assertThat(Switching.erasureDue(s, LocalDate.of(2026, 12, 15)), is(true));

    Switch started = erasing(s);
    assertThat(Switching.erasureDue(started, LocalDate.of(2027, 1, 1)), is(false));
    assertThat(Switching.stage(started, TODAY, Set.of("order-svc"), ALL), is(Stage.ERASING));
    assertThat(Switching.stage(started, TODAY, ALL, ALL), is(Stage.ERASED));
  }

  @Test
  @DisplayName("Erasure alone falls due when notice ends; today's notice erases today")
  void eraseDates() {
    Switch now = notice(Switching.ERASE, TODAY);
    assertThat(now.erasureDueOn(), is(TODAY));
    assertThat(Switching.stage(now, TODAY, Set.of(), ALL), is(Stage.ERASURE_DUE));
    assertThat(Switching.erasureDue(now, TODAY), is(true));

    Switch later = notice(Switching.ERASE, LocalDate.of(2026, 10, 1));
    assertThat(later.transitionEndsOn(), is(LocalDate.of(2026, 10, 1)));
    assertThat(Switching.stage(later, TODAY, Set.of(), ALL), is(Stage.NOTICE));
    assertThat(Switching.erasureDue(later, LocalDate.of(2026, 9, 30)), is(false));
  }

  @Test
  @DisplayName("Notice runs today to two months ahead, by calendar month, and nothing else")
  void noticeRefused() {
    assertThat(
        Switching.forNotice(Switching.SWITCH, TODAY, LocalDate.of(2026, 11, 15)).noticeEndsOn(),
        is(LocalDate.of(2026, 11, 15)));
    refused(
        "SWITCHING_NOTICE_TOO_LONG",
        () -> Switching.forNotice(Switching.SWITCH, TODAY, LocalDate.of(2026, 11, 16)));
    refused(
        "SWITCHING_NOTICE_INVALID",
        () -> Switching.forNotice(Switching.SWITCH, TODAY, TODAY.minusDays(1)));
    refused("SWITCHING_NOTICE_INVALID", () -> Switching.forNotice(Switching.ERASE, TODAY, null));
    refused("SWITCHING_INTENT_UNKNOWN", () -> Switching.forNotice("LEAVE", TODAY, TODAY));
    refused("SWITCHING_INTENT_UNKNOWN", () -> Switching.forNotice(null, TODAY, TODAY));
    LocalDate newYearsEve = LocalDate.of(2026, 12, 31);
    assertThat(
        Switching.forNotice(Switching.SWITCH, newYearsEve, LocalDate.of(2027, 2, 28))
            .noticeEndsOn(),
        is(LocalDate.of(2027, 2, 28)));
    refused(
        "SWITCHING_NOTICE_TOO_LONG",
        () -> Switching.forNotice(Switching.SWITCH, newYearsEve, LocalDate.of(2027, 3, 1)));
  }

  @Test
  @DisplayName(
      "The transitional period is extended once, by a switch, while it runs, to seven months")
  void extension() {
    Switch s = notice(Switching.SWITCH, LocalDate.of(2026, 10, 15));
    Dates d = Switching.extended(s, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31));
    assertThat(d.transitionEndsOn(), is(LocalDate.of(2027, 1, 31)));
    assertThat(d.retrievalEndsOn(), is(LocalDate.of(2027, 3, 2)));
    assertThat(d.erasureDueOn(), is(LocalDate.of(2027, 3, 3)));

    Switch extended = with(s, d, Instant.now());
    refused(
        "SWITCHING_ALREADY_EXTENDED",
        () -> Switching.extended(extended, TODAY, LocalDate.of(2027, 2, 1)));
    refused(
        "SWITCHING_EXTENSION_INVALID", () -> Switching.extended(s, TODAY, s.transitionEndsOn()));
    refused("SWITCHING_EXTENSION_INVALID", () -> Switching.extended(s, TODAY, null));
    refused(
        "SWITCHING_EXTENSION_TOO_LONG",
        () -> Switching.extended(s, TODAY, LocalDate.of(2027, 5, 16)));
    assertThat(
        Switching.extended(s, TODAY, LocalDate.of(2027, 5, 15)).transitionEndsOn(),
        is(LocalDate.of(2027, 5, 15)));
    refused(
        "SWITCHING_TRANSITION_OVER",
        () -> Switching.extended(s, LocalDate.of(2026, 11, 15), LocalDate.of(2027, 1, 1)));
    refused(
        "SWITCHING_NOT_EXTENDABLE",
        () ->
            Switching.extended(
                notice(Switching.ERASE, LocalDate.of(2026, 10, 1)),
                TODAY,
                LocalDate.of(2026, 12, 1)));
    refused(
        "SWITCHING_CLOSED",
        () -> Switching.extended(cancelled(s), TODAY, LocalDate.of(2027, 1, 1)));
    refused(
        "SWITCHING_CLOSED", () -> Switching.extended(erasing(s), TODAY, LocalDate.of(2027, 1, 1)));
  }

  @Test
  @DisplayName("Notice is withdrawn while it runs, and not after it ends or closes")
  void withdrawal() {
    Switch s = notice(Switching.SWITCH, LocalDate.of(2026, 10, 15));
    Switching.cancellable(s, TODAY);
    Switching.cancellable(s, LocalDate.of(2026, 10, 15));
    refused(
        "SWITCHING_TOO_LATE_TO_CANCEL", () -> Switching.cancellable(s, LocalDate.of(2026, 10, 16)));
    refused("SWITCHING_CLOSED", () -> Switching.cancellable(cancelled(s), TODAY));
    refused("SWITCHING_CLOSED", () -> Switching.cancellable(erasing(s), TODAY));
    Switch gone = cancelled(s);
    assertThat(Switching.stage(gone, LocalDate.of(2027, 1, 1), Set.of(), ALL), is(Stage.CANCELLED));
    assertThat(Switching.erasureDue(gone, LocalDate.of(2027, 1, 1)), is(false));
    Refused r =
        assertThrows(
            Refused.class,
            () -> Switching.forNotice(Switching.SWITCH, TODAY, LocalDate.of(2027, 1, 1)));
    assertThat(r.getMessage(), containsString("2026-11-15"));
  }
}
