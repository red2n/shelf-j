package com.storeql.tenant.service;

import com.storeql.tenant.domain.Domain.IncidentEvent;
import com.storeql.tenant.domain.Domain.ReportingStage;
import com.storeql.tenant.domain.Domain.SecurityIncident;
import com.storeql.tenant.domain.Domain.StageStatus;
import com.storeql.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The clocks and rules of the security incident register (21.15), without a database. The deadlines
 * are the statutory stages in {@code incident_reporting_stages}; this only reads them against an
 * incident's timeline.
 */
public final class IncidentRules {

  public static final Set<String> KINDS =
      Set.of("EXPLOITED_VULNERABILITY", "SEVERE_INCIDENT", "PERSONAL_DATA_BREACH");

  public static final String EARLY_WARNING_SENT = "EARLY_WARNING_SENT";
  public static final String NOTIFICATION_SENT = "NOTIFICATION_SENT";
  public static final String MITIGATION_AVAILABLE = "MITIGATION_AVAILABLE";
  public static final String FINAL_REPORT_SENT = "FINAL_REPORT_SENT";
  public static final String TENANTS_NOTIFIED = "TENANTS_NOTIFIED";
  public static final String NOTE = "NOTE";
  public static final String CLOSED = "CLOSED";

  public static final Set<String> EVENT_KINDS =
      Set.of(
          EARLY_WARNING_SENT,
          NOTIFICATION_SENT,
          MITIGATION_AVAILABLE,
          FINAL_REPORT_SENT,
          TENANTS_NOTIFIED,
          NOTE,
          CLOSED);

  public static final String DONE = "DONE";
  public static final String DUE = "DUE";
  public static final String OVERDUE = "OVERDUE";
  public static final String WAITING = "WAITING";
  public static final String NO_DEADLINE = "NO_DEADLINE";

  /** An event reported from a workstation a few minutes fast is not refused as in the future. */
  static final Duration SKEW = Duration.ofMinutes(5);

  private static final Map<String, String> COMPLETED_BY =
      Map.of(
          "EARLY_WARNING", EARLY_WARNING_SENT,
          "NOTIFICATION", NOTIFICATION_SENT,
          "FINAL_REPORT", FINAL_REPORT_SENT,
          "TENANT_NOTICE", TENANTS_NOTIFIED);

  /** The event each anchor waits on; AWARE is the incident's own moment of awareness. */
  private static final Map<String, String> ANCHORED_BY =
      Map.of("NOTIFIED", NOTIFICATION_SENT, "MITIGATED", MITIGATION_AVAILABLE);

  private IncidentRules() {}

  /** Every stage the law sets for this incident, as it stands at {@code now}. */
  public static List<StageStatus> stages(
      SecurityIncident incident,
      List<IncidentEvent> events,
      List<ReportingStage> law,
      Instant now) {
    Map<String, IncidentEvent> once = once(events);
    List<StageStatus> out = new ArrayList<>();
    for (ReportingStage s :
        law.stream().sorted(Comparator.comparingInt(ReportingStage::position)).toList()) {
      IncidentEvent done = once.get(COMPLETED_BY.get(s.stage()));
      Instant anchor = anchor(s.anchor(), incident, once);
      Instant due = s.dueAfter() == null || anchor == null ? null : plus(anchor, s.dueAfter());
      String state;
      if (done != null) {
        state = DONE;
      } else if (s.dueAfter() == null) {
        state = NO_DEADLINE;
      } else if (due == null) {
        state = WAITING;
      } else {
        state = now.isAfter(due) ? OVERDUE : DUE;
      }
      out.add(
          new StageStatus(
              s.stage(),
              s.summary(),
              s.citation(),
              due,
              done == null ? null : done.occurredAt(),
              state));
    }
    return out;
  }

  /** The unfinished stage due soonest, or null when no clock is running. */
  public static StageStatus next(List<StageStatus> stages) {
    return stages.stream()
        .filter(s -> s.dueAt() != null && !DONE.equals(s.state()))
        .min(Comparator.comparing(StageStatus::dueAt))
        .orElse(null);
  }

  /**
   * An ISO-8601 time after an instant: a duration (PT24H) exactly, a period (P1M) on the calendar.
   */
  public static Instant plus(Instant anchor, String iso) {
    return iso.contains("T")
        ? anchor.plus(Duration.parse(iso))
        : anchor.atZone(ZoneOffset.UTC).plus(Period.parse(iso)).toInstant();
  }

