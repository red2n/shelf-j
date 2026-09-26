package com.storeql.customer.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A phone number to E.164, tried against the business's own regions — never one named in code,
 * because the platform is multi-tenant and multi-location: a business's home country first, then
 * each of its stores'. A number already written with a leading {@code "+"} carries its own country
 * calling code and needs no region at all.
 *
 * <p>Pure: no I/O, no tenant lookup. The caller (the service layer, which alone holds {@code
 * TenantProfiles}) resolves the business's regions and hands them in, in the order to try them.
 */
public final class PhoneNumbers {

  private PhoneNumbers() {}

  private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

  /**
   * Normalises a number to E.164.
   *
   * @param raw as typed or stored; {@code null} or blank ⇒ {@code null}
   * @param homeCountry the business's own country (ISO 3166-1 alpha-2), tried first; {@code null}
   *     when it is not known or could not be read
   * @param storeCountries the business's stores' countries, tried next, each once, in the order
   *     given (duplicates and the home country, once already tried, are skipped); {@code null} is
   *     treated as none
   * @return the E.164 form ({@code "+" + digits}), or {@code null} when {@code raw} was typed with
   *     a leading {@code "+"} that is not a valid number, or when none of the regions offered — an
   *     empty list included — parse it to one. The phone is kept as typed either way; this only
   *     answers what to index it under.
   */
  public static String toE164(String raw, String homeCountry, Collection<String> storeCountries) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.startsWith("+")) {
      // Carries its own country calling code; no region rescues one that doesn't parse under it.
      return parseValid(trimmed, null);
    }
    for (String region : regions(homeCountry, storeCountries)) {
      String e164 = parseValid(trimmed, region);
      if (e164 != null) {
        return e164;
      }
    }
    return null;
  }

  /** The regions to try, home country first, then the rest, each named at most once. */
  private static List<String> regions(String homeCountry, Collection<String> storeCountries) {
    Set<String> ordered = new LinkedHashSet<>();
    if (present(homeCountry)) {
      ordered.add(up(homeCountry));
    }
    if (storeCountries != null) {
      for (String country : storeCountries) {
        if (present(country)) {
          ordered.add(up(country));
        }
      }
    }
    return List.copyOf(ordered);
  }

  private static String parseValid(String raw, String region) {
    try {
      PhoneNumber parsed = UTIL.parse(raw, region);
      if (!UTIL.isValidNumber(parsed)) {
        return null;
      }
      return UTIL.format(parsed, PhoneNumberFormat.E164);
    } catch (NumberParseException | RuntimeException e) {
      // An unsupported/blank region, or a number that simply isn't one under it: try the next.
      return null;
    }
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  private static String up(String s) {
    return s.strip().toUpperCase(Locale.ROOT);
  }
}
