package com.storeql.tenant.service;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.BreachDuty;
import com.storeql.tenant.domain.Domain.DutyState;
import com.storeql.tenant.domain.Domain.IncidentEvent;
import com.storeql.tenant.domain.Domain.IncidentSheet;
import com.storeql.tenant.domain.Domain.LegalObligation;
import com.storeql.tenant.domain.Domain.NoticeDuties;
import com.storeql.tenant.domain.Domain.NoticeIssue;
import com.storeql.tenant.domain.Domain.NoticeReport;
import com.storeql.tenant.domain.Domain.ReportingStage;
import com.storeql.tenant.domain.Domain.SecurityIncident;
import com.storeql.tenant.domain.Domain.SecurityNotice;
import com.storeql.tenant.dto.Dtos.CreateIncidentRequest;
import com.storeql.tenant.dto.Dtos.IssueNoticesRequest;
import com.storeql.tenant.dto.Dtos.RecordDutyRequest;
import com.storeql.tenant.dto.Dtos.RecordIncidentEventRequest;
import com.storeql.tenant.repo.ObligationRepository;
import com.storeql.tenant.repo.SecurityIncidentRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The platform's security incident register (21.15): an incident opened when the platform becomes
 * aware of it, the statutory reports recorded against it as they are made, and notices to the
 * businesses it affects, which they acknowledge.
 */
@ApplicationScoped
public class SecurityIncidentService {

  static final int MAX_TENANTS = 500;
  private static final int MAX_TITLE = 200;
  private static final int MAX_SUMMARY = 4000;
  private static final int MAX_REFERENCE = 120;
  private static final int MAX_NOTE = 2000;
  private static final int MAX_MESSAGE = 4000;

  static final String KIND_BREACH = "PERSONAL_DATA_BREACH";
  static final String REGIME_DPDP = "DPDP";
  static final String REGIME_GDPR = "GDPR";
  static final String REGIME_DPDP_OBLIGATION = "DPDP";

  @Inject SecurityIncidentRepository repo;
  @Inject ObligationRepository obligations;
  @Inject TenantService tenants;

  Clock clock = Clock.systemUTC();

