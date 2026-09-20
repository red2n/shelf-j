package com.storeql.tenant.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * A business leaving the platform (21.14, EU Data Act (EU) 2023/2854 art.25): the notice it gives,
 * the transitional period and its one extension, the retrieval period, and the day its data may be
 * erased. Pure: the dates follow from the notice and the day it is asked on.
 *
 * <ul>
 *   <li>Notice runs at most two months (art.25(2)(a)); a business may give less.
 *   <li>To switch, a transitional period of 30 calendar days follows (art.25(2)(a)), extendable
 *       once by the business (art.25(4)), here to at most seven months after notice ends, the
 *       longest transitional period the Act contemplates; the service keeps running throughout.
 *   <li>A retrieval period of at least 30 calendar days follows it (art.25(2)(g)); the data is
 *       erased the day after (art.25(2)(h)).
 *   <li>A business that asks only for erasure has it at the end of its notice (art.25(3)).
 *   <li>Notice can be withdrawn while it runs, not after.
 * </ul>
 */
public final class Switching {

  /** Take the data to another provider or the business's own systems. */
  public static final String SWITCH = "SWITCH";

  /** Have the data erased. */
  public static final String ERASE = "ERASE";

  public static final int MAX_NOTICE_MONTHS = 2;
  public static final int TRANSITION_DAYS = 30;
  public static final int RETRIEVAL_DAYS = 30;
  public static final int MAX_TRANSITION_MONTHS = 7;

  private Switching() {}

  /** Where a notice stands on a day. */
  public enum Stage {
    NOTICE,
    TRANSITION,
    RETRIEVAL,
    ERASURE_DUE,
    ERASING,
    ERASED,
    CANCELLED
  }

