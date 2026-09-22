package com.storeql.notification.template;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * What goes into a message's gaps, kept as what it is — an amount of money, a day, a moment, a
 * number — until the language it is written in is known. Then each is written the way that language
 * writes it: {@code £12.50} in English, {@code 12,50 GBP} in Polish; {@code 21 September 2026} or
 * {@code 21 września 2026}. Text is text in every language, and a template writes its own words.
 */
public final class Values {

  private sealed interface V permits Text, Money, Num, Day, Moment, Flag, Items {}

  private record Text(String value) implements V {}

  private record Money(BigDecimal amount, String currency) implements V {}

  private record Num(BigDecimal value) implements V {}

  private record Day(LocalDate value) implements V {}

  private record Moment(Instant value) implements V {}

  private record Flag(boolean value) implements V {}

  private record Items(List<Values> items) implements V {}

  private final Map<String, V> given = new LinkedHashMap<>();

  public static Values of() {
    return new Values();
  }

  public Values text(String name, String value) {
    if (value != null) given.put(name, new Text(value));
    return this;
  }

  public Values money(String name, BigDecimal amount, String currency) {
    if (amount != null && currency != null) given.put(name, new Money(amount, currency));
    return this;
  }

  public Values number(String name, BigDecimal value) {
    if (value != null) given.put(name, new Num(value));
    return this;
  }

  public Values day(String name, LocalDate value) {
    if (value != null) given.put(name, new Day(value));
    return this;
  }

  public Values moment(String name, Instant value) {
    if (value != null) given.put(name, new Moment(value));
    return this;
  }

  public Values flag(String name, boolean value) {
    given.put(name, new Flag(value));
    return this;
  }

  public Values items(String name, List<Values> items) {
    given.put(name, new Items(List.copyOf(items)));
    return this;
  }

  /** Whether a name has a value at all. */
  public boolean has(String name) {
    return given.containsKey(name);
  }

  /** The names given, in the order given. */
  public List<String> names() {
    return new ArrayList<>(given.keySet());
  }

  /** The values as a template reads them, written for this locale. */
  public Function<String, Object> in(Locale locale) {
    return name -> write(given.get(name), locale);
  }

  private static Object write(V v, Locale locale) {
    return switch (v) {
      case null -> null;
      case Text t -> t.value();
      case Flag f -> f.value();
      case Money m -> money(m.amount(), m.currency(), locale);
      case Num n -> number(n.value(), locale);
      case Day d ->
          DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(d.value());
      case Moment m -> moment(m.value(), locale);
      case Items items -> listed(items.items(), locale);
    };
  }

  /** Each item, knowing whether it is the first or the last: where a list's separators go. */
  private static List<Function<String, Object>> listed(List<Values> items, Locale locale) {
    List<Function<String, Object>> out = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      Function<String, Object> own = items.get(i).in(locale);
      boolean first = i == 0;
      boolean last = i == items.size() - 1;
      out.add(
          name ->
              switch (name) {
                case "first" -> first;
                case "last" -> last;
                default -> own.apply(name);
              });
    }
    return out;
  }

  static String money(BigDecimal amount, String currency, Locale locale) {
    NumberFormat f = NumberFormat.getCurrencyInstance(locale);
    try {
      Currency c = Currency.getInstance(currency);
      f.setCurrency(c);
      int digits = Math.max(c.getDefaultFractionDigits(), 0);
      f.setMinimumFractionDigits(digits);
      f.setMaximumFractionDigits(digits);
    } catch (IllegalArgumentException e) {
      // Not an ISO 4217 code: say the amount and the code as they came.
      return amount.toPlainString() + " " + currency;
    }
    return f.format(amount);
  }

  static String number(BigDecimal value, Locale locale) {
    NumberFormat f = NumberFormat.getNumberInstance(locale);
    f.setMaximumFractionDigits(4);
    f.setGroupingUsed(value.abs().compareTo(BigDecimal.valueOf(10000)) >= 0);
    return f.format(value);
  }

  /** A moment in UTC — the only zone a message about a store's event can be sure of. */
  static String moment(Instant value, Locale locale) {
    var at = value.atOffset(ZoneOffset.UTC);
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(at)
        + ", "
        + DateTimeFormatter.ofPattern("HH:mm").format(at)
        + " UTC";
  }
}
