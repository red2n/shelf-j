package com.shelfj.customer.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A person's privacy as India's DPDP Act 2023 and Rules 2025 shape it, and as it is good sense
 * anywhere (13.12): the notice they read, in their language; what they agreed to, purpose by
 * purpose; who they may complain to; what they asked for and by when it is owed; a child's
 * guardian; and what they were told of a breach.
 */
public final class Privacy {

  private Privacy() {}

  /** The obligation code tenant-svc's register carries for the Act. */
  public static final String OBLIGATION_DPDP = "DPDP";

  public static final String PURPOSE_LOYALTY = "LOYALTY";
  public static final String PURPOSE_MARKETING = "MARKETING";
  public static final String PURPOSE_PERSONALISATION = "PERSONALISATION";
  public static final String PURPOSE_ANALYTICS = "ANALYTICS";

  /**
   * The purposes a person consents to, each on its own. Fulfilling what they bought needs no
   * consent (s.7(a): data given voluntarily for a purpose they asked for), so it is stated in the
   * notice and not asked.
   */
  public static final List<String> PURPOSES =
      List.of(PURPOSE_LOYALTY, PURPOSE_MARKETING, PURPOSE_PERSONALISATION, PURPOSE_ANALYTICS);

  /** What each purpose is, in the words a notice states it. */
  public static final Map<String, String> PURPOSE_TEXT =
      Map.of(
          PURPOSE_LOYALTY,
          "Loyalty: points and store credit on what you buy, and the history behind them",
          PURPOSE_MARKETING,
          "Marketing: offers by email, text, phone or post, on the channels you allow",
          PURPOSE_PERSONALISATION,
          "Personalisation: suggestions and a storefront shaped by what you look at and buy",
          PURPOSE_ANALYTICS,
          "Analytics: how the shop is used, measured across its shoppers");

  /** The purposes that track, monitor or target a person: never for a child (s.9(3)). */
  public static final Set<String> TRACKING =
      Set.of(PURPOSE_MARKETING, PURPOSE_PERSONALISATION, PURPOSE_ANALYTICS);

  public static final String LANGUAGE_DEFAULT = "en";

  /**
   * English and the twenty-two languages of the Eighth Schedule to the Constitution of India, as
   * ISO 639 codes, in the order the Schedule lists them. A notice is offered in English and in any
   * of these on request (r.3(1)).
   */
  public static final Map<String, String> LANGUAGES = languages();

