package com.shelfj.gs1;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The six-digit {@code YYMMDD} a GS1 code carries, and the two rules a naive reading gets wrong.
 *
 * <p><b>A day of {@code 00} means the end of that month.</b> {@code 261200} is 31 December 2026,
 * not an invalid date. Food and medicines are marked this way constantly — "best before end
 * December" — so a till that treats it as unparseable stops a legitimate sale, and one that reads
 * it as the 1st sells a month's stock a month early or refuses it a month late.
 *
 * <p><b>The century comes from a 50-year window, not from prepending "20".</b> The standard fixes
 * it relative to the current year: a two-digit year more than 50 years ahead belongs to the last
 * century. Prepending "20" works until it doesn't — and the code that has to read 2051 is already
 * being printed on products with long lives.
 */
final class Gs1Dates {

  private Gs1Dates() {}

  /**
   * The date a six-digit GS1 field names, or empty when it names none.
   *
   * <p>Empty rather than an exception, and empty rather than a guess: a date field that is not a
   * date is a misread label, and inventing one would put a wrong expiry on a batch.
   */
  static Optional<LocalDate> parse(String yymmdd) {
    return parse(yymmdd, LocalDate.now().getYear());
  }

  /**
   * As {@link #parse(String)}, against a stated current year so the century rule is testable
   * without waiting twenty-five years for the window to move.
   */
  static Optional<LocalDate> parse(String yymmdd, int currentYear) {
    if (yymmdd == null || yymmdd.length() != 6 || !Gtin.isDigits(yymmdd)) return Optional.empty();
    int yy = Integer.parseInt(yymmdd.substring(0, 2));
    int month = Integer.parseInt(yymmdd.substring(2, 4));
    int day = Integer.parseInt(yymmdd.substring(4, 6));
    if (month < 1 || month > 12) return Optional.empty();

    int year = century(yy, currentYear);
    YearMonth ym = YearMonth.of(year, month);
    // Day 00: the end of the month, which is what "best before end December" prints as.
    if (day == 0) return Optional.of(ym.atEndOfMonth());
    if (day > ym.lengthOfMonth()) return Optional.empty();
    return Optional.of(ym.atDay(day));
  }

  /**
   * The century for a two-digit year, by GS1's 50-year window.
   *
   * <p>The difference between the two-digit year and the current century's equivalent decides it:
   * more than 50 years ahead is the previous century, 50 or more behind is the next one. In 2026
   * that puts 49 in 2049 and 51 in 1951.
   */
  private static int century(int yy, int currentYear) {
    int currentYy = currentYear % 100;
    int base = currentYear - currentYy;
    int difference = yy - currentYy;
    if (difference >= 51) return base - 100 + yy;
    if (difference <= -51) return base + 100 + yy;
    return base + yy;
  }
}