  /**
   * Opens an incident.
   *
   * @throws ApiException 400 for an unknown kind, a title or summary out of bounds, an awareness in
   *     the future, or tenants that are not businesses on the platform
   */
  public IncidentSheet open(CreateIncidentRequest req, UUID actor) {
    String kind = upper(req.kind());
    if (!IncidentRules.KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "INCIDENT_KIND_UNKNOWN", "kind must be one of " + new TreeSet<>(IncidentRules.KINDS));
    }
    String title = text(req.title(), MAX_TITLE, "INCIDENT_TITLE_INVALID", "title");
    String summary = text(req.summary(), MAX_SUMMARY, "INCIDENT_SUMMARY_INVALID", "summary");
    Instant now = clock.instant();
    Instant aware = Parsing.instant(req.awareAt(), "awareAt");
    if (aware.isAfter(now.plus(IncidentRules.SKEW))) {
      throw ApiException.badRequest(
          "INCIDENT_AWARE_IN_FUTURE",
          "awareAt is in the future; the statutory clocks run from when the platform became aware");
    }
    List<UUID> tenants =
        req.tenantIds() == null
            ? List.of()
            : req.tenantIds().stream().map(t -> Parsing.uuid(t, "tenantIds")).distinct().toList();
    if (tenants.size() > MAX_TENANTS) {
      throw ApiException.badRequest(
          "INCIDENT_TOO_MANY_TENANTS",
          "name at most " + MAX_TENANTS + " businesses, or none for every business");
    }
    if (!tenants.isEmpty() && repo.existingTenants(tenants) != tenants.size()) {
      throw ApiException.badRequest(
          "INCIDENT_TENANT_UNKNOWN", "one or more tenantIds is not a business on the platform");
    }
    var incident =
        new SecurityIncident(
            Ids.newId(),
            kind,
            title,
            summary,
            aware.truncatedTo(ChronoUnit.MICROS),
            now.truncatedTo(ChronoUnit.MICROS),
            actor,
            tenants.isEmpty(),
            tenants);
    repo.insert(incident);
    return sheet(incident.id());
  }

  /**
   * Incidents newest first, each with its stages as they stand.
   *
   * @throws ApiException 400 {@code INCIDENT_STATUS_UNKNOWN}
   */
  public List<IncidentSheet> list(String status, Integer limit) {
    Boolean closed;
    if (status == null || status.isBlank()) {
      closed = null;
    } else if ("OPEN".equals(upper(status))) {
      closed = false;
    } else if ("CLOSED".equals(upper(status))) {
      closed = true;
    } else {
      throw ApiException.badRequest("INCIDENT_STATUS_UNKNOWN", "status is OPEN or CLOSED");
    }
    int n = limit == null ? 50 : Math.max(1, Math.min(100, limit));
    Instant now = clock.instant();
    Map<String, List<ReportingStage>> law = new HashMap<>();
    return repo.list(closed, n).stream()
        .map(
            i -> {
              List<IncidentEvent> events = repo.events(i.id());
              List<ReportingStage> stages = law.computeIfAbsent(i.kind(), repo::stages);
              return new IncidentSheet(
                  i,
                  events,
                  IncidentRules.stages(i, events, stages, now),
                  IncidentRules.closed(events),
                  0,
                  0);
            })
        .toList();
  }

  /**
   * One incident with its timeline, stages and notices.
   *
   * @throws ApiException 404 {@code INCIDENT_NOT_FOUND}
   */
  public IncidentSheet sheet(UUID id) {
    SecurityIncident incident = find(id);
    List<IncidentEvent> events = repo.events(id);
    NoticeIssue notices = repo.noticeCounts(id);
    return new IncidentSheet(
        incident,
        events,
        IncidentRules.stages(incident, events, repo.stages(incident.kind()), clock.instant()),
        IncidentRules.closed(events),
        notices.total(),
        notices.acknowledged());
  }

  /**
   * Records a report made, a measure available, a note, or the close.
   *
   * @throws ApiException the refusals of {@link IncidentRules#check}; 400 for a reference or note
   *     out of bounds
   */
  public IncidentSheet record(UUID id, RecordIncidentEventRequest req, UUID actor) {
    String kind = upper(req.kind());
    String reference =
        optionalText(req.reference(), MAX_REFERENCE, "INCIDENT_REFERENCE_INVALID", "reference");
    String note = optionalText(req.note(), MAX_NOTE, "INCIDENT_NOTE_INVALID", "note");
    if (IncidentRules.NOTE.equals(kind) && note == null) {
      throw ApiException.badRequest("INCIDENT_NOTE_REQUIRED", "a note needs its text");
    }
    SecurityIncident known = find(id);
    List<ReportingStage> law = repo.stages(known.kind());
    Instant now = clock.instant();
    Instant occurred =
        req.occurredAt() == null || req.occurredAt().isBlank()
            ? now
            : Parsing.instant(req.occurredAt(), "occurredAt");
    repo.recordEvent(
        id,
        (incident, events) -> {
          IncidentRules.check(incident, events, law, kind, occurred, now);
          return new IncidentEvent(
              Ids.newId(),
              id,
              kind,
              occurred.truncatedTo(ChronoUnit.MICROS),
              now.truncatedTo(ChronoUnit.MICROS),
              actor,
              reference,
              note);
        });
    return sheet(id);
  }

  /**
   * Tells every business the incident affects, once each; records that businesses were told the
   * first time.
   *
   * @throws ApiException 404 {@code INCIDENT_NOT_FOUND}; 409 {@code INCIDENT_CLOSED}; 400 for a
   *     message out of bounds
   */
  public NoticeIssue issueNotices(UUID id, IssueNoticesRequest req, UUID actor) {
    String message = text(req.message(), MAX_MESSAGE, "NOTICE_MESSAGE_INVALID", "message");
    SecurityIncident known = find(id);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    return repo.issueNotices(
        id,
        (incident, events) -> {
          if (IncidentRules.closed(events)) {
            throw ApiException.conflict(
                "INCIDENT_CLOSED", "This incident is closed; nothing more is recorded against it");
          }
          boolean told =
              events.stream().anyMatch(e -> IncidentRules.TENANTS_NOTIFIED.equals(e.kind()));
          return told
              ? null
              : new IncidentEvent(
                  Ids.newId(), id, IncidentRules.TENANTS_NOTIFIED, now, now, actor, null, null);
        },
        "Security notice: " + known.title(),
        message,
        actor,
        now);
  }

  /** A business's own notices, newest first. */
  public List<SecurityNotice> notices(UUID tenantId) {
    return repo.noticesFor(tenantId);
  }

  /**
   * What a business owes on a notice of a personal data breach, under the regime its country puts
   * it under (13.12): India's DPDP Act where the register carries it, binding or upcoming; the GDPR
   * elsewhere. A notice of anything but a breach carries no duties.
   */
  public NoticeDuties duties(UUID tenantId, SecurityNotice notice) {
    SecurityIncident incident = repo.find(notice.incidentId()).orElse(null);
    if (incident == null || !KIND_BREACH.equals(incident.kind())) {
      return NoticeDuties.none();
    }
    String country = tenants.getTenant(tenantId).country();
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    LegalObligation dpdp =
        obligations.forCountry(country).stream()
            .filter(o -> REGIME_DPDP_OBLIGATION.equals(o.code()))
            .findFirst()
            .orElse(null);
    String regime = dpdp == null ? REGIME_GDPR : REGIME_DPDP;
    boolean binding =
        dpdp == null
            || (!dpdp.effectiveFrom().isAfter(today)
                && (dpdp.effectiveTo() == null || !dpdp.effectiveTo().isBefore(today)));
    Map<String, NoticeReport> reports = new HashMap<>();
    for (NoticeReport r : repo.reportsFor(tenantId, notice.id())) reports.put(r.duty(), r);
    Instant now = clock.instant();
    List<DutyState> states = new ArrayList<>();
    for (BreachDuty d : repo.breachDuties(regime)) {
      Instant dueAt =
          d.dueAfter() == null ? null : IncidentRules.plus(notice.issuedAt(), d.dueAfter());
      NoticeReport report = reports.get(d.duty());
      String state;
      if (report != null) state = IncidentRules.DONE;
      else if (dueAt == null) state = IncidentRules.WAITING;
      else state = dueAt.isBefore(now) ? IncidentRules.OVERDUE : IncidentRules.DUE;
      states.add(new DutyState(d.duty(), d.citation(), d.summary(), dueAt, state, report));
    }
    return new NoticeDuties(regime, binding, dpdp == null ? null : dpdp.effectiveFrom(), states);
  }

  /**
   * Records a duty done on a business's notice, once.
   *
   * @throws ApiException 404 {@code SECURITY_NOTICE_NOT_FOUND}; 400 {@code
   *     SECURITY_NOTICE_DUTY_UNKNOWN} for a duty the regime does not put on the business, or {@code
   *     SECURITY_NOTICE_NO_DUTIES} for a notice that is not of a breach; 409 {@code
   *     SECURITY_NOTICE_DUTY_DONE} when that duty was recorded already
   */
  public NoticeDuties report(UUID tenantId, UUID noticeId, RecordDutyRequest req, UUID actor) {
    SecurityNotice notice =
        repo.noticeOf(tenantId, noticeId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "SECURITY_NOTICE_NOT_FOUND", "This business has no such security notice"));
    NoticeDuties duties = duties(tenantId, notice);
    if (duties.regime() == null) {
      throw ApiException.badRequest(
          "SECURITY_NOTICE_NO_DUTIES", "this notice is not of a personal data breach");
    }
    String duty = upper(req.duty());
    if (duties.duties().stream().noneMatch(d -> d.duty().equals(duty))) {
      throw ApiException.badRequest(
          "SECURITY_NOTICE_DUTY_UNKNOWN",
          "under "
              + duties.regime()
              + " the duties are "
              + String.join(", ", duties.duties().stream().map(DutyState::duty).toList()));
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant doneAt =
        req.doneAt() == null || req.doneAt().isBlank()
            ? now
            : Parsing.instant(req.doneAt(), "doneAt");
    if (doneAt.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
      throw ApiException.badRequest("SECURITY_NOTICE_DONE_AT_FUTURE", "doneAt is in the future");
    }
    NoticeReport r =
        new NoticeReport(
            Ids.newId(),
            tenantId,
            noticeId,
            duty,
            doneAt,
            req.reference() == null || req.reference().isBlank()
                ? null
                : text(
                    req.reference(),
                    MAX_REFERENCE,
                    "SECURITY_NOTICE_REFERENCE_INVALID",
                    "reference"),
            req.note() == null || req.note().isBlank()
                ? null
                : text(req.note(), MAX_NOTE, "SECURITY_NOTICE_NOTE_INVALID", "note"),
            actor,
            now);
    if (!repo.recordReport(r)) {
      throw ApiException.conflict(
          "SECURITY_NOTICE_DUTY_DONE", duty + " was recorded on this notice already");
    }
    return duties(tenantId, notice);
  }

  /**
   * Acknowledges a business's notice; a second acknowledgement returns the first.
   *
   * @throws ApiException 404 {@code SECURITY_NOTICE_NOT_FOUND}, including another business's notice
   */
  public SecurityNotice acknowledge(UUID tenantId, UUID noticeId, UUID actor) {
    return repo.acknowledge(
            tenantId, noticeId, actor, clock.instant().truncatedTo(ChronoUnit.MICROS))
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "SECURITY_NOTICE_NOT_FOUND", "This business has no such security notice"));
  }

  private SecurityIncident find(UUID id) {
    return repo.find(id)
        .orElseThrow(
            () -> ApiException.notFound("INCIDENT_NOT_FOUND", "No such security incident"));
  }

  private static String upper(String s) {
    return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
  }

  private static String text(String value, int max, String code, String field) {
    String t = value == null ? "" : value.trim();
    if (t.isEmpty() || t.length() > max) {
      throw ApiException.badRequest(code, field + " is 1 to " + max + " characters");
    }
    return t;
  }

  private static String optionalText(String value, int max, String code, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return text(value, max, code, field);
  }
}
