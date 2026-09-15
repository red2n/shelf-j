package com.shelfj.einvoice;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/** Reads the typed values of a document, refusing any that a schema would. */
final class Values {

  /**
   * xs:decimal's lexical form: digits with an optional point, no exponent. An exponent is refused
   * because {@code 1e999999999} is a valid Java BigDecimal whose plain form is a gigabyte.
   */
  private static final Pattern DECIMAL =
      Pattern.compile("[+-]?(\\d{1,24}(\\.\\d{0,12})?|\\.\\d{1,12})");

  /** xs:date, with the time zone xs:date allows and EN 16931 ignores. */
  private static final Pattern ISO_DATE =
      Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(Z|[+-]\\d{2}:\\d{2})?");

  private Values() {}

  static BigDecimal decimal(String text, String term) {
    if (text == null) return null;
    String s = text.strip();
    if (!DECIMAL.matcher(s).matches()) {
      throw bad(term, "is not a decimal number", s);
    }
    return new BigDecimal(s);
  }

  static BigDecimal decimal(XmlElement e, String term) {
    return e == null ? null : decimal(e.value(), term);
  }

  static LocalDate isoDate(String text, String term) {
    if (text == null) return null;
    var m = ISO_DATE.matcher(text.strip());
    if (!m.matches()) throw bad(term, "is not a date as yyyy-MM-dd", text);
    try {
      return LocalDate.parse(m.group(1));
    } catch (DateTimeParseException e) {
      throw bad(term, "is not a calendar date", text, e);
    }
  }

  /** A CII date: a {@code DateTimeString} or {@code DateString} in format 102, yyyyMMdd. */
  static LocalDate ciiDate(XmlElement dateString, String term) {
    if (dateString == null || dateString.value() == null) return null;
    String format = dateString.attribute("format");
    if (format != null && !"102".equals(format)) {
      throw bad(term, "uses date format " + format + "; EN 16931 dates are format 102", format);
    }
    try {
      return LocalDate.parse(dateString.value(), DateTimeFormatter.BASIC_ISO_DATE);
    } catch (DateTimeParseException e) {
      throw bad(term, "is not a date as yyyyMMdd", dateString.value(), e);
    }
  }

  /** xs:boolean, whose lexical space is {@code true}, {@code false}, {@code 1} and {@code 0}. */
  static boolean indicator(String text, String term) {
    String s = text == null ? "" : text.strip();
    if ("true".equals(s) || "1".equals(s)) return true;
    if ("false".equals(s) || "0".equals(s)) return false;
    throw bad(term, "is neither true nor false", s);
  }

  /**
   * The group, or {@code null} when every term in it is absent — so a reader returns the same
   * {@code null} for a group that is missing and one written empty, and a document read twice reads
   * the same.
   */
  static <T extends Record> T orNull(T group) {
    for (RecordComponent c : group.getClass().getRecordComponents()) {
      Object v;
      try {
        v = c.getAccessor().invoke(group);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot read " + c.getName(), e);
      }
      boolean present = v instanceof List<?> list ? !list.isEmpty() : v != null;
      if (present) return group;
    }
    return null;
  }

  private static EInvoiceFormatException bad(String term, String problem, String value) {
    return bad(term, problem, value, null);
  }

  private static EInvoiceFormatException bad(
      String term, String problem, String value, Throwable cause) {
    String shown = value.length() > 40 ? value.substring(0, 40) + "…" : value;
    return new EInvoiceFormatException(
        "BAD_VALUE", term + " " + problem + ": \"" + shown + "\"", cause);
  }
}
