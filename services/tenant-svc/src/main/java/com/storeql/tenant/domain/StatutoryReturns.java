package com.storeql.tenant.domain;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Which statutory returns a business owes, when each falls due, and what it filed.
 *
 * <p>The platform can already produce several of these — Germany's DSFinV-K and Portugal's SAF-T
 * from order-svc, the VAT return and its MTD submission from pricing-svc. What it could not say is
 * which a given business owes, when, or whether any of them went. An auditor does not ask whether a
 * SAF-T can be generated; it asks to see the one filed for September.
 *
 * <p><b>Nothing here is stored.</b> The due date and the state are computed from the return's
 * frequency and its statutory offset against the period, every time they are read. A stored
 * deadline is a deadline that goes stale — the mistake the incident register (21.15) avoided for
 * the same reason, and the reason its state is derived from its timeline rather than kept in a
 * column.
 */
public final class StatutoryReturns {

  private StatutoryReturns() {}

  /**
   * Three ten-day periods a month: 1–10, 11–20, and the 21st to the end. France's e-reporting
   * cadence for a business on the ordinary monthly VAT regime, and a real frequency rather than a
   * rounding of "monthly" — a business reports three times a month.
   */
  public static final String DECADAL = "DECADAL";

  public static final String MONTHLY = "MONTHLY";
  public static final String QUARTERLY = "QUARTERLY";
  public static final String ANNUAL = "ANNUAL";

  public static final Set<String> FREQUENCIES = Set.of(DECADAL, MONTHLY, QUARTERLY, ANNUAL);

  /** Where a filing went. {@code SIMULATED} says on the record that nothing left the building. */
  public static final Set<String> PROVIDERS = Set.of("HMRC_MTD", "MANUAL", "SIMULATED");

  // ── the derived state ───────────────────────────────────────────────────────

  /** The period has not ended, so nothing is owed yet. */
  public static final String NOT_DUE = "NOT_DUE";

  /** The period has ended and the return has not been filed, and its date has not passed. */
  public static final String DUE = "DUE";

  /** The date has passed and nothing was filed. The only state anybody needs to act on. */
  public static final String OVERDUE = "OVERDUE";

  public static final String FILED = "FILED";