  private static Map<String, String> languages() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("en", "English");
    m.put("as", "Assamese");
    m.put("bn", "Bengali");
    m.put("brx", "Bodo");
    m.put("doi", "Dogri");
    m.put("gu", "Gujarati");
    m.put("hi", "Hindi");
    m.put("kn", "Kannada");
    m.put("ks", "Kashmiri");
    m.put("kok", "Konkani");
    m.put("mai", "Maithili");
    m.put("ml", "Malayalam");
    m.put("mni", "Manipuri");
    m.put("mr", "Marathi");
    m.put("ne", "Nepali");
    m.put("or", "Odia");
    m.put("pa", "Punjabi");
    m.put("sa", "Sanskrit");
    m.put("sat", "Santali");
    m.put("sd", "Sindhi");
    m.put("ta", "Tamil");
    m.put("te", "Telugu");
    m.put("ur", "Urdu");
    return Map.copyOf(m);
  }

  /** The order the Schedule lists the languages in, for a picker. */
  public static final List<String> LANGUAGE_ORDER =
      List.of(
          "en", "as", "bn", "brx", "doi", "gu", "hi", "kn", "ks", "kok", "mai", "ml", "mni", "mr",
          "ne", "or", "pa", "sa", "sat", "sd", "ta", "te", "ur");

  /**
   * A request is answered within the period the business publishes: ninety days at most (r.14(3)).
   */
  public static final int MAX_RESPONSE_DAYS = 90;

  public static final int DEFAULT_RESPONSE_DAYS = 30;

  /** A child is a person under eighteen (s.2(f)). */
  public static final int ADULT_AGE = 18;

  public static final String SOURCE_SIGNUP = "SIGNUP";
  public static final String SOURCE_PREFERENCE_CENTRE = "PREFERENCE_CENTRE";
  public static final String SOURCE_STAFF = "STAFF";
  public static final String SOURCE_WITHDRAW_ALL = "WITHDRAW_ALL";
  public static final String SOURCE_GUARDIAN = "GUARDIAN";

  public static final String VERIFICATION_DETAILS_HELD = "DETAILS_HELD";
  public static final String VERIFICATION_DOCUMENT_SEEN = "DOCUMENT_SEEN";
  public static final String VERIFICATION_DIGITAL_LOCKER = "DIGITAL_LOCKER";
  public static final List<String> VERIFICATIONS =
      List.of(VERIFICATION_DETAILS_HELD, VERIFICATION_DOCUMENT_SEEN, VERIFICATION_DIGITAL_LOCKER);

  public static final String REQUEST_ACCESS = "ACCESS";
  public static final String REQUEST_CORRECTION = "CORRECTION";
  public static final String REQUEST_ERASURE = "ERASURE";
  public static final String REQUEST_NOMINATION = "NOMINATION";
  public static final String REQUEST_GRIEVANCE = "GRIEVANCE";
  public static final List<String> REQUEST_KINDS =
      List.of(
          REQUEST_ACCESS,
          REQUEST_CORRECTION,
          REQUEST_ERASURE,
          REQUEST_NOMINATION,
          REQUEST_GRIEVANCE);

  public static final String STATUS_OPEN = "OPEN";
  public static final String STATUS_RESOLVED = "RESOLVED";
  public static final String STATUS_REFUSED = "REFUSED";
  public static final List<String> STATUSES = List.of(STATUS_OPEN, STATUS_RESOLVED, STATUS_REFUSED);

  /** The business's own answers: who takes grievances, and how long it gives itself. */
  public record Settings(
      UUID tenantId,
      String grievanceName,
      String grievanceEmail,
      String grievancePhone,
      String grievanceAddress,
      int responseDays,
      Instant updatedAt,
      UUID updatedBy) {

    /** A business that has never set anything: the default period, nobody named. */
    public static Settings none(UUID tenantId) {
      return new Settings(tenantId, null, null, null, null, DEFAULT_RESPONSE_DAYS, null, null);
    }

    public boolean hasGrievanceContact() {
      return present(grievanceEmail) || present(grievancePhone);
    }
  }

  /** One version of the notice in one language, as published. */
  public record Notice(
      UUID id,
      UUID tenantId,
      String language,
      int version,
      String title,
      String body,
      Instant publishedAt,
      UUID publishedBy) {}

  /** What a person has agreed to for one purpose, as it stands now. */
  public record PurposeConsent(
      UUID tenantId,
      UUID customerId,
      String purpose,
      boolean granted,
      String noticeLanguage,
      Integer noticeVersion,
      Instant updatedAt) {}

  /** One grant or withdrawal, as evidence. */
  public record ConsentEntry(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String purpose,
      boolean granted,
      String source,
      String noticeLanguage,
      Integer noticeVersion,
      UUID actorId,
      Instant recordedAt) {}

  /** A parent's or guardian's consent for a child, and how they were verified. */
  public record GuardianConsent(
      UUID tenantId,
      UUID customerId,
      String guardianName,
      String verification,
      String reference,
      Instant givenAt,
      UUID recordedBy,
      Instant withdrawnAt,
      UUID withdrawnBy) {

    public boolean standing() {
      return withdrawnAt == null;
    }
  }

  /** What a person asked for, and what became of it. */
  public record Request(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String kind,
      String detail,
      String nomineeName,
      String nomineeContact,
      Instant openedAt,
      LocalDate dueOn,
      String status,
      String resolution,
      Instant resolvedAt,
      UUID resolvedBy) {

    public boolean open() {
      return STATUS_OPEN.equals(status);
    }

    public boolean overdue(LocalDate today) {
      return open() && dueOn.isBefore(today);
    }
  }

  /** A breach told to the business's customers: what, when, to how many. */
  public record Intimation(
      UUID id,
      UUID tenantId,
      UUID noticeId,
      String subject,
      String body,
      Instant sentAt,
      UUID sentBy,
      int recipients,
      int failures) {}

  /** Whether a person with this date of birth is a child today. */
  public static boolean isChild(LocalDate dob, LocalDate today) {
    return dob != null && dob.plusYears(ADULT_AGE).isAfter(today);
  }

  /** The day a request opened now runs out, in the business's published period. */
  public static LocalDate dueOn(Instant openedAt, int responseDays) {
    return LocalDate.ofInstant(openedAt, ZoneOffset.UTC).plusDays(responseDays);
  }

  /** A language code as the notice tables key it, or null for one not offered. */
  public static String language(String raw) {
    if (raw == null) return null;
    String code = raw.strip().toLowerCase(Locale.ROOT);
    return LANGUAGES.containsKey(code) ? code : null;
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }
}
