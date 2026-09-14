package com.shelfj.product.service;

import com.shelfj.product.domain.Domain.ProductSafety;
import com.shelfj.product.dto.Dtos.SafetyInformationRequest;
import com.shelfj.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What GPSR art.19 asks an online offer to show about a product (01.12), and whether a statement of
 * it is well formed. Pure: whether the regulation binds a business, and whether a manufacturer's
 * country is inside it, are answered by the jurisdiction rules and passed in.
 */
public final class SafetyInformationRules {

  /** The jurisdiction rule that binds a business, and marks a country as inside the regime. */
  public static final String GPSR_ONLINE_OFFER = "GPSR_ONLINE_OFFER";

  public static final String MANUFACTURER_NAME = "MANUFACTURER_NAME";
  public static final String MANUFACTURER_ADDRESS = "MANUFACTURER_ADDRESS";
  public static final String MANUFACTURER_CONTACT = "MANUFACTURER_CONTACT";
  public static final String MANUFACTURER_COUNTRY = "MANUFACTURER_COUNTRY";
  public static final String RESPONSIBLE_PERSON_NAME = "RESPONSIBLE_PERSON_NAME";
  public static final String RESPONSIBLE_PERSON_ADDRESS = "RESPONSIBLE_PERSON_ADDRESS";
  public static final String RESPONSIBLE_PERSON_CONTACT = "RESPONSIBLE_PERSON_CONTACT";
  public static final String WARNINGS = "WARNINGS";

  static final int MAX_NAME = 200;
  static final int MAX_ADDRESS = 500;
  static final int MAX_CONTACT = 254;
  static final int MAX_WARNINGS = 4000;

  private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
  private static final Pattern EMAIL =
      Pattern.compile("^[^\\s@<>\"':/]+@[^\\s@<>\"':/]+\\.[^\\s@<>\"':/]{2,}$");
  private static final Pattern HTTPS_URL = Pattern.compile("^https://[^\\s<>\"']+\\.[^\\s<>\"']+$");

  private SafetyInformationRules() {}

  /**
   * Validates and normalises a statement: trimmed, blank as absent, the country upper-cased.
   *
   * @throws ApiException 400 {@code SAFETY_TEXT_INVALID} for a value too long or carrying control
   *     characters, {@code SAFETY_CONTACT_INVALID} for a contact that is neither an e-mail address
   *     nor an https:// URL, {@code SAFETY_COUNTRY_INVALID}, and {@code SAFETY_WARNINGS_CONFLICT}
   *     when warnings are given together with the statement that none apply
   */
  public static ProductSafety normalise(
      UUID tenantId, UUID productId, SafetyInformationRequest req, UUID actor, Instant at) {
    String warnings = text(req.warnings(), MAX_WARNINGS, "warnings", true);
    boolean none = Boolean.TRUE.equals(req.noWarnings());
    if (none && warnings != null) {
      throw ApiException.badRequest(
          "SAFETY_WARNINGS_CONFLICT", "Give the warnings, or state that none apply — not both");
    }
    return new ProductSafety(
        tenantId,
        productId,
        text(req.manufacturerName(), MAX_NAME, "manufacturerName", false),
        text(req.manufacturerAddress(), MAX_ADDRESS, "manufacturerAddress", true),
        contact(req.manufacturerContact(), "manufacturerContact"),
        country(req.manufacturerCountry()),
        text(req.responsiblePersonName(), MAX_NAME, "responsiblePersonName", false),
        text(req.responsiblePersonAddress(), MAX_ADDRESS, "responsiblePersonAddress", true),
        contact(req.responsiblePersonContact(), "responsiblePersonContact"),
        warnings,
        none,
        at,
        actor);
  }

  /**
   * What an online offer still lacks, in the order the regulation lists it.
   *
   * @param s the statement, or null when nothing has been stated
   * @param manufacturerInside whether the manufacturer's country is inside the regime; ignored
   *     until a country is stated, since until then nobody can say whether a responsible person is
   *     needed
   * @return the missing items; empty when complete
   */
  public static List<String> missing(ProductSafety s, boolean manufacturerInside) {
    List<String> out = new ArrayList<>();
    if (s == null || s.manufacturerName() == null) out.add(MANUFACTURER_NAME);
    if (s == null || s.manufacturerAddress() == null) out.add(MANUFACTURER_ADDRESS);
    if (s == null || s.manufacturerContact() == null) out.add(MANUFACTURER_CONTACT);
    if (s == null || s.manufacturerCountry() == null) {
      out.add(MANUFACTURER_COUNTRY);
    } else if (!manufacturerInside) {
      if (s.responsiblePersonName() == null) out.add(RESPONSIBLE_PERSON_NAME);
      if (s.responsiblePersonAddress() == null) out.add(RESPONSIBLE_PERSON_ADDRESS);
      if (s.responsiblePersonContact() == null) out.add(RESPONSIBLE_PERSON_CONTACT);
    }
    if (s == null || s.warnings() == null && !s.noWarnings()) out.add(WARNINGS);
    return out;
  }

  private static String text(String value, int max, String field, boolean multiline) {
    if (value == null || value.isBlank()) return null;
    String t = value.strip();
    boolean control =
        t.chars().anyMatch(ch -> Character.isISOControl(ch) && !(multiline && ch == '\n'));
    if (t.length() > max || control) {
      throw ApiException.badRequest(
          "SAFETY_TEXT_INVALID",
          field
              + " is at most "
              + max
              + " characters"
              + (multiline
                  ? ", with line breaks but no other control characters"
                  : " on one line"));
    }
    return t;
  }

  private static String contact(String value, String field) {
    String t = text(value, MAX_CONTACT, field, false);
    if (t == null) return null;
    if (!EMAIL.matcher(t).matches() && !HTTPS_URL.matcher(t).matches()) {
      throw ApiException.badRequest(
          "SAFETY_CONTACT_INVALID", field + " must be an e-mail address or an https:// URL");
    }
    return t;
  }

  private static String country(String value) {
    if (value == null || value.isBlank()) return null;
    String cc = value.strip().toUpperCase(Locale.ROOT);
    if (!ISO_COUNTRIES.contains(cc)) {
      throw ApiException.badRequest(
          "SAFETY_COUNTRY_INVALID", "manufacturerCountry must be an ISO 3166-1 alpha-2 code");
    }
    return cc;
  }
}