  /**
   * What a jurisdiction requires.
   *
   * @param scopeKind {@code COUNTRY} or {@code REGIME} — a regime's return reaches a country while
   *     it is a member, asked of the period's own dates rather than of a list
   * @param dueAfter ISO-8601 period added to the period's <b>exclusive</b> end, so it is what the
   *     law adds to the day the period ended: {@code P4D} lands on the 5th of the following month,
   *     which is Portugal's SAF-T date, and {@code P1M6D} on the 7th of the second month, which is
   *     the date HMRC publishes for a VAT quarter. A period and not a day count, because a month is
   *     a month: {@code P1M6D} is 7 May from a quarter to 31 March and 7 November from one to 30
   *     September, and no number of days gives you both
   * @param exportService where the export that answers it lives, or null where the platform cannot
   *     produce it at all — a link, never a proxy, because the service that owns the data serves
   *     the bytes
   */
  public record Return(
      String code,
      String scopeKind,
      String scope,
      String name,
      String frequency,
      String dueAfter,
      String exportService,
      String exportPath,
      String citation,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {

    /** Whether this return was in force over a period. */
    public boolean inForceOn(LocalDate day) {
      return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Whether the platform can produce what this return wants. */
    public boolean producible() {
      return exportService != null && exportPath != null;
    }

    /**
     * The day this return falls due for a period.
     *
     * <p>The offset is added to the exclusive end — the first day after the period — because that
     * is the day from which every instrument here counts. Adding it to the period's <em>last</em>
     * day instead would put Portugal's SAF-T on the 4th and HMRC's return on the 6th.
     *
     * @param periodEnd the day after the period's last, as every period here is exclusive
     * @throws IllegalArgumentException when {@code dueAfter} is not a period this understands,
     *     which is a seeding mistake and should fail loudly rather than answer a wrong date
     */
    public LocalDate dueOn(LocalDate periodEnd) {
      return periodEnd.plus(period(dueAfter));
    }
  }

  /**
   * One period of one return, with its date and its state worked out.
   *
   * @param state {@link #NOT_DUE}, {@link #DUE}, {@link #OVERDUE} or {@link #FILED}
   * @param filing the filing that stands, or null
   */
  public record Obligation(
      Return owed,
      LocalDate periodStart,
      LocalDate periodEnd,
      LocalDate dueOn,
      String state,
      Filing filing) {

    public boolean actionable() {
      return DUE.equals(state) || OVERDUE.equals(state);
    }
  }

  /**
   * Evidence that a return went.
   *
   * @param reference the authority's receipt, or null where the authority gives none — some do not
   * @param payloadDigest SHA-256 of what was sent, so a filing can be proved against an export
   *     produced later
   * @param supersedes the filing this one corrects; both stay on the record
   */
  public record Filing(
      UUID id,
      UUID tenantId,
      String returnCode,
      LocalDate periodStart,
      LocalDate periodEnd,
      java.time.Instant filedAt,
      UUID filedBy,
      String reference,
      String provider,
      String payloadDigest,
      UUID supersedes,
      UUID supersededBy,
      String note) {

    /** Whether this is the filing that stands, rather than one a correction has replaced. */
    public boolean stands() {
      return supersededBy == null;
    }
  }

  // ── the arithmetic ──────────────────────────────────────────────────────────

  /**
   * The period a day falls in, for a frequency.
   *
   * @return the period's first day; the period runs to {@link #periodEnd} exclusive
   */
  public static LocalDate periodStart(String frequency, LocalDate day) {
    return switch (frequency) {
      // The month's three ten-day periods. Not arithmetic on a day count: the last one is 11 days
      // long in March and 8 in February, because it ends when the month does.
      case DECADAL ->
          day.withDayOfMonth(day.getDayOfMonth() <= 10 ? 1 : day.getDayOfMonth() <= 20 ? 11 : 21);
      case MONTHLY -> day.withDayOfMonth(1);
      // Calendar quarters: a business's own VAT stagger is a per-business setting and not the
      // law's,
      // so it does not belong in reference data. When staggers are wanted they belong on the
      // subscription-like row for the business, not here.
      case QUARTERLY -> day.withDayOfMonth(1).withMonth(((day.getMonthValue() - 1) / 3) * 3 + 1);
      case ANNUAL -> day.withDayOfYear(1);
      default -> throw new IllegalArgumentException("unknown frequency: " + frequency);
    };
  }

  /** The day after a period's last, since every period here is half-open. */
  public static LocalDate periodEnd(String frequency, LocalDate periodStart) {
    return switch (frequency) {
      case DECADAL ->
          switch (periodStart.getDayOfMonth()) {
            case 1 -> periodStart.withDayOfMonth(11);
            case 11 -> periodStart.withDayOfMonth(21);
            default -> periodStart.withDayOfMonth(1).plusMonths(1);
          };
      case MONTHLY -> periodStart.plusMonths(1);
      case QUARTERLY -> periodStart.plusMonths(3);
      case ANNUAL -> periodStart.plusYears(1);
      default -> throw new IllegalArgumentException("unknown frequency: " + frequency);
    };
  }

  /** The period before a given one, for walking a calendar backwards. */
  public static LocalDate previousPeriod(String frequency, LocalDate periodStart) {
    return switch (frequency) {
      case DECADAL ->
          switch (periodStart.getDayOfMonth()) {
            case 21 -> periodStart.withDayOfMonth(11);
            case 11 -> periodStart.withDayOfMonth(1);
            default -> periodStart.minusMonths(1).withDayOfMonth(21);
          };
      case MONTHLY -> periodStart.minusMonths(1);
      case QUARTERLY -> periodStart.minusMonths(3);
      case ANNUAL -> periodStart.minusYears(1);
      default -> throw new IllegalArgumentException("unknown frequency: " + frequency);
    };
  }

  /**
   * The state of one period, on a day.
   *
   * <p>Derived, always. A filing that stands makes it {@link #FILED} whatever the dates say — a
   * return filed late is filed, and calling it overdue afterwards would misrepresent the record.
   */
  public static String stateOf(
      LocalDate periodEnd, LocalDate dueOn, Filing standing, LocalDate asOf) {
    if (standing != null) return FILED;
    if (asOf.isBefore(periodEnd)) return NOT_DUE;
    return asOf.isAfter(dueOn) ? OVERDUE : DUE;
  }

  /**
   * {@code P1M7D} and the like.
   *
   * @throws IllegalArgumentException on anything else, because a return whose offset cannot be read
   *     must not quietly become due on the day its period ended
   */
  static Period period(String iso) {
    try {
      Period p = Period.parse(iso);
      if (p.isZero() || p.isNegative()) {
        throw new IllegalArgumentException("a return is not due before its period ends: " + iso);
      }
      return p;
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("not a period: " + iso, e);
    }
  }

  /** Every code the platform seeds, so a caller names one rather than inventing it. */
  public static List<String> seededCodes() {
    return List.of("SAFT_PT", "DSFINVK_DE", "VAT_RETURN_UK", "EC_SALES_LIST");
  }
}