  /** A refusal the rules name. */
  public static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    public Refused(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  /** The dates a notice sets. */
  public record Dates(
      LocalDate noticeEndsOn,
      LocalDate transitionEndsOn,
      LocalDate retrievalEndsOn,
      LocalDate erasureDueOn) {}

  /** A notice as recorded. */
  public record Switch(
      UUID id,
      UUID tenantId,
      String intent,
      Instant noticeGivenAt,
      UUID noticeGivenBy,
      LocalDate noticeEndsOn,
      LocalDate transitionEndsOn,
      Instant extendedAt,
      UUID extendedBy,
      LocalDate retrievalEndsOn,
      LocalDate erasureDueOn,
      Instant cancelledAt,
      UUID cancelledBy,
      String cancelReason,
      UUID erasureEventId,
      Instant erasureStartedAt) {

    public boolean cancelled() {
      return cancelledAt != null;
    }

    public boolean erasureStarted() {
      return erasureStartedAt != null;
    }

    public boolean extended() {
      return extendedAt != null;
    }
  }

  /** What one service erased, as it announced. */
  public record Evidence(
      UUID id,
      UUID erasureEventId,
      String service,
      int rowsErased,
      String tables,
      Instant erasedAt,
      Instant recordedAt) {}

  /** A notice given now, with the dates its rules set; the database stamps when. */
  public static Switch notice(UUID id, UUID tenantId, String intent, UUID by, Dates d) {
    return new Switch(
        id,
        tenantId,
        intent,
        null,
        by,
        d.noticeEndsOn(),
        d.transitionEndsOn(),
        null,
        null,
        d.retrievalEndsOn(),
        d.erasureDueOn(),
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * The dates a notice given today sets.
   *
   * @throws Refused {@code SWITCHING_INTENT_UNKNOWN}, {@code SWITCHING_NOTICE_INVALID} or {@code
   *     SWITCHING_NOTICE_TOO_LONG}
   */
  public static Dates forNotice(String intent, LocalDate today, LocalDate noticeEndsOn) {
    if (!SWITCH.equals(intent) && !ERASE.equals(intent)) {
      throw new Refused(
          "SWITCHING_INTENT_UNKNOWN",
          "intent is SWITCH, to take the data elsewhere, or ERASE, to have it erased");
    }
    if (noticeEndsOn == null) {
      throw new Refused("SWITCHING_NOTICE_INVALID", "give the day the notice ends");
    }
    if (noticeEndsOn.isBefore(today)) {
      throw new Refused("SWITCHING_NOTICE_INVALID", "notice cannot end before today, " + today);
    }
    LocalDate latest = today.plusMonths(MAX_NOTICE_MONTHS);
    if (noticeEndsOn.isAfter(latest)) {
      throw new Refused(
          "SWITCHING_NOTICE_TOO_LONG",
          "notice runs at most two months, to " + latest + " (EU Data Act art.25(2)(a))");
    }
    return dates(intent, noticeEndsOn, noticeEndsOn.plusDays(TRANSITION_DAYS));
  }

  /**
   * The dates once the transitional period is extended.
   *
   * @throws Refused {@code SWITCHING_CLOSED}, {@code SWITCHING_NOT_EXTENDABLE}, {@code
   *     SWITCHING_ALREADY_EXTENDED}, {@code SWITCHING_TRANSITION_OVER}, {@code
   *     SWITCHING_EXTENSION_INVALID} or {@code SWITCHING_EXTENSION_TOO_LONG}
   */
  public static Dates extended(Switch s, LocalDate today, LocalDate transitionEndsOn) {
    open(s);
    if (!SWITCH.equals(s.intent())) {
      throw new Refused(
          "SWITCHING_NOT_EXTENDABLE", "only a switch has a transitional period to extend");
    }
    if (s.extended()) {
      throw new Refused(
          "SWITCHING_ALREADY_EXTENDED",
          "the transitional period can be extended once (EU Data Act art.25(4))");
    }
    if (today.isAfter(s.transitionEndsOn())) {
      throw new Refused(
          "SWITCHING_TRANSITION_OVER", "the transitional period ended on " + s.transitionEndsOn());
    }
    if (transitionEndsOn == null || !transitionEndsOn.isAfter(s.transitionEndsOn())) {
      throw new Refused(
          "SWITCHING_EXTENSION_INVALID", "an extension ends after " + s.transitionEndsOn());
    }
    LocalDate latest = s.noticeEndsOn().plusMonths(MAX_TRANSITION_MONTHS);
    if (transitionEndsOn.isAfter(latest)) {
      throw new Refused(
          "SWITCHING_EXTENSION_TOO_LONG",
          "a transitional period runs at most seven months, to " + latest);
    }
    return dates(SWITCH, s.noticeEndsOn(), transitionEndsOn);
  }

  /**
   * Refuses withdrawing a notice that has ended or closed.
   *
   * @throws Refused {@code SWITCHING_CLOSED} or {@code SWITCHING_TOO_LATE_TO_CANCEL}
   */
  public static void cancellable(Switch s, LocalDate today) {
    open(s);
    if (today.isAfter(s.noticeEndsOn())) {
      throw new Refused(
          "SWITCHING_TOO_LATE_TO_CANCEL",
          "notice ended on " + s.noticeEndsOn() + "; it can be withdrawn only while it runs");
    }
  }

  /** Whether the business's data is due for erasure today. */
  public static boolean erasureDue(Switch s, LocalDate today) {
    return !s.cancelled() && !s.erasureStarted() && !today.isBefore(s.erasureDueOn());
  }

  /**
   * Where the notice stands.
   *
   * @param reported the services whose erasure evidence has arrived
   * @param expected every service that holds the business's data
   */
  public static Stage stage(Switch s, LocalDate today, Set<String> reported, Set<String> expected) {
    if (s.cancelled()) return Stage.CANCELLED;
    if (s.erasureStarted()) return reported.containsAll(expected) ? Stage.ERASED : Stage.ERASING;
    if (!today.isBefore(s.erasureDueOn())) return Stage.ERASURE_DUE;
    if (!today.isAfter(s.noticeEndsOn())) return Stage.NOTICE;
    if (!today.isAfter(s.transitionEndsOn())) return Stage.TRANSITION;
    return Stage.RETRIEVAL;
  }

  private static void open(Switch s) {
    if (s.cancelled() || s.erasureStarted()) {
      throw new Refused("SWITCHING_CLOSED", "this notice is no longer running");
    }
  }

  private static Dates dates(String intent, LocalDate noticeEndsOn, LocalDate transitionEndsOn) {
    if (ERASE.equals(intent)) {
      return new Dates(noticeEndsOn, noticeEndsOn, noticeEndsOn, noticeEndsOn);
    }
    LocalDate retrievalEndsOn = transitionEndsOn.plusDays(RETRIEVAL_DAYS);
    return new Dates(noticeEndsOn, transitionEndsOn, retrievalEndsOn, retrievalEndsOn.plusDays(1));
  }
}