  public static boolean closed(List<IncidentEvent> events) {
    return events.stream().anyMatch(e -> CLOSED.equals(e.kind()));
  }

  /**
   * Whether an event may be recorded against the incident as it stands.
   *
   * @throws ApiException the refusal that applies
   */
  public static void check(
      SecurityIncident incident,
      List<IncidentEvent> events,
      List<ReportingStage> law,
      String kind,
      Instant occurredAt,
      Instant now) {
    Map<String, IncidentEvent> once = once(events);
    if (once.containsKey(CLOSED)) {
      throw ApiException.conflict(
          "INCIDENT_CLOSED", "This incident is closed; nothing more is recorded against it");
    }
    if (!EVENT_KINDS.contains(kind)) {
      throw ApiException.badRequest(
          "INCIDENT_EVENT_UNKNOWN", "kind must be one of " + new TreeSet<>(EVENT_KINDS));
    }
    if (occurredAt.isBefore(incident.awareAt())) {
      throw ApiException.badRequest(
          "INCIDENT_EVENT_BEFORE_AWARE",
          "Nothing is reported before the platform became aware of the incident");
    }
    if (occurredAt.isAfter(now.plus(SKEW))) {
      throw ApiException.badRequest("INCIDENT_EVENT_IN_FUTURE", "occurredAt is in the future");
    }
    if (TENANTS_NOTIFIED.equals(kind)) {
      throw ApiException.badRequest(
          "INCIDENT_EVENT_NOT_APPLICABLE",
          "Businesses are told by issuing notices, which records this itself");
    }
    if (NOTE.equals(kind)) {
      return;
    }
    if (once.containsKey(kind)) {
      throw ApiException.conflict(
          "INCIDENT_STAGE_ALREADY_RECORDED", kind + " is already recorded for this incident");
    }
    Map<String, ReportingStage> byStage = new HashMap<>();
    law.forEach(s -> byStage.put(s.stage(), s));
    switch (kind) {
      case CLOSED -> {
        List<String> open =
            stages(incident, events, law, now).stream()
                .filter(s -> !DONE.equals(s.state()))
                .map(StageStatus::stage)
                .toList();
        if (!open.isEmpty()) {
          throw ApiException.conflict(
              "INCIDENT_STAGES_OUTSTANDING",
              "Not closed while these stages are outstanding: " + open);
        }
      }
      case MITIGATION_AVAILABLE -> {
        if (byStage.values().stream().noneMatch(s -> "MITIGATED".equals(s.anchor()))) {
          throw notApplicable(kind, incident.kind());
        }
      }
      default -> {
        ReportingStage stage = byStage.get(stageCompletedBy(kind));
        if (stage == null) {
          throw notApplicable(kind, incident.kind());
        }
        if (FINAL_REPORT_SENT.equals(kind)) {
          String waitsOn = ANCHORED_BY.get(stage.anchor());
          if (!once.containsKey(NOTIFICATION_SENT)
              || waitsOn != null && !once.containsKey(waitsOn)) {
            throw ApiException.conflict(
                "INCIDENT_FINAL_REPORT_TOO_EARLY",
                "The final report follows the notification"
                    + ("MITIGATED".equals(stage.anchor())
                        ? " and a corrective or mitigating measure"
                        : ""));
          }
        }
      }
    }
  }

  private static ApiException notApplicable(String kind, String incidentKind) {
    return ApiException.badRequest(
        "INCIDENT_EVENT_NOT_APPLICABLE", kind + " is not a stage of a " + incidentKind);
  }

  private static String stageCompletedBy(String kind) {
    return COMPLETED_BY.entrySet().stream()
        .filter(e -> e.getValue().equals(kind))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");
  }

  private static Instant anchor(
      String anchor, SecurityIncident incident, Map<String, IncidentEvent> once) {
    if ("AWARE".equals(anchor)) {
      return incident.awareAt();
    }
    IncidentEvent e = once.get(ANCHORED_BY.get(anchor));
    return e == null ? null : e.occurredAt();
  }

  private static Map<String, IncidentEvent> once(List<IncidentEvent> events) {
    Map<String, IncidentEvent> out = new HashMap<>();
    for (IncidentEvent e : events) {
      if (!NOTE.equals(e.kind())) {
        out.putIfAbsent(e.kind(), e);
      }
    }
    return out;
  }
}
