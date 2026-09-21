package com.storeql.purchase.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Whether a date may be posted to, given the accounting periods inventory-svc holds for a store.
 *
 * <p>A period is a calendar month: the one whose {@code periodDate} shares the posting date's year
 * and month. A month with no period opened for it is not controlled — periods are optional, and a
 * business that has never opened one must not find its goods receipts refused. A month whose period
 * is CLOSED is refused. The rule is deliberately narrower than "the latest period at or before the
 * date": under that rule closing June with July not yet opened would block every posting from the
 * first of July onwards, which is a surprise nobody wants on the first of July.
 */
public final class PeriodControl {

  private PeriodControl() {}

  /** One period as inventory-svc reports it. */
  public record Period(LocalDate periodDate, String status) {}

  public static final String OPEN = "OPEN";
  public static final String CLOSED = "CLOSED";

  /**
   * The status of the period a date falls in.
   *
   * @param periods the store's periods, in any order
   * @param date the posting date
   * @return {@code OPEN} or {@code CLOSED}, or empty when no period covers the month
   */
  public static Optional<String> statusOn(List<Period> periods, LocalDate date) {
    YearMonth month = YearMonth.from(date);
    return periods.stream()
        .filter(p -> p.periodDate() != null && YearMonth.from(p.periodDate()).equals(month))
        .map(Period::status)
        .findFirst();
  }

  /**
   * Whether posting to a date is refused.
   *
   * @param periods the store's periods
   * @param date the posting date
   * @return {@code true} only when the month's period exists and is CLOSED
   */
  public static boolean closedOn(List<Period> periods, LocalDate date) {
    return statusOn(periods, date).map(CLOSED::equalsIgnoreCase).orElse(false);
  }
}
