package com.shelfj.tenant.service;

import com.shelfj.ids.Ids;
import com.shelfj.tenant.domain.Domain.IncidentEvent;
import com.shelfj.tenant.domain.Domain.IncidentSheet;
import com.shelfj.tenant.domain.Domain.NoticeIssue;
import com.shelfj.tenant.domain.Domain.ReportingStage;
import com.shelfj.tenant.domain.Domain.SecurityIncident;
import com.shelfj.tenant.domain.Domain.SecurityNotice;
import com.shelfj.tenant.dto.Dtos.CreateIncidentRequest;
import com.shelfj.tenant.dto.Dtos.IssueNoticesRequest;
import com.shelfj.tenant.dto.Dtos.RecordIncidentEventRequest;
import com.shelfj.tenant.repo.SecurityIncidentRepository;
import com.shelfj.web.ApiException;
import com.shelfj.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

  @Inject SecurityIncidentRepository repo;

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
