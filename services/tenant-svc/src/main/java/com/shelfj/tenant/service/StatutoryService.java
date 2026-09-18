package com.shelfj.tenant.service;

import com.shelfj.ids.Ids;
import com.shelfj.tenant.domain.Domain.Tenant;
import com.shelfj.tenant.domain.StatutoryReturns;
import com.shelfj.tenant.domain.StatutoryReturns.Filing;
import com.shelfj.tenant.domain.StatutoryReturns.Obligation;
import com.shelfj.tenant.domain.StatutoryReturns.Return;
import com.shelfj.tenant.repo.ObligationRepository;
import com.shelfj.tenant.repo.StatutoryRepository;
import com.shelfj.tenant.repo.TenantRepository;
import com.shelfj.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a business owes each jurisdiction, when, and what it has filed.
 *
 * <p>The exports already exist: order-svc produces Germany's DSFinV-K and Portugal's SAF-T,
 * pricing-svc the VAT return and its MTD submission. What did not exist is the answer to the
 * question an auditor actually asks — not "can you generate a SAF-T?" but "show me the one you
 * filed for September".
 *
 * <p>Three things are deliberate.
 *
 * <p><b>Every date and every state is derived here, on every read.</b> Nothing is stored. A due
 * date in a column is a due date that goes stale the first time a rule changes, and the rule
 * changing is the one thing a statutory calendar can count on.
 *
 * <p><b>Membership is asked of the period, not of today.</b> A regime's return reaches a business
 * for the periods its country was a member, and not for the ones after it left — so the question
 * goes to {@code jurisdiction_members} with the period's own dates. Asking "is it a member now?"
 * would quietly drop the quarters it owed.
 *
 * <p><b>The export is linked, never proxied.</b> SAF-T belongs to order-svc and the VAT return to
 * pricing-svc, and serving their bytes from here would be one service reading another's data. A
 * return says where its export lives and the console follows it.
 */
@ApplicationScoped
public class StatutoryService {

  /**
   * How many periods back the calendar reaches.
   *
   * <p>Two years of months. Far enough that a business coming to the platform mid-year sees what it
   * has not filed, bounded so the answer does not grow without limit as the platform ages.
   */
  private static final int PERIODS_BACK = 24;

  @Inject StatutoryRepository repo;
  @Inject ObligationRepository jurisdictions;
  @Inject TenantRepository tenants;

  /**
   * Every return this business owes, period by period, with each one's date and state worked out.
   *
   * @param asOf the day the question is asked, which is what {@code DUE} and {@code OVERDUE} are
   *     relative to
   * @return newest period first within each return, so the thing needing attention is near the top
   */
  public List<Obligation> calendar(UUID tenantId, LocalDate asOf) {
    Tenant tenant =
        tenants
            .findTenant(tenantId)
            .orElseThrow(() -> ApiException.notFound("TENANT_NOT_FOUND", "No such business"));
    String country = tenant.country();
    List<Obligation> out = new ArrayList<>();

    // Every filing that stands, read once and keyed on (return, period). Asking the database per
    // period would be a query for each cell of the calendar — four returns over twenty-four periods
    // is ninety-six round trips to answer one screen, and the answer is the same either way.
    Map<String, Filing> standing = new HashMap<>();
    for (Filing f : repo.filingsOf(tenantId)) {
      if (f.stands()) standing.put(key(f.returnCode(), f.periodStart()), f);
    }

    for (Return owed : repo.candidatesFor(country)) {
      LocalDate period = StatutoryReturns.periodStart(owed.frequency(), asOf);
      for (int back = 0; back < PERIODS_BACK; back++) {
        LocalDate periodEnd = StatutoryReturns.periodEnd(owed.frequency(), period);
        if (reaches(owed, country, periodEnd)) {
          LocalDate dueOn = owed.dueOn(periodEnd);
          Filing filed = standing.get(key(owed.code(), period));
          out.add(
              new Obligation(
                  owed,
                  period,
                  periodEnd,
                  dueOn,
                  StatutoryReturns.stateOf(periodEnd, dueOn, filed, asOf),
                  filed));
        }
        period = StatutoryReturns.previousPeriod(owed.frequency(), period);
      }
    }
    return out;
  }

  private static String key(String code, LocalDate periodStart) {
    return code + '@' + periodStart;
  }

