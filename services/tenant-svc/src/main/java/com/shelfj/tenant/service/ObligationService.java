package com.shelfj.tenant.service;

import com.shelfj.tenant.domain.Domain.LegalObligation;
import com.shelfj.tenant.domain.Domain.ObligationSheet;
import com.shelfj.tenant.repo.ObligationRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Which laws bind a business: the jurisdiction rules every legal fix asks instead of carrying its
 * own list of countries and dates. The tenant's country is the default because it is the one the
 * business declared and cannot change; another country may be asked about, since a chain can trade
 * across a border and the rules are public.
 */
@ApplicationScoped
public class ObligationService {

  private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

  /** Outside this range a date is a typing error, not a question about the law. */
  private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

  private static final LocalDate LATEST = LocalDate.of(2100, 12, 31);

  @Inject ObligationRepository repo;
  @Inject TenantService tenants;

  /** The day "today" means; UTC, like every date this platform stores. */
  Clock clock = Clock.systemUTC();

  /**
   * The obligations that bind a country on a day.
   *
   * @param country ISO 3166-1 alpha-2, or blank for the tenant's own
   * @param on yyyy-mm-dd, or blank for today
   * @return those in force on the day, then those still to come; anything that had ended is left
   *     out
   * @throws ApiException 400 {@code COUNTRY_INVALID} or {@code OBLIGATION_DATE_INVALID}; 409 {@code
   *     TENANT_COUNTRY_MISSING} when the tenant never declared one
   */
  public ObligationSheet obligations(UUID tenantId, String country, String on) {
    String cc;
    if (country == null || country.isBlank()) {
      cc = tenants.getTenant(tenantId).country();
      if (cc == null || cc.isBlank()) {
        throw ApiException.conflict(
            "TENANT_COUNTRY_MISSING",
            "this business has no country, so no law can be matched to it");
      }
    } else {
      cc = country.trim().toUpperCase(Locale.ROOT);
    }
    if (!COUNTRIES.contains(cc)) {
      throw ApiException.badRequest(
          "COUNTRY_INVALID", "country must be an ISO 3166-1 alpha-2 code such as DE or JP");
    }
    LocalDate day = day(on);
    List<LegalObligation> rows =
        repo.forCountry(cc).stream()
            .filter(LegalObligation::everApplies)
            .filter(o -> !o.endedBefore(day))
            .sorted(
                Comparator.comparing((LegalObligation o) -> o.effectiveFrom().isAfter(day))
                    .thenComparing(LegalObligation::effectiveFrom)
                    .thenComparing(LegalObligation::code))
            .toList();
    return new ObligationSheet(cc, day, rows);
  }

  private LocalDate day(String on) {
    if (on == null || on.isBlank()) {
      return LocalDate.now(clock);
    }
    try {
      LocalDate day = LocalDate.parse(on.trim());
      if (!day.isBefore(EARLIEST) && !day.isAfter(LATEST)) {
        return day;
      }
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "OBLIGATION_DATE_INVALID", "on is a date written yyyy-mm-dd", List.of(), e);
    }
    throw ApiException.badRequest("OBLIGATION_DATE_INVALID", "on is a date between 1900 and 2100");
  }
}