  /**
   * Whether one return reaches this business for one period.
   *
   * <p>Both halves are asked of the period rather than of today: the return has to have been in
   * force during it, and for a regime's return the country has to have been a member during it. The
   * period's <em>last</em> day is what both are asked about — a return that came into force
   * mid-period applies to the period it was in force at the end of, which is how the instruments
   * themselves read.
   */
  // One indexed query per period for a regime's return, bounded by PERIODS_BACK. Left as it is
  // deliberately: membership can have gaps, so a single window would have to assume it does not,
  // and
  // being right about a business that left and rejoined is worth twenty-four cheap lookups.
  private boolean reaches(Return owed, String country, LocalDate end) {
    LocalDate lastDay = end.minusDays(1);
    if (!owed.inForceOn(lastDay)) return false;
    if ("COUNTRY".equals(owed.scopeKind())) return true;
    return jurisdictions.memberOn(owed.scope(), country, lastDay);
  }

  private Obligation obligation(
      UUID tenantId, Return owed, LocalDate start, LocalDate end, LocalDate asOf) {
    LocalDate dueOn = owed.dueOn(end);
    Filing standing = repo.standingFor(tenantId, owed.code(), start).orElse(null);
    return new Obligation(
        owed, start, end, dueOn, StatutoryReturns.stateOf(end, dueOn, standing, asOf), standing);
  }

  /** Only what needs attention: owed and not filed, oldest first, which is the order to act in. */
  public List<Obligation> outstanding(UUID tenantId, LocalDate asOf) {
    return calendar(tenantId, asOf).stream()
        .filter(Obligation::actionable)
        .sorted((a, b) -> a.dueOn().compareTo(b.dueOn()))
        .toList();
  }

  /**
   * Records that a return went.
   *
   * @param periodStart the period filed for; it must be a real period of that return, so a caller
   *     cannot invent one and file against it
   * @param supersedes the filing this corrects, or null
   * @throws ApiException 404 {@code STATUTORY_RETURN_UNKNOWN}; 400 {@code
   *     STATUTORY_PERIOD_NOT_A_PERIOD} or {@code STATUTORY_PROVIDER_UNKNOWN}; 409 {@code
   *     STATUTORY_PERIOD_NOT_ENDED} or {@code STATUTORY_FILING_EXISTS}
   */
  public Obligation file(
      UUID tenantId,
      String code,
      LocalDate periodStart,
      String reference,
      String provider,
      String payloadDigest,
      String note,
      UUID supersedes,
      UUID actorId,
      LocalDate asOf) {
    Return owed =
        repo.byCode(code, periodStart)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "STATUTORY_RETURN_UNKNOWN",
                        "No return "
                            + code
                            + " was in force for a period beginning "
                            + periodStart));
    if (!StatutoryReturns.PROVIDERS.contains(provider)) {
      throw ApiException.badRequest(
          "STATUTORY_PROVIDER_UNKNOWN",
          "A filing goes by " + String.join(", ", StatutoryReturns.PROVIDERS));
    }
    // The period has to be one of this return's own. Filing "the month beginning the 14th" would
    // put a
    // row on the calendar that no period ever matches, and it would sit there for ever looking
    // unfiled.
    if (!periodStart.equals(StatutoryReturns.periodStart(owed.frequency(), periodStart))) {
      throw ApiException.badRequest(
          "STATUTORY_PERIOD_NOT_A_PERIOD",
          periodStart
              + " does not begin a "
              + owed.frequency().toLowerCase(Locale.ROOT)
              + " period");
    }
    LocalDate periodEnd = StatutoryReturns.periodEnd(owed.frequency(), periodStart);
    // Nothing is filed for a period that has not finished: the figures do not exist yet, and a
    // filing
    // recorded against them would be evidence of something that had not happened.
    if (asOf.isBefore(periodEnd)) {
      throw ApiException.conflict(
          "STATUTORY_PERIOD_NOT_ENDED",
          "That period runs to " + periodEnd + " and cannot be filed for until it has ended");
    }

    Optional<Filing> standing = repo.standingFor(tenantId, code, periodStart);
    if (standing.isPresent() && supersedes == null) {
      throw ApiException.conflict(
          "STATUTORY_FILING_EXISTS",
          "This period has already been filed; a correction names the filing it replaces");
    }
    if (supersedes != null && (standing.isEmpty() || !standing.get().id().equals(supersedes))) {
      throw ApiException.conflict(
          "STATUTORY_FILING_NOT_STANDING",
          "That filing is not the one standing for this period, so it cannot be the one corrected");
    }

    repo.file(
        new Filing(
            Ids.newId(),
            tenantId,
            code,
            periodStart,
            periodEnd,
            java.time.Instant.now(),
            actorId,
            blankToNull(reference),
            provider,
            blankToNull(payloadDigest),
            supersedes,
            null,
            blankToNull(note)),
        supersedes);
    return obligation(tenantId, owed, periodStart, periodEnd, asOf);
  }

  /**
   * Everything this business has filed, superseded rows included, because both stay on the record.
   */
  public List<Filing> filings(UUID tenantId) {
    return repo.filingsOf(tenantId);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
